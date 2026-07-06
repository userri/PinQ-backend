# AWS EC2 → Oracle Cloud Always Free 이전 가이드

현재 아키텍처(docker-compose + nginx/Let's Encrypt + blue-green + 외부 MySQL 컨테이너)를
그대로 Oracle Cloud 무료 ARM 서버로 옮긴다. 애플리케이션 코드 변경은 필요 없다 —
CI 가 amd64+arm64 멀티아치 이미지를 푸시하므로 서버가 알아서 자기 아치를 pull 한다.

> ⚠️ **순서 중요**: 이 문서가 포함된 커밋이 main 에 머지되어 CI 의 멀티아치 이미지
> 빌드가 **성공한 뒤에** 서버 이전을 시작할 것. (arm64 이미지가 Docker Hub 에
> 있어야 오라클 서버에서 pull 이 된다)

---

## 1. Oracle Cloud 가입 + 인스턴스 생성

1. [oracle.com/cloud/free](https://www.oracle.com/cloud/free/) 가입
   - **홈 리전**: South Korea Central (Seoul) 또는 South Korea North (Chuncheon).
     홈 리전은 나중에 못 바꾸니 신중히. A1 무료 인스턴스 자리는 춘천이 상대적으로 여유
2. (권장) 가입 후 **Pay As You Go 로 업그레이드** — 카드만 등록하면 되고 무료 한도
   내 사용은 계속 0원. 무료 전용 계정보다 A1 자리 잡기가 훨씬 쉬워지고,
   유휴 인스턴스 회수/계정 정지 위험도 크게 줄어든다
3. 인스턴스 생성: Compute → Instances → Create
   - Image: **Ubuntu 22.04 (aarch64)** / Shape: **VM.Standard.A1.Flex, 4 OCPU / 24GB**
     (Always Free 한도 전부 — 쪼개 쓸 필요 없음)
   - SSH 공개키 등록, 퍼블릭 IP 할당 확인
   - "Out of capacity" 가 뜨면: 시간대 바꿔 재시도(새벽 추천) / OCPU 를 2로 줄여 시도 /
     PAYG 업그레이드 후 시도

## 2. VCN 방화벽 (Security List) 열기

Networking → VCN → 해당 서브넷의 Security List → Ingress Rules 추가:

| Source | Protocol | Port |
|---|---|---|
| 0.0.0.0/0 | TCP | 80 |
| 0.0.0.0/0 | TCP | 443 |

(22 는 기본으로 열려 있음. 서버 내부 iptables 는 부트스트랩 스크립트가 처리)

## 3. 서버 부트스트랩

```bash
ssh ubuntu@<새서버IP>

# 스크립트 가져오기 (레포가 public 이므로 raw 로 바로 받는다)
curl -fsSLO https://raw.githubusercontent.com/userri/PinQ-backend/main/scripts/oracle-bootstrap.sh

# DB 자격증명은 기존 EC2 의 .env 와 동일한 값 사용 (덤프 복원 시 계정 일치)
export DB_NAME=pinq_db DB_USERNAME=<기존값> DB_PASSWORD=<기존값> MYSQL_ROOT_PASSWORD=<새로설정>
bash oracle-bootstrap.sh
```

## 4. 배포 파일 + .env 이전

로컬(이 레포 체크아웃)에서:

```bash
cd ~/SSAFY/PinQ-backend
scp docker-compose.yml deploy.sh ubuntu@<새서버IP>:~/pinq_backend/
scp nginx/entrypoint.sh nginx/nginx.conf nginx/nginx.conf.template \
    nginx/upstream-blue.conf nginx/upstream-green.conf ubuntu@<새서버IP>:~/pinq_backend/nginx/
scp init-letsencrypt.sh duckdns-update.sh ubuntu@<새서버IP>:~/pinq_backend/
```

`.env` 는 기존 EC2 것을 기반으로 새 서버 `~/pinq_backend/.env` 에 작성.
EC2 에 접근할 수 없으면 `.env.example` 를 복사해 전부 새로 채운다
(JWT_SECRET/ADMIN_SECRET 은 `openssl rand -hex 32` 로 재생성하면 된다 —
기존 로그인 세션만 무효화되고 다른 영향 없음).
**추가로 반드시 넣어야 할 항목** (이번 출시 준비에서 새로 생김):

```bash
GOOGLE_WEB_CLIENT_ID=<안드로이드 앱과 동일한 웹 클라이언트 ID>  # 없으면 구글 로그인 전부 거부
ANTHROPIC_API_KEY=<키>                                        # 퀴즈 정답 검증
DOMAIN=finq.duckdns.org                                       # 없으면 compose 기동 거부
DDL_AUTO=update    # ★ 신규 DB 첫 기동 1회만! 기동 확인 후 이 줄 삭제 (기본 validate)
```

첫 라이브 슬롯 상태 파일도 초기화:

```bash
ssh ubuntu@<새서버IP> "cp ~/pinq_backend/nginx/upstream-blue.conf ~/pinq_backend/nginx/upstream.conf"
```

## 5. 데이터 이전 (선택)

**EC2 가 중지 상태라면**: AWS 콘솔에서 잠깐 시작 → 덤프 → 다시 중지.
출시 전 테스트 데이터뿐이라면 건너뛰고 새 DB 로 시작해도 된다 (DDL_AUTO=update 가 스키마 생성).

```bash
# EC2 에서
docker exec mysql-container mysqldump -u<DB_USERNAME> -p<DB_PASSWORD> --databases <DB_NAME> > pinq-dump.sql
# 로컬 경유 복사 후 새 서버에서
docker exec -i mysql-container mysql -uroot -p<MYSQL_ROOT_PASSWORD> < pinq-dump.sql
```

## 6. DNS(DuckDNS) 전환 + 인증서

```bash
# 새 서버에서 — DuckDNS 가 새 서버 IP 를 가리키게 갱신
cd ~/pinq_backend
DUCKDNS_SUBDOMAIN=finq DUCKDNS_TOKEN=<토큰> ./duckdns-update.sh   # .env 에 있으면 자동 로드

# DNS 전파 확인 (새 IP 가 나올 때까지)
dig +short finq.duckdns.org

# Let's Encrypt 인증서 신규 발급 (DNS 가 새 IP 를 가리킨 뒤에!)
./init-letsencrypt.sh
```

기존 EC2 의 `certbot/conf` 를 통째로 scp 해오면 발급 없이 재사용도 가능
(도메인이 같으므로). EC2 접근이 안 되면 신규 발급이 깔끔하다.

## 7. 첫 배포 + 검증

```bash
cd ~/pinq_backend
./deploy.sh          # Docker Hub 의 latest (멀티아치) 배포

# 검증 체크리스트
curl -I https://finq.duckdns.org/actuator/health      # 200
curl -I http://finq.duckdns.org                        # 301 → https
curl -I https://finq.duckdns.org/privacy.html          # 200 (Play Console 제출용)
curl -I https://finq.duckdns.org/account-deletion.html # 200
docker logs pinq-app-blue 2>&1 | grep -i "started\|error" | head
```

✅ 정상 확인 후 **`.env` 에서 `DDL_AUTO=update` 줄 삭제** (다음 배포부터 validate).

## 8. GitHub Actions 배포 대상 전환

레포 Settings → Secrets and variables → Actions 에서 **값만 교체** (이름 유지):

| Secret | 새 값 |
|---|---|
| `EC2_HOST` | 새 오라클 서버 퍼블릭 IP |
| `EC2_USER` | `ubuntu` |
| `EC2_SSH_KEY` | 새 서버용 SSH 개인키 전문 |

이후 main push 때마다 오라클 서버로 자동 배포된다.

## 9. 마무리

- 앱(FinQ)에서 로그인 → 퀴즈 풀이 → 마이페이지까지 실제 플로우 확인
- 며칠 정상 운영 확인 후 AWS EC2 인스턴스/EBS/탄력 IP 완전 삭제 (과금 중단)
- (권장) MySQL 정기 백업: `mysqldump` cron 을 걸고 덤프를 서버 밖(로컬/오브젝트 스토리지)에 보관
  — Oracle 무료 계정은 드물게 예고 없이 정지된 사례가 있어 외부 백업이 보험이다
