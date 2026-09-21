#!/usr/bin/env python3
"""
Compare two running instances of this API, endpoint by endpoint.

    python3 scripts/parity.py http://127.0.0.1:8002 http://127.0.0.1:8001

Built for the Node -> Java rewrite, but it only speaks HTTP: it works for any
refactor that must not change the contract. Both servers should read the same
static store, so store-backed endpoints are compared exactly. Live endpoints hit
stcp.pt a moment apart and ETAs can tick over between the two calls, so for those
an exact match is reported when it happens and a structural match (same keys,
same JSON types, same status) is accepted when it doesn't.
"""
import json
import sys
import urllib.error
import urllib.parse
import urllib.request

# Timestamps, and the circuit breaker, whose state depends on when each process
# happened to see its failures.
VOLATILE = {"generated_at", "upstream"}


def fetch(base, path):
    try:
        with urllib.request.urlopen(base + path, timeout=60) as r:
            return r.status, r.headers.get("Content-Type", ""), r.read()
    except urllib.error.HTTPError as e:
        return e.code, e.headers.get("Content-Type", ""), e.read()


def strip(v):
    if isinstance(v, dict):
        return {k: strip(x) for k, x in v.items() if k not in VOLATILE}
    if isinstance(v, list):
        return [strip(x) for x in v]
    if isinstance(v, bool) or v is None or isinstance(v, str):
        return v
    return float(v)


def kind(v):
    if v is None:
        return "null"
    if isinstance(v, bool):
        return "bool"
    if isinstance(v, (int, float)):
        return "number"
    return type(v).__name__


def shape_diff(a, b, path="$"):
    """Structural differences. null is compatible with anything (a live field may be absent today)."""
    if a is None or b is None:
        return []
    if kind(a) != kind(b):
        return [f"{path}: {kind(a)} vs {kind(b)}"]
    if isinstance(a, dict):
        out = []
        if set(a) != set(b):
            out.append(f"{path}: keys {sorted(set(a) ^ set(b))} differ")
        for k in set(a) & set(b):
            out += shape_diff(a[k], b[k], f"{path}.{k}")
        return out
    if isinstance(a, list):
        out = []
        for x, y in zip(a[:3], b[:3]):
            out += shape_diff(x, y, f"{path}[]")
        return out
    return []


def main():
    if len(sys.argv) != 3:
        print(__doc__)
        return 2
    ref, new = sys.argv[1].rstrip("/"), sys.argv[2].rstrip("/")

    # Discover real ids from the reference server rather than hardcoding ones that expire.
    _, _, body = fetch(ref, "/lines/300/services")
    service = json.loads(body).get("active_service_id") or ""
    svc = urllib.parse.quote(service, safe="")
    _, _, body = fetch(ref, "/stops/CMO/realtime")
    arrivals = json.loads(body).get("arrivals", [])
    live = next((a for a in arrivals if a.get("trip_id")), None)
    bbox = "-8.62,41.14,-8.61,41.15"

    store = [
        "/health",
        "/stops",
        "/stops/",
        "/stops?q=carmo&limit=5",
        "/stops?limit=5000",
        f"/stops?bbox={bbox}",
        f"/stops?bbox={bbox}&q=ali",
        f"/stops/lines?bbox={bbox}",
        "/stops/CMO",
        "/lines",
        "/trips/stops?line=300&stop=CMO&eta_minutes=10",
        # error paths
        "/stops/NOPE_NOT_A_STOP",
        "/stops/lines",
        "/stops?bbox=1,2,3",
        "/stops?bbox=5,5,1,1",
        "/stops/CMO/departures",
        "/stops/CMO/schedule",
        "/lines/300/schedule",
        "/trips/garbage/stops",
        "/trips/stops",
    ]
    if live:
        tid = urllib.parse.quote(live["trip_id"], safe="")
        hints = urllib.parse.urlencode(
            {"line": live["line"], "headsign": live["destination"], "stop": "CMO", "eta_minutes": live["arrival_minutes"]}
        )
        store += [f"/trips/{tid}/stops", f"/trips/{tid}/stops?{hints}"]

    upstream = [
        "/stops/CMO/realtime",
        "/stops/NOPE_NOT_A_STOP/realtime",
        "/stops/NOPE_NOT_A_STOP/departures?line=300",
        "/lines/NOPE999/stops",
        "/lines/NOPE999/services",
        "/stops/CMO/routes",
        "/stops/CMO/services",
        f"/stops/CMO/schedule?route_id=300&service_id={svc}&direction_id=0",
        "/stops/CMO/departures?line=300",
        "/stops/CMO/departures?line=300&limit=3&window_minutes=5",
        "/lines/300/stops?direction_id=1",
        "/lines/300/shape?direction_id=0",
        "/lines/300/services",
        f"/lines/300/services?date=2026-09-20",
        f"/lines/300/schedule?service_id={svc}&direction_id=0",
        "/board?lat=41.147223&lon=-8.616926",
        "/board?lat=41.147223&lon=-8.616926&sort=eta&include_unreachable=1&buffer=2&limit=4",
        "/board.txt?lat=41.147223&lon=-8.616926&width=30&title=DESK",
        "/board?lat=abc&lon=",
        "/board.txt?color=1",
    ]

    failures = 0
    for path, exact_required in [(p, True) for p in store] + [(p, False) for p in upstream]:
        s1, t1, b1 = fetch(ref, path)
        s2, t2, b2 = fetch(new, path)
        problems = []
        if s1 != s2:
            problems.append(f"status {s1} vs {s2}")
        if t1.split(";")[0] != t2.split(";")[0]:
            problems.append(f"content-type {t1!r} vs {t2!r}")
        verdict = "exact"
        if "json" in t1 and "json" in t2:
            j1, j2 = strip(json.loads(b1)), strip(json.loads(b2))
            if j1 != j2 and s1 >= 500 and s1 == s2 and set(j1) == {"detail"}:
                # A transport failure's wording is diagnostic, not contract.
                verdict = "shape"
            elif j1 != j2:
                diffs = shape_diff(j1, j2)
                if exact_required or diffs or s1 != 200:
                    problems += diffs or ["values differ"]
                    if not diffs:
                        problems.append(f"  ref: {json.dumps(j1)[:300]}\n  new: {json.dumps(j2)[:300]}")
                verdict = "shape"
        elif b1 != b2:
            if exact_required or s1 != 200:
                problems.append("body differs")
            verdict = "shape"
        mark = "FAIL" if problems else "ok  "
        failures += bool(problems)
        print(f"{mark} {s2} {verdict:5} {path}")
        for p in problems:
            print(f"       {p}")
    print(f"\n{failures} failing endpoint(s)")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
