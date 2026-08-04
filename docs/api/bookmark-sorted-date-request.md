# [프론트→백엔드] 북마크 목록에 `bookmarkedAt` 추가 요청

작성 2026-08-04. 프론트 확인 완료, **백엔드 작업만 남음.**

## 증상

북마크 탭의 날짜가 `5/28 → 5/21 → 7/16 → 7/24 → 7/10` 처럼 무작위 순서로 보인다.

## 원인 — 버그가 아니라 정렬 축과 표시 축이 다름

| | 값 |
|---|---|
| 정렬 | `UserBookmarkRepository.findByUserIdOrderByCreatedAtDesc` = **북마크를 누른 시각** |
| 표시 | `AttemptSummaryResponse.solvedAt` = **문제를 푼 날** |

5/28에 푼 문제를 어제 담고, 7/16에 푼 문제를 지난주에 담았으면 5/28이 위로 온다.
정렬은 논리적으로 옳은데 화면에 찍히는 값이 다른 축이라 순서가 깨져 보인다.

## 요청

`AttemptSummaryResponse` 에 **`bookmarkedAt`(LocalDateTime, nullable)** 추가.

```java
public record AttemptSummaryResponse(
    Long quizId, String category, String categoryDisplayName, String question,
    String keyword, boolean correct, boolean solved, boolean bookmarked,
    LocalDateTime solvedAt,
    LocalDateTime bookmarkedAt,   // ← 추가
    AttemptItemResponse.ReviewStatus review
) { ... }
```

- **북마크 목록(`GET /api/me/bookmarks`)에서만 채우면 된다.** `BookmarkService` 가 이미
  `UserBookmark` 를 들고 있으므로 `bm.getCreatedAt()` 을 그대로 넘기면 끝이다.
- 오답노트·전체이력 등 다른 목록에서는 **null** 로 두면 된다 — 그 화면들의 정렬 축은
  `solvedAt` 이라 이미 일치한다.
- 추가 필드라 하위 호환. 프론트는 Moshi 기본값 `null` 로 받아 안전하다.

## 프론트가 할 일 (백엔드 반영 후)

북마크 탭의 행 날짜만 `bookmarkedAt ?: solvedAt` 으로 바꾼다. 정렬과 표시가 같은 축이 된다.

## 검토했으나 기각한 대안

- **정렬을 `solvedAt` 기준으로 변경**: 표시와는 맞지만 "방금 담은 게 목록 위에 안 뜬다"가
  된다. 북마크의 축은 "내가 담은 순서"이므로 정렬을 바꾸는 게 아니라 표시를 맞춰야 한다.
- **프론트에서 날짜를 아예 제거**: 불일치는 사라지지만 "언제 푼 문제인지"를 잃는다.
  백엔드 한 줄로 풀리는 걸 정보 삭제로 덮는 셈이다.
