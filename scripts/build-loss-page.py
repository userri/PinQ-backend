#!/usr/bin/env python3
"""손실 집계 두 파일 → 자체완결 HTML 한 장.

    python3 scripts/build-loss-page.py -o /tmp/loss.html

왜 숫자를 파일에서 읽지 않고 박아 넣는가: 브라우저는 로컬 HTML 이 옆 파일을 읽는 것을 막는다.
박아 넣으면 결과물이 파일 하나라 더블클릭으로 열리고, 아티팩트로 그대로 올릴 수도 있다.

⚠️ 막대까지 **여기서 다 그려서** 내보낸다 — 페이지에 스크립트가 없다. 브라우저에서 그리게 했더니
스크립트를 안 돌리는 뷰어에서 제목만 나오고 그래프가 통째로 비었다(2026-08-17 실측).
빈 그래프는 "손실이 없었다"로 읽히므로 그 실패 방식은 허용하지 않는다.

두 구간은 세는 기준이 다르다 — 한 계열로 잇지 않고 경계에 선을 긋는다.
겹치는 날(8/17)은 테이블 행을 쓰고 링버퍼 행을 버린다. 여기서 막으므로 손으로 지울 필요 없다.
"""

import argparse
import json
import pathlib

ROOT = pathlib.Path(__file__).resolve().parent.parent
LEGACY = ROOT / "docs/data/scrape-stats.jsonl"
TABLE = ROOT / "docs/data/attempt-stats.jsonl"

CATEGORY_LABEL = {
    "EXCHANGE_RATE": "환율",
    "INFLATION": "물가",
    "INTEREST_RATE": "금리",
    "REAL_ESTATE": "부동산",
    "STOCK": "주식",
}

# 옛 기준 — 로그 문구를 5개 버킷으로 뭉갠 근사
LEGACY_REASON = {
    "off_category": "카테고리 이탈",
    "duplicate": "중복 기사",
    "verify_failed": "검증 반려",
    "editorial": "사설",
    "other": "기타",
}

# 새 기준 — 서버가 남긴 사유 코드 그대로
TABLE_REASON = {
    "LLM_SKIP": "생성 모델이 건너뜀",
    "RULE_REJECTED": "룰 반려",
    "VERIFY_FAILED": "검증 반려",
    "CROSS_CATEGORY_USED": "다른 카테고리가 이미 씀",
    "TERM_REUSE_GUARD": "용어 재사용 가드",
    "TERM_EQUALS_CATEGORY": "용어가 카테고리와 같음",
    "EDITORIAL": "사설",
    "DUPLICATE": "중복 기사",
    "UNKNOWN": "미분류",
}

# 단계 이름과 뜻. 근거: src/.../audit/domain/AttemptStage.java 의 주석.
STAGE_LABEL = {
    "PREFILTER": "기사 거르기",
    "GENERATE": "문제 만들기",
    "VALIDATE": "규칙 검사",
    "VERIFY": "다른 모델이 풀어보기",
    "PUBLISHED": "발행",
}
STAGE_NOTE = {
    "PREFILTER": "문제를 만들어보기도 전에 기사 단계에서 뺀 것. 사설이거나, 다른 카테고리가 이미 쓴 기사.",
    "GENERATE": "만들어보라고 시켰는데 모델이 “이 기사로는 못 만들겠다”고 한 것. 응답이 깨졌거나 API 가 실패한 경우도 포함.",
    "VALIDATE": "만들어진 문제를 저장 전에 정해둔 규칙으로 검사해 반려한 것. 최근에 쓴 용어를 또 썼거나, 형식이 어긋난 경우.",
    "VERIFY": "완성된 문제를 다른 회사 모델에게 풀려본 것. 답이 안 맞거나 정답이 둘 이상이면 반려.",
    "PUBLISHED": "다 통과해서 실제로 나간 문제. 탈락이 아니다.",
}
STAGE_ORDER = ["PREFILTER", "GENERATE", "VALIDATE", "VERIFY", "PUBLISHED"]


def read_rows(path):
    if not path.exists():
        return []
    with path.open(encoding="utf-8") as f:
        return [json.loads(line) for line in f if line.strip()]


def normalize(row, source):
    cats = []
    for name, c in row["categories"].items():
        attempts = c["attempts"]
        cats.append({
            "name": CATEGORY_LABEL.get(name, name),
            "attempts": attempts,
            "reasons": [
                {"label": (TABLE_REASON if source == "table" else LEGACY_REASON).get(k, k), "n": v}
                for k, v in sorted(c["reasons"].items(), key=lambda kv: -kv[1])
            ],
            "stages": [
                {"label": STAGE_LABEL.get(s, s), "n": c.get("stages", {}).get(s, 0)}
                for s in STAGE_ORDER if c.get("stages", {}).get(s)
            ],
            "regular": c["runs"].get("regular", 0),
            "backfill": c["runs"].get("backfill", 0),
        })
    cats.sort(key=lambda c: -c["attempts"])

    published = row.get("published", {})
    return {
        "date": row["date"],
        "source": source,
        "attempts": sum(c["attempts"] for c in cats),
        "regular": sum(c["regular"] for c in cats),
        "backfill": sum(c["backfill"] for c in cats),
        "published": published.get("regular", 0) + published.get("backfill", 0),
        "publishedBackfill": published.get("backfill", 0),
        "categories": cats,
    }


def build_days():
    table = {r["date"]: normalize(r, "table") for r in read_rows(TABLE)}
    days = {}
    for r in read_rows(LEGACY):
        # 겹치는 날은 테이블이 이긴다 — 대조로 테이블이 상위집합임이 확정됐다.
        if r["date"] not in table:
            days[r["date"]] = normalize(r, "legacy")
    days.update(table)
    return [days[d] for d in sorted(days)]


PAGE = """<title>퀴즈 생성 손실</title>
<style>
:root {
  --ground: #f2f4f7; --panel: #ffffff; --ink: #14203a; --ink-soft: #55627d;
  --hair: #d8dee9; --grid: #e7ecf3;
  --regular: #2f5d8c; --backfill: #7fa8cd; --published: #1f7a5a;
  --legacy-ink: #6b7386; --legacy-fill: #b9c0cf;
}
@media (prefers-color-scheme: dark) {
  :root:not([data-theme="light"]) {
    --ground: #10151f; --panel: #182031; --ink: #e6ecf7; --ink-soft: #97a3bb;
    --hair: #2a3546; --grid: #232d3f;
    --regular: #6fa6dd; --backfill: #3d6a95; --published: #4cbf95;
    --legacy-ink: #8a94a8; --legacy-fill: #414d63;
  }
}
:root[data-theme="dark"] {
  --ground: #10151f; --panel: #182031; --ink: #e6ecf7; --ink-soft: #97a3bb;
  --hair: #2a3546; --grid: #232d3f;
  --regular: #6fa6dd; --backfill: #3d6a95; --published: #4cbf95;
  --legacy-ink: #8a94a8; --legacy-fill: #414d63;
}
* { box-sizing: border-box; }
body {
  margin: 0; background: var(--ground); color: var(--ink);
  font-family: -apple-system, BlinkMacSystemFont, "Apple SD Gothic Neo", "Pretendard",
    "Malgun Gothic", system-ui, sans-serif;
  font-size: 15px; line-height: 1.6;
}
.wrap { max-width: 940px; margin: 0 auto; padding: 40px 20px 72px; display: flex; flex-direction: column; gap: 34px; }
h1 { font-size: 27px; font-weight: 700; letter-spacing: -0.02em; margin: 0; text-wrap: balance; }
h2 { font-size: 13px; font-weight: 700; letter-spacing: 0.1em; color: var(--ink-soft); margin: 0 0 14px; }
.sub { color: var(--ink-soft); font-size: 14px; margin: 6px 0 0; }
.tiles { display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap: 12px; }
.tile { background: var(--panel); border: 1px solid var(--hair); border-radius: 10px; padding: 15px 17px; }
.tile .k { font-size: 12px; color: var(--ink-soft); letter-spacing: 0.04em; }
.tile .v { font-size: 30px; font-weight: 700; font-variant-numeric: tabular-nums; letter-spacing: -0.03em; margin-top: 3px; }
.tile .n { font-size: 12px; color: var(--ink-soft); font-variant-numeric: tabular-nums; }
.panel { background: var(--panel); border: 1px solid var(--hair); border-radius: 12px; padding: 22px 24px; }
.days { display: flex; flex-direction: column; gap: 11px; }
.day { display: grid; grid-template-columns: 92px 1fr 74px; align-items: center; gap: 14px; }
.day .d { font-size: 13px; font-variant-numeric: tabular-nums; color: var(--ink-soft); }
.day.legacy .d { color: var(--legacy-ink); }
.bar { height: 22px; background: var(--grid); border-radius: 4px; overflow: hidden; display: flex; }
.bar span { display: block; height: 100%; }
.s-regular { background: var(--regular); }
.s-backfill { background: var(--backfill); }
.legacy .s-regular { background: var(--legacy-fill); }
.legacy .s-backfill { background: var(--legacy-fill); opacity: 0.55; }
.day .n { font-size: 13px; font-variant-numeric: tabular-nums; text-align: right; }
.boundary { display: flex; align-items: center; gap: 12px; color: var(--ink-soft); font-size: 12px; margin: 4px 0; }
.boundary::before, .boundary::after { content: ""; height: 1px; background: var(--hair); flex: 1; }
.key { display: flex; flex-wrap: wrap; gap: 16px; margin-top: 18px; font-size: 12px; color: var(--ink-soft); }
.key i { width: 11px; height: 11px; border-radius: 3px; display: inline-block; vertical-align: -1px; margin-right: 6px; }
.tablewrap { overflow-x: auto; }
table { border-collapse: collapse; width: 100%; font-size: 14px; }
th, td { text-align: left; padding: 9px 12px 9px 0; border-bottom: 1px solid var(--hair); white-space: nowrap; }
th { font-size: 12px; font-weight: 600; color: var(--ink-soft); letter-spacing: 0.04em; }
td.num, th.num { text-align: right; font-variant-numeric: tabular-nums; }
.reasons { display: flex; flex-direction: column; gap: 9px; }
.reason { display: grid; grid-template-columns: 150px 1fr 42px; align-items: center; gap: 12px; font-size: 13px; }
.reason .rb { height: 9px; background: var(--regular); border-radius: 3px; min-width: 2px; }
.legacyblock .reason .rb { background: var(--legacy-fill); }
.reason .rn { text-align: right; font-variant-numeric: tabular-nums; color: var(--ink-soft); }
.note { font-size: 13px; color: var(--ink-soft); border-left: 2px solid var(--hair); padding-left: 14px; }
.cols { display: grid; grid-template-columns: repeat(auto-fit, minmax(280px, 1fr)); gap: 26px; }
.stages { list-style: none; counter-reset: st; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 10px; }
.stages li { counter-increment: st; display: grid; grid-template-columns: 22px 168px 1fr; gap: 12px; align-items: baseline; font-size: 13px; }
.stages li::before {
  content: counter(st); font-variant-numeric: tabular-nums; font-size: 11px; font-weight: 700;
  color: var(--ink-soft); border: 1px solid var(--hair); border-radius: 50%;
  width: 20px; height: 20px; line-height: 18px; text-align: center; align-self: center;
}
.stages li b { font-weight: 600; }
.stages li span { color: var(--ink-soft); }
@media (max-width: 560px) { .stages li { grid-template-columns: 22px 1fr; } .stages li span { grid-column: 2; } }
</style>
<div class="wrap">
  <header>
    <h1>퀴즈 생성 손실</h1>
    <p class="sub">하루에 기사 몇 건을 시도해서 몇 개가 발행됐는지, 나머지는 어디서 걸렀는지.</p>
  </header>
  <div class="tiles">__TILES__</div>
  <section class="panel">
    <h2>날짜별 시도</h2>
    <div class="days">__DAYS__</div>
    <div class="key">
      <span><i style="background:var(--regular)"></i>정기 회차</span>
      <span><i style="background:var(--backfill)"></i>백필 회차</span>
      <span><i style="background:var(--legacy-fill)"></i>옛 기준 (회차 구분 정확도 낮음)</span>
    </div>
  </section>
  <section class="panel">
    <h2>__LATEST__ — 카테고리별</h2>
    <div class="tablewrap"><table>__CATS__</table></div>
__LEGEND__
  </section>
  <section class="panel">
    <h2>거른 사유</h2>
    <div class="cols">__REASONS__</div>
    <p class="note" style="margin-top:22px">
      두 구간은 세는 기준이 다르다. 옛 기준은 로그 문구를 다섯 덩어리로 뭉갠 근사라
      용어 가드 반려처럼 <b>아예 못 세던 사유</b>가 있었고, 카테고리도 일부 잘못 붙었다.
      새 기준은 서버가 기록한 사유를 그대로 쓴다. 그래서 두 구간의 막대는 이어 보지 않는다.
    </p>
  </section>
</div>
"""


def esc(s):
    return (str(s).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace('"', "&quot;"))


def render_tiles(latest):
    lost = latest["attempts"] - latest["published"]
    rate = 100 * latest["published"] / latest["attempts"] if latest["attempts"] else 0
    tiles = [
        ("최근 집계일", latest["date"], "DB 기록" if latest["source"] == "table" else "옛 로그 기록"),
        ("발행", f"{latest['published']}개",
         f"백필 {latest['publishedBackfill']}개 포함" if latest["publishedBackfill"] else "전부 정기 회차"),
        ("시도", f"{latest['attempts']}건", f"백필 {latest['backfill']}건"),
        ("발행까지 간 비율", f"{rate:.1f}%", f"나머지 {lost}건은 걸러짐"),
    ]
    return "".join(
        f'<div class="tile"><div class="k">{esc(k)}</div><div class="v">{esc(v)}</div>'
        f'<div class="n">{esc(n)}</div></div>'
        for k, v, n in tiles
    )


def render_days(days):
    top = max(d["attempts"] for d in days) or 1
    out = []
    drew_boundary = False
    for d in days:
        if not drew_boundary and d["source"] == "table":
            out.append('<div class="boundary">여기부터 DB 기록 — 세는 기준이 바뀐다</div>')
            drew_boundary = True
        segs = ""
        for cls, label, n in (("s-regular", "정기", d["regular"]), ("s-backfill", "백필", d["backfill"])):
            if n:
                segs += (f'<span class="{cls}" style="width:{100 * n / top:.2f}%" '
                         f'title="{label} {n}건"></span>')
        cls = " legacy" if d["source"] == "legacy" else ""
        out.append(
            f'<div class="day{cls}"><div class="d">{esc(d["date"][5:])}</div>'
            f'<div class="bar">{segs}</div><div class="n">{d["attempts"]}건</div></div>'
        )
    return "".join(out)


def render_cats(latest):
    has_stages = any(c["stages"] for c in latest["categories"])
    rows = ['<tr><th>카테고리</th><th class="num">시도</th><th class="num">백필</th>'
            '<th>어디까지 갔나</th></tr>']
    for c in latest["categories"]:
        stages = " · ".join(f'{s["label"]} {s["n"]}' for s in c["stages"]) if has_stages else "—"
        rows.append(
            f'<tr><td>{esc(c["name"])}</td><td class="num">{c["attempts"]}</td>'
            f'<td class="num">{c["backfill"] or "—"}</td><td>{esc(stages)}</td></tr>'
        )
    return "".join(rows)


def render_stage_legend():
    """단계 이름 각주. 기사 한 건이 아래 순서대로 관문을 지나고, 어디서 걸렸는지가 표의 값이다."""
    items = "".join(
        f'<li><b>{esc(STAGE_LABEL[s])}</b><span>{esc(STAGE_NOTE[s])}</span></li>'
        for s in STAGE_ORDER
    )
    return (
        '<p class="note" style="margin:20px 0 14px">기사 한 건은 아래 관문을 순서대로 지난다. '
        '표의 “어디까지 갔나”는 <b>그 관문에서 몇 건이 떨어졌는지</b>다.</p>'
        f'<ol class="stages">{items}</ol>'
    )


def render_reasons(days, latest):
    legacy = next((d for d in days if d["source"] == "legacy"), None)
    blocks = []
    for title, src, cls in (
        (f"새 기준 — {latest['date']}", latest, ""),
        (f"옛 기준 — {legacy['date']}" if legacy else "", legacy, "legacyblock"),
    ):
        if not src:
            continue
        agg = {}
        for c in src["categories"]:
            for r in c["reasons"]:
                agg[r["label"]] = agg.get(r["label"], 0) + r["n"]
        items = sorted(agg.items(), key=lambda kv: -kv[1])
        if not items:
            continue
        top = items[0][1]
        bars = "".join(
            f'<div class="reason"><div>{esc(label)}</div>'
            f'<div class="rb" style="width:{100 * n / top:.2f}%"></div>'
            f'<div class="rn">{n}</div></div>'
            for label, n in items
        )
        blocks.append(f'<div class="{cls}"><h2>{esc(title)}</h2><div class="reasons">{bars}</div></div>')
    return "".join(blocks)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("-o", "--out", default=str(ROOT / "docs/data/loss-stats.html"),
                    help="기본값: docs/data/loss-stats.html (레포에 커밋되는 자리 — 열면 바로 보인다)")
    args = ap.parse_args()

    days = build_days()
    if not days:
        raise SystemExit("오류: 집계 파일이 비어 있다.")
    latest = days[-1]
    html = (PAGE
            .replace("__TILES__", render_tiles(latest))
            .replace("__DAYS__", render_days(days))
            .replace("__LATEST__", esc(latest["date"]))
            .replace("__CATS__", render_cats(latest))
            .replace("__LEGEND__", render_stage_legend())
            .replace("__REASONS__", render_reasons(days, latest)))
    for mark in ("__TILES__", "__DAYS__", "__LATEST__", "__CATS__", "__LEGEND__", "__REASONS__"):
        if mark in html:
            raise SystemExit(f"오류: {mark} 가 치환되지 않았다 — 빈 그래프가 나갈 뻔했다.")
    pathlib.Path(args.out).write_text(html, encoding="utf-8")
    print(f"{args.out} — {len(days)}일 ({days[0]['date']} ~ {days[-1]['date']})")


if __name__ == "__main__":
    main()
