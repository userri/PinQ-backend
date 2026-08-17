#!/usr/bin/env python3
"""생성 시도 롤업(JSON) → 손실 집계 한 줄(JSONL).

입력은 `pinq-quiz-fetch.sh attempts <일수|YYYY-MM-DD>` 의 롤업 응답을 그대로 stdin 으로 받는다.
출력은 날짜별 한 줄. 인자로 날짜를 주면 그 날짜만, 안 주면 응답에 있는 모든 날짜를 낸다.

    ~/bin/pinq-quiz-fetch.sh attempts 3 | python scripts/attempt-stats.py 2026-08-18

⚠️ 이 스크립트는 `scrape-stats.py`(로그 링버퍼 파서)의 후계다. 두 산출물은 **이어 붙이되 섞지
않는다** — 사유 분류 기준이 다르고(여기는 서버가 남긴 reason 코드 그대로, 저기는 로그 문구를
5개 버킷으로 뭉갠 근사), 회차 구분 정확도도 다르다. 경계는 `docs/data/` 의 두 파일로 그어져 있다.

행 스키마:
  date        집계 날짜
  source      "table" (링버퍼 파서 산출물과 구분하는 표지)
  published   {regular, backfill} — stage=PUBLISHED 행을 회차로 가른 수
  categories  카테고리별
    attempts  그 카테고리의 모든 시도 행 합 (PUBLISHED 포함)
    articles  서로 다른 기사 수 (URL 기준). 시도 ÷ 이 값이 재시도 배수다
    losses    발행에 이르지 못한 시도 (= attempts - published)
    reasons   서버 reason 코드별 수 (PUBLISHED 행은 reason 이 null 이라 제외)
    stages    탈락 단계별 수 (PUBLISHED 포함)
    runs      {regular, backfill}

⚠️ `articles` 는 옛 파일의 같은 이름 필드와 **세는 법이 다르다**. 옛 파서는 로그의 제목 문자열을
키로 썼는데 그 뒤에 `stage=`·`reason=` 이 붙어 같은 기사가 단계마다 다른 기사로 세어졌다(그래서
배수가 낮게 나왔다). 여기 값은 서버가 URL 로 센 것이다. 두 구간의 배수를 직접 비교하지 말 것.
"""

import json
import sys
from collections import defaultdict


def _run_key(run_window):
    return "backfill" if run_window == "BACKFILL" else "regular"


def build_rows(rollup):
    by_date = defaultdict(lambda: {
        "published": defaultdict(int),
        "categories": defaultdict(lambda: {
            "attempts": 0,
            "articles": 0,
            "losses": 0,
            "reasons": defaultdict(int),
            "stages": defaultdict(int),
            "runs": defaultdict(int),
        }),
    })

    for r in rollup:
        day = by_date[r["day"]]
        cat = day["categories"][r["category"]]
        n = r["attempts"]
        run = _run_key(r.get("runWindow"))

        cat["attempts"] += n
        # 같은 날·같은 카테고리의 모든 행에 같은 값이 실린다 — 더하지 말고 덮는다.
        cat["articles"] = r.get("distinctArticles", 0)
        cat["stages"][r["stage"]] += n
        cat["runs"][run] += n

        if r["stage"] == "PUBLISHED":
            day["published"][run] += n
        else:
            cat["losses"] += n
            # 발행 외 행은 reason 이 항상 있지만, 스키마가 열려 있으므로 방어한다.
            cat["reasons"][r["reason"] or "UNKNOWN"] += n

    rows = []
    for date in sorted(by_date):
        day = by_date[date]
        rows.append({
            "date": date,
            "source": "table",
            "published": dict(day["published"]),
            "categories": {
                name: {
                    "attempts": c["attempts"],
                    "articles": c["articles"],
                    "losses": c["losses"],
                    "reasons": dict(c["reasons"]),
                    "stages": dict(c["stages"]),
                    "runs": dict(c["runs"]),
                }
                for name, c in sorted(day["categories"].items())
            },
        })
    return rows


def main():
    want_date = sys.argv[1] if len(sys.argv) > 1 else None

    rollup = json.load(sys.stdin)
    if not isinstance(rollup, list):
        sys.exit("오류: 롤업 배열이 아니다. `attempts raw` 가 아니라 `attempts <일수|날짜>` 를 넘길 것.")
    if rollup and "day" not in rollup[0]:
        sys.exit("오류: 원시 행으로 보인다(`day` 필드 없음). 롤업 응답을 넘길 것.")

    rows = build_rows(rollup)
    if want_date is not None:
        rows = [r for r in rows if r["date"] == want_date]
        if not rows:
            sys.exit(f"오류: {want_date} 행이 롤업에 없다. 0 으로 채우지 말고 조회 범위를 넓혀 확인할 것.")

    for row in rows:
        print(json.dumps(row, ensure_ascii=False))


if __name__ == "__main__":
    main()
