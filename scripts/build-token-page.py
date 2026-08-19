#!/usr/bin/env python3
"""토큰 사용량 롤업(JSON) → GitHub 에서 바로 읽히는 마크다운 한 장.

    ~/bin/pinq-quiz-fetch.sh tokens 30 | python scripts/build-token-page.py -o docs/data/token-usage.md

입력은 `GET /api/admin/audit/token-usage` 응답(날짜 x kind 롤업)을 그대로 stdin 으로 받는다.

⚠️ 손실 집계(`build-loss-page.py`)와 달리 **중간 jsonl 을 두지 않는다.** 저쪽은 숫자가 서버
메모리(링버퍼)에만 있어 재배포마다 사라졌으므로 파일이 곧 원본이었다. 토큰은 `token_usage`
테이블에 남으므로 **원본은 DB 이고 이 파일은 언제든 다시 뽑는 사본**이다. 사본을 원본처럼
누적시키면 DB 와 어긋났을 때 어느 쪽이 맞는지 판단할 근거가 없어진다.

⚠️ 마크다운으로 내는 이유: GitHub 은 레포 안의 `.html` 을 렌더링하지 않고 소스로 보여준다
(`docs/data/loss-stats.html` 이 그 상태다). 표와 mermaid 는 그대로 그려준다.

⚠️ **절감 지표로 promptTokens 합을 쓰지 않는다.** 캐시가 먹으면 prompt 가 당연히 줄어서
"아꼈다"로 오독된다. 캐시 성과는 `cacheHits/calls` 로만 본다 — AuditController 주석과 같은 판단.
"""

import argparse
import collections
import json
import sys

# 테이블 이전 구간. `token_usage` 는 2026-08-17 배포부터 차므로 그 앞은 DB 에 없다.
# 8/16 만 검수 로그에 하루 합계가 수기로 남아 있어 추이의 첫 비교점으로 살린다.
# ⚠️ 이 행은 링버퍼 출처라 DB 행과 세는 법이 같은지 보장되지 않는다 — 표에서 따로 표시한다.
LEGACY = [
    {"day": "2026-08-16", "kind": "generate", "calls": 53, "promptTokens": 321946,
     "completionTokens": 11935, "cacheWriteTokens": 0, "cacheReadTokens": 0,
     "totalTokens": 333881, "cacheHits": None},
    {"day": "2026-08-16", "kind": "verify", "calls": 10, "promptTokens": 37214,
     "completionTokens": 1026, "cacheWriteTokens": 5596, "cacheReadTokens": 22384,
     "totalTokens": 66220, "cacheHits": None},
]

CACHE_UNIT = 2798  # verify 프롬프트 1회분 캐시 크기. write 수 = 그날 회차 수로 읽는다.


def num(n):
    return f"{n:,}"


def usage_table(rows):
    out = ["| 날짜 | kind | 호출 | prompt | completion | cache_write | cache_read | total |",
           "|---|---|---:|---:|---:|---:|---:|---:|"]
    for r in rows:
        mark = " ⚠️" if r.get("cacheHits") is None else ""
        out.append(
            f"| {r['day']}{mark} | {r['kind']} | {num(r['calls'])} | {num(r['promptTokens'])} "
            f"| {num(r['completionTokens'])} | {num(r['cacheWriteTokens'])} "
            f"| {num(r['cacheReadTokens'])} | {num(r['totalTokens'])} |")
    return "\n".join(out)


def cache_table(rows):
    """캐시 성과는 여기서만 본다. generate 는 캐시 대상이 아니라 제외한다(설계대로 전부 0)."""
    out = ["| 날짜 | verify 호출 | 캐시 적중 | 적중률 | cache_write | 회차 추정 |",
           "|---|---:|---:|---:|---:|---:|"]
    for r in rows:
        if r["kind"] != "verify":
            continue
        if r.get("cacheHits") is None:
            out.append(f"| {r['day']} ⚠️ | {num(r['calls'])} | — | — "
                       f"| {num(r['cacheWriteTokens'])} | {r['cacheWriteTokens'] // CACHE_UNIT} |")
            continue
        rate = r["cacheHits"] / r["calls"] * 100 if r["calls"] else 0
        out.append(f"| {r['day']} | {num(r['calls'])} | {num(r['cacheHits'])} | {rate:.0f}% "
                   f"| {num(r['cacheWriteTokens'])} | {r['cacheWriteTokens'] // CACHE_UNIT} |")
    return "\n".join(out)


def chart(title, days, values, ylabel):
    """mermaid xychart. GitHub 이 못 그리면 코드블록이 그대로 보일 뿐 값은 읽힌다 —
    빈 그래프가 '0' 으로 오독되는 실패(2026-08-17 손실 페이지)와 달리 안전한 퇴화다."""
    if not days:
        return ""
    labels = ", ".join(f'"{d[5:]}"' for d in days)
    top = max(values) if values else 1
    return (f"```mermaid\nxychart-beta\n    title \"{title}\"\n"
            f"    x-axis [{labels}]\n"
            f"    y-axis \"{ylabel}\" 0 --> {int(top * 1.15) + 1}\n"
            f"    bar [{', '.join(str(v) for v in values)}]\n```")


def main():
    # stdout/stderr 을 utf-8 로 고정한다. Windows 기본(cp949)에서는 한글 진행 문구가
    # UnicodeEncodeError 로 죽는다 — 2026-08-19 회차에서 파일은 정상인데 마지막 줄에서
    # 죽어 실패로 보였다. `scrape-stats.py` 와 같은 처리다.
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser()
    ap.add_argument("-o", "--out", required=True)
    args = ap.parse_args()

    rows = LEGACY + json.load(sys.stdin)
    rows.sort(key=lambda r: (r["day"], r["kind"]))
    days = sorted({r["day"] for r in rows})

    by = collections.defaultdict(dict)
    for r in rows:
        by[r["day"]][r["kind"]] = r

    gen_calls = [by[d].get("generate", {}).get("calls", 0) for d in days]
    # 적중률 차트는 값이 있는 날만 그린다. 없는 날을 0 으로 채우면 '적중 0' 으로 읽힌다 —
    # 빈 그래프를 '손실 없음' 으로 오독한 2026-08-17 과 같은 유형이다.
    hit_days, hit_rate = [], []
    for d in days:
        v = by[d].get("verify")
        if not v or v.get("cacheHits") is None or not v["calls"]:
            continue
        hit_days.append(d)
        hit_rate.append(round(v["cacheHits"] / v["calls"] * 100))

    body = f"""# 토큰 사용량

`GET /api/admin/audit/token-usage` 롤업을 그대로 옮긴 것이다. **원본은 `token_usage` 테이블**이고
이 파일은 사본이라 언제든 아래 명령으로 다시 만든다. 손으로 고치지 말 것.

```bash
~/bin/pinq-quiz-fetch.sh tokens 30 | python scripts/build-token-page.py -o docs/data/token-usage.md
```

## 날짜 x kind

{usage_table(rows)}

⚠️ 표시한 행은 **테이블 이전 구간**이다. `token_usage` 는 2026-08-17 배포부터 차므로 8/16 은
DB 에 없고 검수 로그의 수기 합계를 옮긴 것이다. 세는 법이 DB 행과 같은지는 보장되지 않는다.

## 캐시 성과

**적중률로 본다. `prompt` 합의 감소를 절감으로 읽지 않는다** — 캐시가 먹으면 당연히 줄어드는
값이라 그렇게 읽으면 캐시가 잘 들을수록 "비용이 늘었다"와 "줄었다"를 구분할 수 없다.

`generate` 는 캐시 대상이 아니라 제외했다(전부 `cache_write=0 cache_read=0`, 설계대로).
`회차 추정` 은 `cache_write / {CACHE_UNIT}` — 캐시 TTL 5분이라 회차마다 첫 건이 새로 쓴다.
정기·백필 회차 수와 맞는지 보는 자리다.

{cache_table(rows)}

## 추이

{chart("generate 호출 수", days, gen_calls, "calls")}

⚠️ 호출 수는 **낭비의 독립 지표가 아니다.** 그날 생성 시도 수와 함께 움직인다 —
손실 집계([loss-stats.html](loss-stats.html))의 시도 수와 나란히 볼 것.

{chart("verify 캐시 적중률 (%)", hit_days, hit_rate, "percent")}
"""

    with open(args.out, "w", encoding="utf-8", newline="\n") as f:
        f.write(body)
    print(f"{args.out} — {len(days)}일 / {len(rows)}행", file=sys.stderr)


if __name__ == "__main__":
    main()
