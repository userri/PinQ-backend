#!/usr/bin/env python3
"""퀴즈 품질 지표 전후 비교 — 백업 덤프(db-YYYYMMDD.sql)에서 계산한다.

    tar xzf ~/backups/pinq/<날짜>/pinq-backup-<날짜>.tar.gz db-<날짜>.sql
    python3 scripts/quality-metrics.py db-<날짜>.sql

원격 접속·쓰기 없음. 덤프에서 quiz·choice·quiz_generation_attempt 세 테이블만 읽는다
(사용자 테이블은 열지 않는다). 결과 해석은 docs/data/quality-before-after.md.

집합:
  BASE   quiz_date 2026-04-26~06-09 — 2026-07-07 에 분석한 172건 모집단
  POST   quiz_date >= 2026-07-08   — 7/7 22:41 KST 배포(ec04552) 뒤 첫 06:00 생성분부터
  POST30 / POST7 — POST 의 마지막 30 / 7 발행일

지표:
  A. 최장 보기 = 정답. strict(유일 최장) / tie(동률 최장) / not. 원 분석의 "55%" 는 strict+tie.
  B. 렉시컬 중복 — QuizSimilarityChecker 의 판정 규칙 그대로
     (J>=0.5 & D>=0.6) | J>=0.75 | (D>=0.85 & 정규화 길이>=20).
     집합 안 전쌍을 비교해 "1쌍 이상에 속한 문항 비율". 같은 카테고리 내 비율도 낸다.
     ⚠️ 이 규칙은 저장 직전 필터이기도 하다 — POST 의 값은 필터 통과분을 필터로 잰 것이다.
     어휘가 다른 의미 중복은 잡히지 않는다. 원 분석의 "39~58%·금리 85.7%" 와는 기준이 다르다
     (같은 172건에 이 규칙을 걸면 42.4%·60.7% 가 나온다).
  C. 일별 발행 수, 목표(7/11 까지 4, 이후 5) 충족일.
  D. quiz_generation_attempt — 회차×단계, 정기만으로 5문항 채운 날, 백필 발행 있는 날.
"""
import collections
import datetime as dt
import itertools
import re
import sqlite3
import sys

KEEP = {"quiz", "choice", "quiz_generation_attempt"}


# ---------- 덤프 → 메모리 SQLite ----------
def parse_values(s):
    rows, i, n = [], 0, len(s)
    while i < n:
        while i < n and s[i] != "(":
            i += 1
        if i >= n:
            break
        i += 1
        row = []
        while True:
            while s[i] == " ":
                i += 1
            if s.startswith("NULL", i):
                row.append(None); i += 4
            elif s.startswith("_binary '", i):
                i += 9; buf = []
                while s[i] != "'":
                    if s[i] == "\\":
                        buf.append(s[i + 1]); i += 2
                    else:
                        buf.append(s[i]); i += 1
                row.append(0 if "".join(buf) in ("\0", "0", "") else 1); i += 1
            elif s[i] == "'":
                i += 1; buf = []
                while True:
                    c = s[i]
                    if c == "\\":
                        buf.append({"n": "\n", "r": "\r", "t": "\t", "0": "\0"}.get(s[i + 1], s[i + 1])); i += 2
                    elif c == "'":
                        i += 1; break
                    else:
                        buf.append(c); i += 1
                row.append("".join(buf))
            else:
                j = i
                while s[j] not in ",)":
                    j += 1
                tok = s[i:j]
                try:
                    row.append(int(tok))
                except ValueError:
                    row.append(tok)
                i = j
            while s[i] == " ":
                i += 1
            if s[i] == ",":
                i += 1; continue
            if s[i] == ")":
                i += 1; break
        rows.append(tuple(row))
    return rows


def load(path):
    text = open(path, encoding="utf-8").read()
    con = sqlite3.connect(":memory:")
    schemas = {}
    for m in re.finditer(r"CREATE TABLE `(\w+)` \((.*?)\n\) ENGINE", text, re.S):
        name = m.group(1)
        if name not in KEEP:
            continue
        cols = re.findall(r"^\s*`(\w+)`", m.group(2), re.M)
        schemas[name] = cols
        con.execute(f"CREATE TABLE {name} ({', '.join(cols)})")
    for m in re.finditer(r"^INSERT INTO `(\w+)` VALUES (.*?);$", text, re.M | re.S):
        name = m.group(1)
        if name not in KEEP:
            continue
        rows = parse_values(m.group(2))
        con.executemany(f"INSERT INTO {name} VALUES ({','.join('?' * len(schemas[name]))})", rows)
    return con


# ---------- B. QuizSimilarityChecker 이식 ----------
JOSA = ["에서", "으로", "이나", "부터", "까지", "처럼", "보다",
        "은", "는", "이", "가", "을", "를", "의", "에", "로", "와", "과", "도", "만", "나"]
CHUNK = re.compile(r"[가-힣a-zA-Z0-9]+")
NON = re.compile(r"[^가-힣a-zA-Z0-9]")


def strip_josa(t):
    for j in JOSA:
        if t.endswith(j) and len(t) - len(j) >= 2:
            return t[:-len(j)]
    return t


def tokenize(s):
    return {strip_josa(m) for m in CHUNK.findall(s.lower()) if len(strip_josa(m)) >= 2}


def normalize(s):
    return NON.sub("", s.lower())


def bigrams(n):
    return {n[i:i + 2] for i in range(len(n) - 1)}


def jac(a, b):
    if not a or not b:
        return 0.0
    i = len(a & b)
    return i / (len(a) + len(b) - i)


def dice(a, b):
    if not a or not b:
        return 0.0
    return 2 * len(a & b) / (len(a) + len(b))


def is_dup(j, d, ml):
    return (j >= 0.5 and d >= 0.6) or j >= 0.75 or (d >= 0.85 and ml >= 20)


def dup_stats(items):
    prep = {r[0]: (tokenize(r[3]), bigrams(normalize(r[3])), len(normalize(r[3])), r[2]) for r in items}
    flagged, same_cat, pairs = set(), set(), 0
    for a, b in itertools.combinations(items, 2):
        ta, ba, la, ca = prep[a[0]]
        tb, bb, lb, cb = prep[b[0]]
        if is_dup(jac(ta, tb), dice(ba, bb), min(la, lb)):
            pairs += 1
            flagged.update((a[0], b[0]))
            if ca == cb:
                same_cat.update((a[0], b[0]))
    percat = collections.defaultdict(lambda: [0, 0])
    for r in items:
        percat[r[2]][1] += 1
        if r[0] in same_cat:
            percat[r[2]][0] += 1
    return len(flagged), pairs, percat


# ---------- A. 최장 보기 ----------
def longest_stats(items, choices):
    c = collections.Counter()
    percat = collections.defaultdict(collections.Counter)
    for r in items:
        ch = choices[r[0]]
        if len(ch) != 4:
            c["bad"] += 1; continue
        lens = [(len(x[1]), x[2]) for x in ch]
        mx = max(l for l, _ in lens)
        ans = [l for l, a in lens if a][0]
        n_max = sum(1 for l, _ in lens if l == mx)
        k = "strict" if (ans == mx and n_max == 1) else "tie" if ans == mx else "not"
        c[k] += 1
        percat[r[2]][k] += 1
    return c, percat


def main(path):
    con = load(path)
    rows = con.execute("select id, quiz_date, category, question from quiz order by id").fetchall()
    choices = collections.defaultdict(list)
    for qid, content, is_ans, order_num in con.execute("select quiz_id, content, is_answer, order_num from choice"):
        choices[qid].append((order_num, content, is_ans))

    base = [r for r in rows if "2026-04-26" <= r[1] <= "2026-06-09"]
    post = [r for r in rows if r[1] >= "2026-07-08"]
    dates = sorted({r[1] for r in post})
    sets = {"BASE": base, "POST": post,
            "POST30": [r for r in post if r[1] in set(dates[-30:])],
            "POST7": [r for r in post if r[1] in set(dates[-7:])]}

    for name, items in sets.items():
        ds = sorted({r[1] for r in items})
        print(f"\n===== {name}: N={len(items)} {ds[0]}..{ds[-1]} ({len(ds)}일) id {items[0][0]}..{items[-1][0]}")
        print("  카테고리:", dict(collections.Counter(r[2] for r in items)))
        c, pc = longest_stats(items, choices)
        n = c["strict"] + c["tie"] + c["not"]
        print(f"  A. 최장=정답: 유일 {c['strict']} ({c['strict']/n:.1%}) · 동률 {c['tie']} ({c['tie']/n:.1%}) · 합 {(c['strict']+c['tie'])/n:.1%} · 아님 {c['not']}  [n={n}]")
        for cat, cc in sorted(pc.items()):
            t = sum(cc.values())
            print(f"     {cat:14s} 유일 {cc['strict']}/{t} = {cc['strict']/t:.1%}  합 {(cc['strict']+cc['tie'])/t:.1%}")
        f, pairs, pcd = dup_stats(items)
        print(f"  B. 렉시컬 중복(검사기 규칙, 카테고리 무관): {f}/{len(items)} = {f/len(items):.1%} · 쌍 {pairs}")
        for cat, (k, t) in sorted(pcd.items()):
            print(f"     {cat:14s} 같은 카테고리 내 {k}/{t} = {k/t:.1%}")

    print("\n===== C. 일별 발행 (POST)")
    daily = collections.Counter(r[1] for r in post)
    d0 = dt.date(2026, 7, 8)
    d1 = max(dt.date.fromisoformat(d) for d in daily)
    days = [(d0 + dt.timedelta(i)).isoformat() for i in range((d1 - d0).days + 1)]
    target = lambda d: 4 if d < "2026-07-12" else 5
    full = sum(1 for d in days if daily.get(d, 0) >= target(d))
    short = [(d, daily.get(d, 0), target(d)) for d in days if daily.get(d, 0) < target(d)]
    print(f"  일수 {len(days)} · 목표 충족 {full} ({full/len(days):.1%}) · 미달 {short}")
    print(f"  발행 {sum(daily.values())} · 일평균 {sum(daily.values())/len(days):.2f}")

    print("\n===== D. quiz_generation_attempt")
    print("  행:", con.execute("select count(*), min(occurred_on), max(occurred_on) from quiz_generation_attempt").fetchone())
    for r in con.execute("select run_window, stage, count(*) from quiz_generation_attempt group by 1,2 order by 1,2"):
        print("  ", r)
    reg = dict(con.execute("select occurred_on, count(*) from quiz_generation_attempt where stage='PUBLISHED' and run_window<>'BACKFILL' group by 1"))
    bf = dict(con.execute("select occurred_on, count(*) from quiz_generation_attempt where stage='PUBLISHED' and run_window='BACKFILL' group by 1"))
    alld = sorted(set(reg) | set(bf))
    print(f"  일수 {len(alld)} ({alld[0]}..{alld[-1]}) · 정기만으로 5문항 {sum(1 for d in alld if reg.get(d, 0) >= 5)} · 백필 발행 있음 {sum(1 for d in alld if bf.get(d, 0) > 0)}")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    main(sys.argv[1])
