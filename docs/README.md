# 문서 색인

이 레포의 문서는 **성격이 다섯 가지**뿐이다. 폴더가 아니라 이 표에서 찾는다.
프론트 레포에도 같은 색인이 있다 → [`../PinQ-frontend/docs/README.md`](../PinQ-frontend/docs/README.md)

> **다른 레포 파일을 가리키는 법 — 형제 상대경로로 쓴다.**
> `../PinQ-frontend/docs/...` (○) / `PinQ-frontend/docs/...` (✗)
> 레포 이름부터 쓰면 **현재 열려 있는 레포 기준**으로 해석돼 파일을 못 찾는다. 두 레포는 `SSAFY/` 아래 형제라 `../` 하나면 닿는다.
> `/Users/...` 절대경로는 커밋되는 문서에 쓰지 않는다 — 다른 머신에서 깨진다. 추적되지 않는 `CLAUDE.md` 에만 쓴다.

| 성격 | 뜻 | 어디 |
|---|---|---|
| 🟢 **살아있는 상태** | 계속 갱신됨. 매일 읽는다 | `PENDING.md`, `quality-audit-log.md` |
| 📏 **규칙 (SSOT)** | 지금 참인 것. 코드와 어긋나면 **코드를 고친다** | `rules/` |
| 🧭 **결정 기록** | 왜 이렇게 됐나 / 뭘 기각했나. 코드에 안 남는 정보 | `decisions/` |
| 🔧 **운영 절차** | 어떻게 하나 | `db-access-and-migration.md`, `oracle-migration.md` |
| 📦 **작업 산출물** | 일회성. 완료되면 참고용 | `superpowers/` |

---

## 🟢 살아있는 상태 — 먼저 읽는 것

| 문서 | 무엇 | 언제 읽나 |
|---|---|---|
| [PENDING.md](PENDING.md) | 세션·레포를 넘나드는 대기 작업의 **단일 진실**. "X 후 Y" 의존이 생기면 여기 한 줄 | **모든 판단의 출발점.** 새 세션 시작 시, 매일 검수 마지막 단계 |
| [quality-audit-log.md](quality-audit-log.md) | 일일 퀴즈 검수 이력, 검증 기준 실험의 채택/기각 근거 | 검수할 때. **전량 읽지 말 것** — 상단 기준 + 최근 2~3일만 |

## 📏 규칙 (SSOT)

코드가 이 문서를 따른다. 반대가 아니다.

| 문서 | 무엇 | SSOT 대상 |
|---|---|---|
| [rules/grass-and-streak.md](rules/grass-and-streak.md) | 잔디·스트릭·나무 성장 지표 3종의 확정 규칙 | `UserStatsService.grassLevel`, `UserService.calculateStreak` |
| [rules/concept-diagnosis.md](rules/concept-diagnosis.md) | 취약 개념 진단 규칙 | `UserStatsService.getConceptStats` → `GET /api/users/me/concept-stats` |

## 🧭 결정 기록

대부분 프론트→백엔드 요청 문서 형식이지만, 값어치는 **기각 근거**에 있다.
새 문서도 여기 넣는다 — 엔드포인트 스펙은 문서로 쓰지 않고 코드에서 생성한다.

| 문서 | 결정 | 상태 |
|---|---|---|
| [decisions/review-today-daily-aggregate.md](decisions/review-today-daily-aggregate.md) | `/api/reviews/today` 에 오늘 집계 노출. **`ReviewItem.lastReviewedCorrect` 컬럼 신설안 기각** — 이미 있는 `ReviewDailyLog` 재사용 | 완료 `0594927` |
| [decisions/bookmark-sorted-date-request.md](decisions/bookmark-sorted-date-request.md) | 북마크 목록에 `bookmarkedAt` 추가 — 정렬 축과 표시 축 일치 | 완료 |
| [decisions/review-tree-visibility-frontend.md](decisions/review-tree-visibility-frontend.md) | 복습 나무 가시화 전체 스펙. 메타포(오답에 물 주기 → 나무), 물 이력·졸업 연출·404 처리 | 완료 `75ee22d`~`8e0ccde` |
| [decisions/wrong-notes-lightweight-request.md](decisions/wrong-notes-lightweight-request.md) | 오답노트/이력/북마크 **목록은 요약만, 상세는 단건 조회**로 분리 | 완료 `e97e992` |
| [decisions/unsolved-bookmark-routing-request.md](decisions/unsolved-bookmark-routing-request.md) | 미풀이 북마크의 죽은 링크 해소 — 단건 풀이 라우팅 | 완료 (프론트 `ae35597`) |

## 🔧 운영 절차

| 문서 | 무엇 | 주의 |
|---|---|---|
| [db-access-and-migration.md](db-access-and-migration.md) | 운영 MySQL 접속 · 스키마 마이그레이션 표준 절차 | **비밀번호를 명령줄에 직접 쓰지 않는다** — `.env` 를 셸에 로드해 변수로만 참조 |
| [oracle-migration.md](oracle-migration.md) | AWS EC2 → Oracle Cloud Always Free 이전 가이드 | 앱 코드 변경 없음. 가입 차단 상태로 **보류 중** |

## 📦 작업 산출물 — 완료, 참고용

배포가 끝난 문서다. 현행 동작이 궁금하면 위의 규칙·결정 기록을 보고,
**"그때 왜 그렇게 정했나"가 궁금할 때만** 연다.

| 문서 | 무엇 | 상태 |
|---|---|---|
| [superpowers/specs/2026-07-21-review-tree-visibility-design.md](superpowers/specs/2026-07-21-review-tree-visibility-design.md) | 복습 나무 브레인스토밍 결정사항 (설계 인계용) | ✅ 완료 |
| [superpowers/plans/2026-07-21-review-tree-visibility.md](superpowers/plans/2026-07-21-review-tree-visibility.md) | 위 설계의 구현 계획서 (729줄) | ✅ 완료·배포됨 |

---

## 레포 밖 · 폴더 밖

| 위치 | 무엇 |
|---|---|
| [../README.md](../README.md) | 프로젝트 소개 — 매일 아침 AI가 경제 뉴스에서 5문제 자동 출제 |
| `.claude/skills/quiz-audit/SKILL.md` | 일일 퀴즈 검수 절차. 고정 조회 명령(SSH + 인라인 SQL) 포함 |
| `../PinQ-frontend/docs/` | 프론트 문서. 같은 색인 있음 |
