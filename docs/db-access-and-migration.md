# 운영 DB 접속 · 스키마 마이그레이션 절차

prod MySQL 에 붙어 스키마를 바꾸는 표준 절차. **비밀번호를 명령줄에 직접 쓰지 않는다** —
`.env` 를 셸에 로드해 변수로만 참조한다.

**접속 정보(호스트·키 경로)도 이 문서에 적지 않는다.** 이 레포는 공개다. 아래 명령은
로컬 전용 `~/.pinq-ops.env` 의 `PINQ_SSH_HOST`·`PINQ_SSH_KEY` 를 참조한다 — 먼저 셸에 로드할 것:

```bash
set -a && . ~/.pinq-ops.env && set +a
```

## 사실 확인 (2026-08-04, `docker-compose.yml` 기준)

| 항목 | 값 | 근거 |
|---|---|---|
| 컨테이너 이름 | `mysql-container` | `container_name` |
| 계정 | `root` | `MYSQL_ROOT_PASSWORD: ${DB_PASSWORD}` — root 비밀번호가 곧 `DB_PASSWORD` |
| DB 이름 | `${DB_NAME}` (기본 `pinq_db`) | `MYSQL_DATABASE` |
| 타임존 | **`Asia/Seoul`** | `TZ: Asia/Seoul` — UTC 아니다 |
| 포트 | `127.0.0.1:3306` (루프백 한정) | 외부 노출 없음. GUI 는 SSH 터널로 |
| prod DDL | `ddl-auto=validate` | `application-prod.properties` |

---

## 1. 접속

```bash
ssh -i "$PINQ_SSH_KEY" "$PINQ_SSH_HOST"
```

```bash
cd ~/pinq_backend && set -a && . ./.env && set +a
docker exec -it mysql-container \
  mysql --default-character-set=utf8mb4 -uroot -p"$DB_PASSWORD" "$DB_NAME"
```

앱 디렉터리는 **`~/pinq_backend`** 다(`~/pinq` 아님 — 2026-08-04 실측). `.env` 가 여기 있다.

## 2. 마이그레이션 실행 — 파일로 넣는다

SQL 을 셸에 다시 타이핑하지 않는다. **레포에 커밋된 파일을 파이프로 넣으면** 오타가 없고
"무엇을 언제 돌렸나"가 파일로 남는다.

```bash
# 로컬에서 한 번에
scp -i "$PINQ_SSH_KEY" \
  scripts/migration/<파일>.sql "$PINQ_SSH_HOST":/tmp/m.sql

ssh -i "$PINQ_SSH_KEY" "$PINQ_SSH_HOST" \
  'cd ~/pinq_backend && set -a && . ./.env && set +a && \
   docker exec -i mysql-container mysql -uroot -p"$DB_PASSWORD" "$DB_NAME" < /tmp/m.sql && \
   rm /tmp/m.sql'
```

## 3. 확인

```sql
SHOW COLUMNS FROM review_item LIKE 'last_reviewed_on';
```

---

## ⚠️ 순서 — ALTER 먼저, 배포 나중

prod 가 `ddl-auto=validate` 라 **컬럼이 없는 채로 새 이미지가 뜨면 기동 자체가 실패한다.**
반대로 컬럼만 먼저 추가하는 건 구버전 앱에 아무 영향이 없다(읽지도 쓰지도 않음).

```
① ALTER TABLE 실행  →  ② 확인  →  ③ 새 이미지 배포(blue/green 전환)
```

## ⚠️ 타임존 — 확인하고 손대라

컨테이너는 `TZ: Asia/Seoul` 이라 **이미 KST 일 가능성이 높다.** 확인 없이
`SET time_zone = '+09:00'` 을 걸면 오히려 **-9h 과보정**이 난다(과거 사고 원인).

시각 비교 SQL 을 쓰기 전에 반드시:

```sql
SELECT @@global.time_zone, @@session.time_zone, NOW(), UTC_TIMESTAMP();
```

`NOW()` 가 한국 시각이면 아무것도 건드리지 않는다. `ALTER TABLE` 같은 DDL 은 시각과
무관하므로 이 확인이 필요 없다.

## GUI(IntelliJ 등)로 붙을 때

포트가 루프백 한정이라 직접 접속은 안 된다. SSH 터널을 쓴다.

```bash
ssh -i "$PINQ_SSH_KEY" -L 3307:127.0.0.1:3306 "$PINQ_SSH_HOST" -N
```

이후 `localhost:3307` 로 접속. 계정은 `root`, 비밀번호는 서버 `.env` 의 `DB_PASSWORD`.

## 규칙

- **비밀번호를 명령줄·파일·로그에 남기지 않는다.** 항상 `.env` 로드 후 변수 참조.
- 스키마 변경은 **`scripts/migration/YYYY-MM-DD-<설명>.sql` 로 커밋하고
  `scripts/prepare-server.sh` 에 존재 가드와 함께 등록한다.** 파일 상단에
  "왜 필요한가 / 배포 순서 / NULL 허용 이유"를 주석으로 남긴다.
  ⚠️ **`docs/migration/` 은 CI 가 보지 않는다** — 거기 두면 배포 전 마이그레이션이 돌지 않고,
  `ddl-auto=validate` 라 새 앱이 기동에 실패해 **배포가 통째로 깨진다**(2026-08-14 token_usage 사고).
  아래 수기 실행 절차는 CI 밖에서 급히 손봐야 할 때의 폴백이다.
- 되돌릴 수 없는 변경(DROP COLUMN, DROP TABLE)은 실행 전 백업을 확인한다.
