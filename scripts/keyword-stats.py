#!/usr/bin/env python3
"""검색 키워드별 성적표 — 어떤 키워드가 쓸모없는 기사를 물어오는지 잰다.

    for d in 2026-08-17 2026-08-18; do ~/bin/pinq-quiz-fetch.sh attempts $d; done \
        | python3 scripts/keyword-stats.py

원시 시도 행(`attempts <날짜>`)을 여러 날치 이어서 stdin 으로 받는다. 롤업이 아니라 원시여야
한다 — `searchKeyword` 는 롤업 축에 없다.

이 스크립트가 있는 이유: 키워드를 감으로 바꾸면 되돌릴 근거가 안 남는다. 2026-08-18 에
EXCHANGE_RATE 키워드를 개편하면서, 새로 넣은 개념 키워드(환헤지·외환보유액·기축통화)가
기존 것보다 나쁘면 되돌린다는 조건을 걸었고 그 판정을 여기서 한다.

읽는 법:
  - `SKIP율` 이 핵심이다. 높으면 그 키워드가 부르는 기사가 출제 부적합이라는 뜻 —
    프롬프트가 아니라 **기사 풀**의 문제다.
  - 비교 기준선: 2026-08-17~18 실측으로 STOCK "코스피" 36%, INFLATION "물가 상승" 50%,
    EXCHANGE_RATE 는 최저가 65% 였다.
  - ⚠️ 시도 수가 적은 키워드는 순위를 믿지 말 것. 앞 키워드에서 발행에 성공하면 뒤 키워드는
    아예 돌지 않으므로, 뒤쪽 키워드는 '마른 날에만 도는' 편향된 표본이다.
"""

import collections
import json
import sys


def main():
    rows = []
    for line in sys.stdin.read().splitlines():
        line = line.strip()
        if line.startswith("["):
            rows += json.loads(line)
    if not rows:
        print("입력 없음 — `attempts <날짜>`(원시) 출력을 넣었는지 확인할 것.", file=sys.stderr)
        return 1

    stat = collections.defaultdict(collections.Counter)
    for r in rows:
        stat[(r["category"], r["searchKeyword"])][r["reason"] or "PUBLISHED"] += 1

    days = sorted({r["occurredOn"] for r in rows})
    print(f"# 키워드별 성적표 ({days[0]} ~ {days[-1]}, {len(days)}일)\n")
    for cat in sorted({c for c, _ in stat}):
        print(f"## {cat}")
        print("| 키워드 | 시도 | SKIP | SKIP율 | 룰반려 | 검증실패 | 발행 |")
        print("|---|---:|---:|---:|---:|---:|---:|")
        entries = [(k, c) for (cc, k), c in stat.items() if cc == cat]
        for kw, c in sorted(entries, key=lambda e: -sum(e[1].values())):
            tot = sum(c.values())
            skip = c["LLM_SKIP"]
            print(f"| {kw} | {tot} | {skip} | {skip * 100 // tot}% "
                  f"| {c['RULE_REJECTED']} | {c['VERIFY_FAILED']} | {c['PUBLISHED']} |")
        print()
    return 0


if __name__ == "__main__":
    sys.exit(main())
