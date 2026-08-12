#!/usr/bin/env python3
"""생성 로그 → 하루치 집계 한 줄 (docs/data/scrape-stats.jsonl 에 append).

쓰는 법 (검수 회차에서):
    ~/bin/pinq-quiz-fetch.sh logs 30 | python scripts/scrape-stats.py 2026-08-12

왜 스크립트인가: 로그 원문은 하루 100KB 를 넘고 사유 분류에 판단이 들어간다.
그때그때 손으로 파싱하면 (ㄱ) 매번 비용이 들고 (ㄴ) 분류 기준이 회차마다 흔들려
날짜 간 비교가 깨진다. 기준을 여기 한 곳에 고정해 둔다.

⚠️ 로그는 메모리 링버퍼다 — 서버가 재시작하면 그 이전 기록이 사라진다.
따라서 **당일 검수에서 돌려야** 행이 남는다. 놓친 날은 영구 결손이고,
빈칸을 0 으로 채우지 않는다(0 과 "못 봤다"는 다르다).
"""
import json
import sys
from collections import Counter, defaultdict

# 폐기 사유 분류 — 판정 순서가 곧 우선순위다.
# 한 기사에 여러 줄(생성 SKIP → 검증 실패)이 붙을 수 있어, '기사 건너뜀' 라인이
# 나올 때까지 모은 줄 전체를 한 덩어리로 보고 분류한다. 순서를 바꾸면 과거 행과
# 비교가 깨지므로 바꿀 때는 전체 재집계가 필요하다.
def classify(chunk: str) -> str:
    if "사설" in chunk and "SKIP 판정" not in chunk:
        return "editorial"          # 사설·칼럼 룰베이스 필터
    if "중복" in chunk or "유사" in chunk:
        return "duplicate"          # 소재·문항 중복 (= 개념 포화 축)
    if "카테고리" in chunk:
        return "off_category"       # 기사가 그 슬롯과 무관 (= 기사 풀 오염)
    if "검증 실패" in chunk or "리젝" in chunk:
        return "verify_failed"      # 품질 사유 (복수 정답·근거 부족 등)
    return "other"


def run_window(hhmm: str) -> str:
    """회차 구분 — 정기 생성은 06:04경, 가드 백필은 매시 10분."""
    return "regular" if hhmm < "06:10" else "backfill"


def main() -> int:
    if len(sys.argv) < 2:
        print("usage: scrape-stats.py YYYY-MM-DD < logs.json", file=sys.stderr)
        return 2
    date = sys.argv[1]
    logs = json.load(sys.stdin)

    tries = defaultdict(list)                       # category → [title, ...]
    reasons = defaultdict(Counter)                  # category → 사유 카운터
    runs = defaultdict(Counter)                     # category → 회차 카운터
    published = Counter()                           # 회차 → 생성 성공 수
    chunk: list[str] = []

    for e in logs:
        msg = e.get("message", "")
        if "Notification" in e.get("logger", ""):
            continue
        hhmm = e.get("at", "")[11:16]

        # 성공 수는 회차 요약 라인에서 읽는다.
        # 문항별 "퀴즈 생성 성공" INFO 는 링버퍼에 담기지 않는다(2026-08-12 확인) —
        # 그걸 세면 항상 0 이 나온다.
        if "완료. 성공=" in msg:
            got = int(msg.split("성공=")[1].split("/")[0])
            published["regular" if "퀴즈 생성 완료" in msg else "backfill"] += got

        if ("기사 건너뜀" in msg or "사설" in msg) and "category=" in msg:
            cat = msg.split("category=")[1].split(",")[0]
            title = msg.split("title=")[1] if "title=" in msg else "?"
            tries[cat].append(title)
            reasons[cat][classify(" ".join(chunk + [msg]))] += 1
            runs[cat][run_window(hhmm)] += 1
            chunk = []
        else:
            chunk.append(msg)

    row = {
        "date": date,
        "published": {"regular": published["regular"], "backfill": published["backfill"]},
        "categories": {
            cat: {
                "attempts": len(titles),                 # 시도 횟수
                "articles": len(set(titles)),            # 서로 다른 기사 수
                "reasons": dict(reasons[cat]),
                "runs": dict(runs[cat]),
            }
            for cat, titles in sorted(tries.items())
        },
    }
    print(json.dumps(row, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
