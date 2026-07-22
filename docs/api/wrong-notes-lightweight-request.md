# [프론트 → 백엔드] 오답노트/이력/북마크 목록 경량화 요청 (목록 요약 + 단건 상세 분리)

## 배경 / 문제
현재 `GET /api/me/wrong-notes`, `/api/me/attempts`, `/api/bookmarks` 는 항목마다
**전체 데이터**(선택지 4개·해설·키워드·관련 기사)를 담아 리스트 전체를 한 번에 내려준다.
프론트는 페이징 없이 전량 로드 후 클라이언트에서 필터링한다.

- 목록 화면(접힌 카드)에서 실제로 쓰는 건 **질문 한 줄 + 카테고리 + 상태 뱃지**뿐이다.
- 무거운 필드(`choices`, `explanation`, `keyword`, `article`)는 **카드를 펼칠 때만** 필요하다.
- 오답이 수백~수천 개로 쌓이면 응답 크기·초기 로드가 선형으로 커진다.

## 요청: 목록은 요약만, 상세는 단건 조회
페이징을 도입하지 않고(필터·카운트를 클라 즉시 처리하는 현재 UX 유지) **응답 자체를 가볍게** 만든다.

### ① 목록 응답에서 무거운 필드 제거 (요약 DTO)
`GET /api/me/wrong-notes`, `/api/me/attempts`, `/api/bookmarks` 응답 항목을 아래로 축소:

```jsonc
{
  "quizId": 123,
  "category": "INTEREST_RATE",
  "categoryDisplayName": "금리",
  "question": "기준금리를 인상하면 일반적으로 나타나는 현상은?",
  "correct": false,          // 첫 시도 정답 여부
  "solved": true,            // ★ 신규: 풀었는지 여부 (미풀이 북마크 판별용)
  "bookmarked": true,
  "solvedAt": "2026-07-20T14:23:00",   // nullable
  "review": {                // 복습 상태 요약 — 기존과 동일, nullable
    "stage": 1, "waterCount": 1, "absorbedCount": 1,
    "graduated": false, "dueDate": "2026-07-23"
  }
}
```

**제거되는 필드**: `choices`, `selectedChoiceId`, `correctChoiceId`, `explanation`, `keyword`, `article`

**추가되는 필드**: `solved: Boolean`
- 현재 프론트는 "미풀이"를 `correctChoiceId == null`(치팅 방지 마스킹)로 판별하는데,
  요약에서 `correctChoiceId`가 사라지므로 **명시적 `solved` 플래그가 반드시 필요**하다.
- 미풀이 북마크(아직 안 푼 문제)는 `solved: false` → 카드에 "아직 안 푼 문제" 뱃지 + 탭 시 풀이 화면 이동.

### ② 단건 상세 조회 엔드포인트 신설 (펼칠 때 호출)
```
GET /api/me/attempts/{quizId}
```
응답 = **현재 AttemptItemResponse 전체 구조 그대로** (선택지·정답·해설·키워드·기사 포함):

```jsonc
{
  "quizId": 123,
  "choices": [ { "id": 1, "orderNum": 1, "content": "예금 금리가 오른다" }, ... ],
  "selectedChoiceId": 2,       // 내가 첫 시도에 고른 선지 (없으면 null)
  "correctChoiceId": 1,        // 미풀이면 마스킹(null) — 기존 규칙 유지
  "correct": false,
  "explanation": "기준금리가 오르면 시중 금리가 따라 올라 예금 금리도 상승합니다.",
  "keyword": "기준금리",
  "article": { "id": 9, "title": "...", "url": "...", "source": "...",
               "category": "...", "categoryDisplayName": "...", "publishedAt": "..." }  // nullable
}
```

- 필드 스펙·마스킹 규칙은 **지금 목록이 주던 값과 100% 동일**하게 유지(프론트가 같은 파서 재사용).
- 미풀이 문제는 상세를 열지 않고 곧장 풀이로 보내므로, 이 엔드포인트는 **푼 문제만** 호출된다(하지만 방어적으로 마스킹은 유지).

## 하위호환 / 배포 순서
- 프론트 DTO는 신규 필드를 **기본값 있는 nullable**로 받으므로, 백엔드가 먼저 배포돼도 구프론트가 깨지지 않는다.
- 다만 **목록에서 `choices`/`explanation` 등을 제거하는 건 파괴적 변경**이라, 배포는 반드시:
  1. 백엔드가 단건 상세 API(`GET /api/me/attempts/{quizId}`)를 **먼저** 추가·배포
  2. 프론트가 상세 지연 로드로 전환·배포
  3. 그 다음에 목록 응답에서 무거운 필드 제거
  순서로 진행. (2)까지는 목록이 무거운 필드를 계속 줘도 무방하다.
- 급하지 않으면 ①은 뒤로 미루고 ②만 먼저 넣어도 된다(프론트가 상세를 단건으로 가져오되 목록 필드는 무시).

## 예상 효과
- 목록 응답 크기: 항목당 대략 1/3 수준(해설·선지·기사 제거분).
- 필터·카운트("N문제")는 여전히 클라 메모리에서 즉답(0ms) — UX 그대로.
- 상세는 펼칠 때 1건만 왕복 → 체감 지연 거의 없음(캐시 가능).

## 프론트 후속 작업(백엔드 ② 배포 후 착수)
1. `AttemptSummaryApiResponse` 신규 DTO + 목록 API 시그니처 교체
2. `AttemptDetailApiResponse`(현 전체 구조) + `GET api/me/attempts/{quizId}`
3. `AttemptItemCard` — 펼칠 때 상세 지연 로드(카드별 로딩/에러 상태), 1회 로드 후 캐시
4. `unsolved` 판별을 `correctChoiceId == null` → 서버 `solved` 플래그로 전환
5. ShowcaseActivity 케이스 갱신
