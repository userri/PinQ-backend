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

## 주기

- **DB 는 매일이 맞다.** 퀴즈가 매일 발행되고 시도 기록이 쌓인다.
  (2026-06-10~07-06 구간처럼 한 번 비면 영구 공백이다.)
- 나머지는 거의 안 바뀐다 — 바꿀 때만.
- 2026-08-24 시점에는 **수동 1회만** 돌리기로 했다. 자동화는 미도입.

## 설계상 조심할 것 — 세 번 걸렸다

1. **`ssh 'bash -s' < script` 를 쓰지 않는다.** 스크립트가 stdin 으로 들어가는데
   본문의 `docker exec -i` 가 그 stdin 을 먹어 나머지가 통째로 잘린다.
   **에러 없이 조용히 끝나서** 처음엔 원인이 안 보였다. → `scp` 로 올려서 실행한다.
2. **날짜는 로컬이 정해 인자로 넘긴다.** 서버는 UTC 라 KST 자정 근처에서 하루 어긋나
   회수가 빗나간다.
3. **인증서는 호스트 `/etc/letsencrypt` 에 없다.** `$APP_DIR/certbot/conf` 에 root 소유로
   있다. `sudo` 는 비대화 실행에서 비번을 물어 막히므로, 이미 root 로 도는
   `pinq-certbot` 컨테이너에게 tar 를 시켜 stdout 으로 받는다.

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
