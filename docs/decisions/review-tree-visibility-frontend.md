# 복습 나무 가시화 — 프론트 연동 스펙

> 2026-07-21 배포. 백엔드 커밋 `75ee22d`~`8e0ccde`.
> 메타포: **오답에 물 주기 → 나무 키우기**. 복습을 맞힐수록 물을 흡수하고, 마지막 단계까지 통과하면 "나무"로 졸업한다.

## 개념 모델

| 용어 | 의미 |
|---|---|
| **stage** | 복습 단계 `0→1→2`. 간격 `{3, 7, 14}`일. stage 2에서 맞히면 졸업. |
| **waterCount** | 물 준 총 횟수 = 이 문제를 복습 채점한 총 시도 수 (정답·오답 무관). |
| **absorbedCount** | 흡수된 물 = 그중 **맞힌** 횟수. `waterCount ≥ absorbedCount`. |
| **graduated / graduatedAt** | 졸업(나무 완성) 여부·시각. 졸업한 문제는 복습 큐에 다시 안 나온다 (영구 성취). |
| **graduatedTrees** | 사용자가 키운 나무 **총계** (users 카운터). |

표현 예시: `"물 7번 줬고, 그중 3번 흡수했어요"` = `waterCount:7, absorbedCount:3`.

---

## 1. GET `/api/reviews/today` — 오늘의 복습 세트

기존 엔드포인트. `reviews[]` 항목에 **`waterCount`, `absorbedCount` 필드가 추가**됐다. (그 외 변화 없음)

```jsonc
{
  "reviews": [
    {
      "quizId": 101,
      "category": "STOCK",
      "categoryDisplayName": "주식",
      "question": "...",
      "choices": [ { "id": 1, "orderNum": 1, "content": "..." }, ... ],
      "stage": 1,
      "dueDate": "2026-07-21",
      "waterCount": 2,      // ← 신규
      "absorbedCount": 1    // ← 신규
    }
  ],
  "nextDueDate": "2026-07-24"  // 예정 복습 없으면 null
}
```

> 풀이 화면 진입 시 "지금까지 물 N번" 뱃지를 그릴 수 있다. 정답/해설은 채점 후에만 내려온다(기존과 동일).

---

## 2. POST `/api/reviews/{quizId}/answer` — 복습 채점

요청 바디는 기존과 동일: `{ "choiceId": <Long> }`.

**응답이 확장됐다.** 일반 문제 채점 화면과 동일하게 **`article`(기사 링크)** 이 포함되고, 물 이력·졸업 연출용 필드가 붙는다.

```jsonc
{
  "quizId": 101,
  "correct": true,
  "correctChoiceId": 2,
  "explanation": "...",
  "keyword": "...",
  "article": {                    // ← 신규. 일반 채점 후 화면과 동일 구조
    "id": 55,
    "title": "...",
    "url": "https://...",
    "source": "한국경제",
    "category": "STOCK",
    "categoryDisplayName": "주식",
    "publishedAt": "2026-07-20T09:00:00"
  },
  "graduated": false,             // true면 이번 채점으로 나무 완성
  "nextDueDate": "2026-07-28",    // 졸업이면 null
  "stage": 2,                     // ← 신규. 채점 반영 후 단계
  "waterCount": 3,                // ← 신규. 이번 시도 포함
  "absorbedCount": 2,             // ← 신규
  "totalGraduatedTrees": null     // ← 신규. 졸업 시에만 숫자, 비졸업이면 null
}
```

### 졸업 연출 (graduated: true)

```jsonc
{
  "graduated": true,
  "nextDueDate": null,
  "stage": 2,
  "waterCount": 7,
  "absorbedCount": 4,
  "totalGraduatedTrees": 5   // "5번째 나무 완성!" 연출에 사용
}
```

**연출 재료:** `"물 7번 준 나무가 완성됐어요 — 당신의 5번째 나무"` = `waterCount:7`, `totalGraduatedTrees:5`.

> ⚠️ 이미 졸업한 문제를 다시 채점 요청하면 **404**가 내려온다 (졸업 나무는 재복습 불가). 정상 흐름에선 today 목록에 안 나오므로 발생하지 않지만, 캐시된 화면에서 오래된 요청이 갈 경우 404를 "이미 졸업한 문제"로 처리할 것.

---

## 3. GET `/api/reviews/garden` — 정원 (신규)

복습 나무 현황 대시보드. 자라는 항목과 완성된 나무를 나눠서 반환한다.

```jsonc
{
  "growing": [                    // 자라는 중 — due 오름차순 (급한 것 먼저)
    {
      "quizId": 101,
      "category": "STOCK",
      "categoryDisplayName": "주식",
      "question": "...",
      "keyword": "...",
      "stage": 1,
      "dueDate": "2026-07-24",
      "waterCount": 2,
      "absorbedCount": 1,
      "graduatedAt": null
    }
  ],
  "graduated": [                  // 완성된 나무 — 졸업 최신순
    {
      "quizId": 88,
      "category": "ECONOMY",
      "categoryDisplayName": "경제",
      "question": "...",
      "keyword": "...",
      "stage": 2,
      "dueDate": "2026-07-10",    // 졸업 항목은 의미 없음 (무시)
      "waterCount": 5,
      "absorbedCount": 4,
      "graduatedAt": "2026-07-19T14:32:00"
    }
  ],
  "graduatedTrees": 12            // 나무 총계 (카운터)
}
```

### ⚠️ 중요: `graduated` 목록 길이 ≠ `graduatedTrees`

이 기능 **배포(2026-07-21) 이전에 졸업한 나무**는 당시 정책상 row가 삭제됐다 → `graduated` 목록에는 안 나오지만 `graduatedTrees` 총계에는 포함된다.

- **"총 몇 그루" 숫자**는 항상 `graduatedTrees`를 신뢰할 것.
- `graduated` 목록은 "복원 가능한 나무들"이며, 배포 이후 졸업분부터 누적된다.
- UI 예: `"🌳 12그루 (그중 3그루 상세 보기 가능)"` 처럼 총계와 목록 길이를 분리 표현.

> 정원은 페이징 없이 전체를 반환한다. 항목이 매우 많아지면(수백 건) 추후 페이징 추가 예정.

---

## 4. GET `/api/me/attempts`, `/api/me/wrong-notes` — 오답노트/풀이이력

`AttemptItemResponse[]` 각 항목에 **nullable `review` 객체가 추가**됐다.

```jsonc
[
  {
    "quizId": 101,
    "category": "STOCK",
    "categoryDisplayName": "주식",
    "question": "...",
    "choices": [ ... ],
    "selectedChoiceId": 3,
    "correctChoiceId": 2,
    "correct": false,
    "explanation": "...",
    "keyword": "...",
    "article": { ... },
    "bookmarked": true,
    "solvedAt": "2026-07-15T10:00:00",
    "review": {                   // ← 신규. 복습 큐에 없는 문제면 null
      "stage": 1,
      "waterCount": 2,
      "absorbedCount": 1,
      "graduated": false,
      "dueDate": "2026-07-24"
    }
  }
]
```

- `review == null` → 이 문제는 한 번도 복습 큐에 오른 적 없음 (물 준 적 없음).
- `review.graduated == true` → 다 키운 나무. 오답노트에서 `🌳` 뱃지 표현 가능.
- 오답노트(`wrong-notes`)는 첫 시도 실패 문제만 → 대부분 `review`가 채워져 있다.

---

## 필드 요약 (신규/변경분만)

| 엔드포인트 | 변경 |
|---|---|
| `GET /api/reviews/today` | `reviews[].waterCount`, `reviews[].absorbedCount` 추가 |
| `POST /api/reviews/{quizId}/answer` | `article`, `stage`, `waterCount`, `absorbedCount`, `totalGraduatedTrees` 추가 / 졸업분 재요청 시 404 |
| `GET /api/reviews/garden` | **신규** 엔드포인트 |
| `GET /api/me/attempts`, `/api/me/wrong-notes` | `review` 객체(nullable) 추가 |

모든 변경은 **필드 추가**뿐 — 기존 필드 제거·이름 변경 없음 (하위 호환).
