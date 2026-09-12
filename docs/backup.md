# 서버 백업 회수

Azure for Students 계정이 졸업으로 막힐 수 있어 2026-08-24 에 도입했다.
**레포에 없는 것만** 백업한다 — 코드·문서·CI 설정은 GitHub 에 이미 있다.

## 실행

```bash
./scripts/backup-fetch.sh
```

접속정보는 `~/.pinq-ops.env` 의 `PINQ_SSH_KEY`·`PINQ_SSH_HOST` 에서만 읽는다.
이 레포는 public 이므로 IP·키 경로를 스크립트에 쓰지 않는다.

산출물: `~/backups/pinq/<YYYYMMDD>/pinq-backup-<YYYYMMDD>.tar.gz` (0600, **레포 밖**)

| 들어가는 것 | 왜 |
|---|---|
| `db-*.sql` | `pinq_db` 전체. **유일본이고 재생성 불가** — quiz·review_item·generation_attempt·token_usage |
| `env` | OPENAI/NAVER/ANTHROPIC 키, DB 비번, `ADMIN_SECRET`, `JWT_SECRET`, `DUCKDNS_TOKEN`, `FIREBASE_SERVICE_ACCOUNT_BASE64` |
| `letsencrypt.tar.gz` | `privkey`·`fullchain`. 재발급 가능하나 있으면 이전이 빠르다 |
| `docker-compose.yml`, `crontab.txt`, `containers.txt` | 서버 상태 재현용 |

nginx 설정(`nginx/*`)과 `duckdns-update.sh` 는 레포에 있으므로 백업하지 않는다.

## 주기 — 매일 13·18·22시에 시도, 하루 한 번 성공하면 끝

2026-08-24 부터 `launchd` 로 매일 돈다. 퀴즈가 매일 발행되고 시도 기록이 쌓이므로
안 돌린 날은 그대로 손실이다(2026-06-10~07-06 구간처럼 한 번 비면 영구 공백).

**13시 한 번만으로는 20일 중 7일 성공했다**(2026-08-24~09-12). 로그에 남은 원인 둘:
- `Network is unreachable` — 잠든 사이 놓친 실행을 launchd 가 깨어나자마자 돌리는데,
  그 시점엔 Wi-Fi 가 아직 안 붙어 있다. 재시도 3회가 60초 안에 소진됐다.
- `Operation timed out` — 사내망이 아웃바운드 22 를 막는다(2026-08-11 실측).
  회사에서 13시를 맞으면 그날은 원천적으로 불가능하다.

그래서 2026-09-13 부터: 시작 전 **22번 포트가 열릴 때까지 최대 3분 대기**, 하루 **세 슬롯**
(13·18·22시), 이미 성공한 날은 건너뜀. 알림은 마지막 슬롯(22시 이후) 실패에만 띄운다 —
사내망에서 13·18시 실패는 정상이라 매번 알리면 무시하게 된다.

**날짜 폴더는 검증까지 통과한 뒤에만 만든다.** 종전에는 시작 시점에 만들어서 실패한
날도 빈 폴더가 남았고, 그게 "백업된 날"로 세어졌다.

**cron 이 아니라 launchd 인 이유**: cron 은 맥이 잠들어 있던 시각의 실행을 그냥
건너뛴다. 노트북은 닫혀 있는 시간이 많아 조용히 빠지는 날이 생긴다. launchd 는
놓친 실행을 깨어날 때 한 번 돌려준다.

```bash
# 설치 (레포 루트에서)
sed -e "s|__REPO__|$PWD|g" -e "s|__HOME__|$HOME|g" scripts/com.pinq.backup.plist \
  > ~/Library/LaunchAgents/com.pinq.backup.plist
launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/com.pinq.backup.plist
```

| | |
|---|---|
| 즉시 1회 | `launchctl kickstart -k gui/$(id -u)/com.pinq.backup` |
| 상태·종료코드 | `launchctl print gui/$(id -u)/com.pinq.backup \| grep -E "last exit\|runs"` |
| 중지 | `launchctl bootout gui/$(id -u)/com.pinq.backup` |
| 로그 | `~/backups/pinq/backup.log` (누적, 실행마다 `### 시각` 헤더) |
| 잘 되는지 | `ls ~/backups/pinq` — 날짜 폴더가 있으면 그날은 성공한 것 |

보관은 최근 30일 전부 + 그 이전은 매월 1일자만. 하루 약 540KB(DB 가 커지고 있다).

### 실패는 재시도하고, 그래도 안 되면 알린다

노트북에서 도는 작업이라 회선이 흔들리거나 맥이 조는 일이 실제로 있다 —
2026-08-24 첫 자동 실행이 회수 도중 `Operation timed out` 으로 끊겼다. 서버 tar 는
만들어졌는데 로컬에 안 왔고, **아무도 그걸 몰랐다.**

그래서 각 원격 단계는 3회 재시도(20s·40s 간격)하고, 최종 실패 시 macOS 알림을
띄운다. `ServerAliveInterval` 로 죽은 연결을 빨리 포기하게 해 재시도가 의미를 갖게 했다.

**서버 사본은 회수에 성공한 뒤에만 지운다.** 순서를 바꾸면 실패한 날 원본까지 잃는다.

### SSH 키를 `~/Downloads` 에 두면 안 된다

macOS 가 `~/Downloads`·`~/Documents` 등을 TCC 로 보호해서, **launchd 로 뜬 프로세스는
그 안의 키 파일을 못 읽는다**(`Load key ...: Operation not permitted`). 터미널에서는
터미널 앱에 권한이 있어 되므로 수동 실행만으로는 안 드러난다. 키는 `~/.ssh/` 에 둔다.

### 자원 사용

맥은 하루 한 번 `scp` 310KB + tar 검증이라 체감 없음. 서버 쪽도 덤프가 1.26MB 이고
`--single-transaction` 이라 테이블 잠금이 없다 — 1GB VM 이지만 몇 초로 끝난다.

## 설계상 조심할 것 — 다섯 번 걸렸다

1. **`ssh 'bash -s' < script` 를 쓰지 않는다.** 스크립트가 stdin 으로 들어가는데
   본문의 `docker exec -i` 가 그 stdin 을 먹어 나머지가 통째로 잘린다.
   **에러 없이 조용히 끝나서** 처음엔 원인이 안 보였다. → `scp` 로 올려서 실행한다.
2. **날짜는 로컬이 정해 인자로 넘긴다.** 서버는 UTC 라 KST 자정 근처에서 하루 어긋나
   회수가 빗나간다.
3. **인증서는 호스트 `/etc/letsencrypt` 에 없다.** `$APP_DIR/certbot/conf` 에 root 소유로
   있다. `sudo` 는 비대화 실행에서 비번을 물어 막히므로, 이미 root 로 도는
   `pinq-certbot` 컨테이너에게 tar 를 시켜 stdout 으로 받는다.

4. **`head -n -N` 은 macOS 에서 안 된다**(BSD head). `sort -r | tail -n +N` 을 쓴다.
5. **`grep` 을 `set -euo pipefail` 파이프에 두지 않는다.** 지울 게 없는 날 exit 1 을
   내서 스크립트가 죽는다 — 백업은 이미 성공한 뒤인데 실패로 보고된다.

덤프 자체도 두 플래그가 필수다 — `--default-character-set=utf8mb4`(없으면 한글이 `?` 로
손실), `--set-gtid-purged=OFF`(없으면 복원 대상에서 GTID 충돌). 둘 다 실제로 터진 적이 있다.

스크립트는 덤프에 `Dump completed` 트레일러가 없으면 죽는다. **빈 백업이 성공으로
보이는 것**이 이런 작업의 대표적 실패라서다.

## 복원

```bash
tar xzf pinq-backup-<날짜>.tar.gz -C <작업폴더>
docker exec -i mysql-container mysql -u<user> -p<pw> --default-character-set=utf8mb4 < db-<날짜>.sql
```

`--databases` 로 떠서 `CREATE DATABASE` 가 덤프에 들어 있다 — DB 를 미리 만들 필요 없다.
