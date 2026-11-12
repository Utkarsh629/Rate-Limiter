#!/usr/bin/env bash
# Phase 3B benchmark infrastructure: creates, switches, verifies and deletes the dedicated benchmark policy (decision O5).
#
#   scripts/bench/setup-policies.sh apply  <token_bucket|fixed_window|sliding_window>   # create, or switch algorithm (PUT)
#   scripts/bench/setup-policies.sh verify <token_bucket|fixed_window|sliding_window>   # check all 3 instances apply it
#   scripts/bench/setup-policies.sh show                                                 # print the stored policy
#   scripts/bench/setup-policies.sh delete                                               # delete, then check all 3 dropped it
#   scripts/bench/setup-policies.sh selftest                                             # conflict-guard tests (no stack needed)
#
# The policy (one policy, approved allowance "120 requests per 60 s per user" for every algorithm):
#   name bench-profile · GET /api/v1/demo/profile · tier FREE · key USER · limit 120 · window 60,000 ms
#   TOKEN_BUCKET:   capacity 120, refill 120 tokens per 60 s (2 tokens/s, continuous)
#   FIXED_WINDOW:   120 per aligned 60 s window
#   SLIDING_WINDOW: 120 per 60 s, sliding window counter (weighted previous window)
#   failure mode: the global default (FAIL_OPEN); priority 0
# Precedence (PolicySnapshot): tier-specific beats any-tier, then the more specific path beats /api/**, so FREE
# tokens on /api/v1/demo/profile resolve to this policy instead of the free-tier seed policy (20/min).
#
# Propagation evidence per instance: a fresh probe user (FREE token minted offline from JWT_SECRET) is sent through
# Nginx until each of app1/app2/app3 (identified from Nginx's access log by the probe's User-Agent; Nginx returns
# no X-Upstream header, security review SEC-6) has answered with X-RateLimit-Limit=120 AND created the Redis key
# of this policy with the expected algorithm suffix (rl:{<policyId>:u:<user>}:tb|fw|sw) and no other suffix.
# Probe requests use fresh users only, never benchmark users; they are counted in metrics and /stats like any request,
# so run them before a run's idle counter snapshot (run.sh), never during a run.
#
# Conflict guard (apply and verify; never blocks show or delete). Measured against the running application: besides
# an identical path, wildcard patterns can win too. "/api/v1/demo/prof?le" with priority 1000 overrode bench-profile
# (it ties on specificity, then priority decides), while "/*", "/profile/**", "/{name}", any-method and any-tier
# variants did not. Rather than re-implementing Spring's specificity rules, the guard rejects EVERY enabled policy
# that can match GET /api/v1/demo/profile for a FREE caller (tier FREE, method GET or any, pattern matching the path),
# except bench-profile itself and the general seed pattern /api/** (a catch-all is always less specific than a
# literal path). Policies for any tier (tier null) are allowed: a tier-specific policy always takes precedence.
# Pattern matching is a deliberate SUPERSET of Spring's PathPattern matching, so it can only add conflicts:
#   - "**" and "{*var}" as a whole segment match zero or more segments wherever they appear. Spring 6.2 accepts them
#     only at the start or end (e.g. "/**/profile" is valid and matches the endpoint) and rejects embedded ones such
#     as "/api/**/profile" with HTTP 400; the guard still treats embedded ones as multi-segment wildcards.
#   - "*", "?" and "{var}" match within one segment; "{var}" may be empty (Spring captures "(.*)" when a variable
#     shares a segment with other text, e.g. "pro{x}file").
#   - "{var:regex}" is evaluated ONLY when the regex belongs to a subset with identical Java and Python semantics:
#     a non-empty sequence of atoms, each optionally followed by ONE greedy quantifier.
#       atom:       a literal [A-Za-z0-9_-] | "." | a class "[...]" or "[^...]" of literals [A-Za-z0-9_] and ranges
#                   between two lowercase letters, two uppercase letters or two digits (ascending)
#       quantifier: "*" | "+" | "?" | "{n}" | "{n,}" | "{n,m}" (n <= m); no lazy "?" or possessive "+" suffix
#     Everything else is uncertain and counts as a conflict: escapes ("\d", Java-only "\p{Lower}"), groups,
#     alternation, anchors, flags, "-" or "&&" inside a class (Java class intersection: "[a-z&&[a-z]]+" matches
#     "profile" in Java but not in Python), "{,m}" (valid in Python, a syntax error in Java), empty regexes.
#   - Anything the guard cannot interpret with certainty is a conflict (fail safe): "**" or "{*" inside a segment
#     (e.g. "/api/a**b/profile"), unbalanced braces, a regex outside the subset above.
# `selftest` checks the matcher and the conflict classification against a fixed table of cases.
#
# Requires: the Compose stack up, .env with JWT_SECRET, python3 (stdlib), curl, docker compose (selftest: python3 only).
# Never modifies any other policy.
set -euo pipefail
cd "$(dirname "$0")/../.."

ENDPOINT=/api/v1/demo/profile
TIER=FREE
# POLICY_VARIANT selects which dedicated benchmark policy this invocation manages (both on the same endpoint and
# tier, so only one can exist at a time; the conflict guard enforces that):
#   default      bench-profile      120 requests per 60 s per user, every algorithm (decision O5)
#   concurrency  bench-concurrency  B6 only: token bucket capacity 100, refill 1 per day (deterministic, as in 3A)
case "${POLICY_VARIANT:-default}" in
  default)
    NAME=bench-profile; LIMIT=120; WINDOW_MS=60000
    REFILL_TOKENS=120             # token bucket: capacity = limit, full refill per window -> same allowance
    DESCRIPTION="Phase 3B benchmark policy: 120 requests per 60 s per user for every algorithm (O5)" ;;
  concurrency)
    NAME=bench-concurrency; LIMIT=100; WINDOW_MS=86400000; REFILL_TOKENS=1
    DESCRIPTION="Phase 3B B6 concurrency policy: token bucket capacity 100, refill 1 per day" ;;
  *) echo "ERROR: unknown POLICY_VARIANT '${POLICY_VARIANT}' (default | concurrency)" >&2; exit 2 ;;
esac
PROPAGATION_TIMEOUT_S=${PROPAGATION_TIMEOUT_S:-40}

# Conflict guard (Python, stdlib). Modes:
#   check    POLICIES_JSONL NAME ENDPOINT TIER  -> exit 1 listing every conflicting policy
#   selftest NAME ENDPOINT TIER                 -> runs the fixed test table, exit 1 on any failure
GUARD_PY=$(cat <<'PY'
import json, re, sys

ALLOWED_GENERAL = "/api/**"   # the free-tier seed policy: a catch-all, always less specific than a literal path

class Uncertain(Exception):
    pass

MULTI = re.compile(r"\*\*|\{\*[^{}]*\}")          # whole-segment multi-segment wildcards
SAFE_LITERAL = re.compile(r"[A-Za-z0-9_-]")
CLASS_LITERAL = re.compile(r"[A-Za-z0-9_]")
QUANT_BRACES = re.compile(r"\{(\d+)(,(\d+)?)?\}")

def same_kind(lo, hi):
    return lo <= hi and any(a <= lo <= hi <= b for a, b in (("a", "z"), ("A", "Z"), ("0", "9")))

def regex_in_safe_subset(rx):
    """True only for the Java/Python-equivalent subset documented in the header; False means uncertain."""
    i, n, atoms = 0, len(rx), 0
    while i < n:
        c = rx[i]
        if SAFE_LITERAL.fullmatch(c) or c == ".":
            i += 1
        elif c == "[":
            j = i + 1
            if j < n and rx[j] == "^":
                j += 1
            items = 0
            while j < n and rx[j] != "]":
                if not CLASS_LITERAL.fullmatch(rx[j]):
                    return False                       # '-', '&', '[', '\', ':' ... inside a class
                if j + 2 < n and rx[j + 1] == "-" and rx[j + 2] != "]":
                    if not same_kind(rx[j], rx[j + 2]):
                        return False
                    j += 3
                else:
                    j += 1
                items += 1
            if j >= n or items == 0:
                return False
            i = j + 1
        else:
            return False                               # escapes, groups, alternation, anchors, flags, ...
        atoms += 1
        if i < n and rx[i] in "*+?":
            i += 1
        elif i < n and rx[i] == "{":
            m = QUANT_BRACES.match(rx, i)
            if not m or (m.group(3) is not None and int(m.group(3)) < int(m.group(1))):
                return False                           # includes Python-only "{,m}"
            i = m.end()
        if i < n and rx[i] in "*+?{":
            return False                               # lazy, possessive or stacked quantifiers
    return atoms > 0

def segment_regex(seg):
    """One single-segment PathPattern element -> regex: {var} / {var:regex}, '*', '?', literal text."""
    if "**" in seg or "{*" in seg:
        raise Uncertain("'**' or '{*' inside a segment: " + seg)
    out, i = "", 0
    while i < len(seg):
        c = seg[i]
        if c == "{":
            depth, j = 1, i + 1
            while j < len(seg) and depth:
                depth += {"{": 1, "}": -1}.get(seg[j], 0)
                j += 1
            if depth:
                raise Uncertain("unbalanced brace: " + seg)
            body = seg[i + 1:j - 1]
            if ":" in body:
                inner = body.split(":", 1)[1]
                if not regex_in_safe_subset(inner):
                    raise Uncertain("variable regex outside the Java/Python-equivalent subset: " + seg)
                try:
                    re.compile(inner)
                except re.error as e:
                    raise Uncertain(f"variable regex not understood ({e}): {seg}")
                out += "(?:" + inner + ")"
            else:
                out += "[^/]*"                         # may be empty when the variable shares a segment
            i = j
        elif c == "}":
            raise Uncertain("unbalanced brace: " + seg)
        elif c == "*":
            out, i = out + "[^/]*", i + 1
        elif c == "?":
            out, i = out + "[^/]", i + 1
        else:
            out, i = out + re.escape(c), i + 1
    return out

def can_match(pattern, path):
    """(matches, reason). A superset of Spring PathPattern matching; uncertain patterns count as matching."""
    try:
        if not pattern.startswith("/"):
            raise Uncertain("pattern does not start with '/'")
        segs = [s for s in pattern.split("/")[1:]]
        regex = ""
        for s in segs:
            regex += "(?:/.*)?" if MULTI.fullmatch(s) else "/" + segment_regex(s)
        regex = regex or "/?"
        return (re.fullmatch(regex, path) is not None, "pattern match")
    except Uncertain as e:
        return (True, "uncertain, treated as conflict: " + str(e))
    except re.error as e:
        return (True, f"uncertain, treated as conflict: {e}")

def conflicts(policies, name, endpoint, tier):
    out = []
    for p in policies:
        if not p["enabled"] or p["name"] == name or p["tier"] != tier or p["httpMethod"] not in ("GET", None):
            continue
        if p["endpointPattern"] == ALLOWED_GENERAL:
            continue
        matches, reason = can_match(p["endpointPattern"], endpoint)
        if matches:
            out.append((p, reason))
    return out

def check(path, name, endpoint, tier):
    policies = [json.loads(l) for l in open(path) if l.strip()]
    found = conflicts(policies, name, endpoint, tier)
    if found:
        sys.exit("ERROR: enabled policies that can match GET " + endpoint + " for tier " + tier
                 + " and could take precedence over " + name + " (delete or disable them first):\n"
                 + "\n".join(f'  id {p["id"]} {p["name"]}: {p["httpMethod"] or "any method"} {p["endpointPattern"]} '
                             f'priority {p["priority"]} [{reason}]' for p, reason in found))

def selftest(name, endpoint, tier):
    # (pattern, expected can_match for the endpoint, note); expected True also covers "uncertain".
    match_cases = [
        # embedded '**' / '{*var}' (rejected by the application with HTTP 400; guard: zero or more segments)
        ("/api/**/profile", True, "embedded **"),
        ("/api/**/demo/profile", True, "embedded **"),
        ("/api/v1/**/profile", True, "embedded **"),
        ("/api/**/v1/**/profile", True, "two embedded **"),
        ("/api/{*rest}/profile", True, "embedded {*var}"),
        ("/api/v1/**/premium", False, "embedded **, different last segment"),
        ("/api/**/demo", False, "embedded **, path continues after /demo"),
        # leading '**' / '{*var}' (accepted by the application)
        ("/**/profile", True, "leading **"),
        ("/{*p}/profile", True, "leading {*var}"),
        ("/**/premium", False, "leading **, different last segment"),
        ("/**", True, "catch-all"),
        # trailing '**' / '{*var}'
        ("/api/**", True, "seed pattern (allowed by name in the classification)"),
        ("/api/v1/demo/profile/**", True, "trailing ** matches zero segments"),
        ("/api/v1/{*rest}", True, "trailing {*var}"),
        ("/api/v2/**", False, "trailing **, other prefix"),
        # single-segment wildcards and variables
        ("/api/v1/demo/profile", True, "literal"),
        ("/api/v1/demo/*", True, "*"),
        ("/api/v1/*/profile", True, "* in the middle"),
        ("/api/v1/demo/prof?le", True, "?"),
        ("/api/v1/demo/pro*", True, "* within a segment"),
        ("/api/v1/demo/{name}", True, "{var}"),
        ("/api/v1/demo/{n:pro.*}", True, "{var:regex} matching"),
        ("/api/v1/demo/{n:pub.*}", False, "{var:regex} not matching"),
        ("/api/v1/demo/{a}/{b}", False, "one segment too many"),
        ("/api/v1/demo", False, "one segment too few"),
        ("/api/v1/demo/profile/x", False, "longer literal"),
        ("/api/v1/demo/premium", False, "other literal"),
        ("/API/v1/demo/profile", False, "case-sensitive literal"),
        # uncertain -> conflict
        ("/api/a**b/profile", True, "'**' inside a segment (accepted by the application)"),
        ("/api/v1/demo/pro{*x}", True, "'{*' inside a segment"),
        ("/api/v1/demo/{x", True, "unbalanced brace"),
        ("/api/v1/demo/{n:[}", True, "regex Python cannot compile"),
        # ---- constrained regex variables: evaluated only inside the Java/Python-equivalent subset ----
        ("/api/v1/demo/{n:[a-z]+}", True, "subset: class + quantifier, matches"),
        ("/api/v1/demo/{n:[0-9]+}", False, "subset: digits cannot match 'profile'"),
        ("/api/v1/demo/{n:[^0-9]+}", True, "subset: negated class, matches"),
        ("/api/v1/demo/{n:p.{6}}", True, "subset: bounded repetition, matches"),
        ("/api/v1/demo/{n:p.{2}}", False, "subset: bounded repetition, too short"),
        ("/api/v1/demo/{n:p.{2,}}", True, "subset: open repetition, matches"),
        ("/api/v1/demo/{n:pro_file-x?}", False, "subset: literals incl. '_' and '-', no match"),
        ("/api/v1/demo/pro{n:[a-z]+}", True, "subset: regex variable sharing a segment"),
        ("/api/v1/demo/{n:[a-z&&[a-z]]+}", True, "DIALECT: Java class intersection (Java true, Python false)"),
        ("/api/v1/demo/{n:p.{,6}}", True, "DIALECT: '{,m}' (Python true, Java syntax error)"),
        ("/api/v1/demo/{n:\\p{Lower}+}", True, "Java-only escape \\p{Lower}"),
        ("/api/v1/demo/{n:pub\\d+}", True, "escape \\d (outside subset)"),
        ("/api/v1/demo/{n:(pub|pri).*}", True, "group + alternation (outside subset, even if it cannot match)"),
        ("/api/v1/demo/{n:^profile$}", True, "anchors (outside subset)"),
        ("/api/v1/demo/{n:(?i)PROFILE}", True, "inline flag (outside subset)"),
        ("/api/v1/demo/{n:pro.*?}", True, "lazy quantifier (outside subset)"),
        ("/api/v1/demo/{n:pro.*+}", True, "possessive quantifier (outside subset)"),
        ("/api/v1/demo/{n:[a-z-]+}", True, "'-' as a class literal (outside subset)"),
        ("/api/v1/demo/{n:[z-a]+}", True, "descending range (outside subset)"),
        ("/api/v1/demo/{n:[[:alpha:]]+}", True, "POSIX-style class (outside subset)"),
        ("/api/v1/demo/{n:}", True, "empty regex (outside subset)"),
        # ---- plain variables may be empty when they share a segment with text ----
        ("/api/v1/demo/pro{x}file", True, "{var} inside a segment can capture ''"),
        ("/api/v1/demo/pro{x}files", False, "{var} inside a segment, literal suffix differs"),
    ]
    # Known dialect differences (Java results verified with JDK 17 java.util.regex): the guard must never trust
    # Python's own verdict for these.
    dialect_cases = [
        ("[a-z&&[a-z]]+", True, "Java: intersection of a-z and a-z"),
        ("p.{,6}", None, "Java: PatternSyntaxException (Spring cannot use it)"),
    ]
    def pol(pattern, tier_=tier, method="GET", enabled=True, pname="t"):
        return {"id": 0, "name": pname, "endpointPattern": pattern, "tier": tier_, "httpMethod": method,
                "enabled": enabled, "priority": 0}
    # (policy, expected conflict, note): the same classification apply and verify use
    class_cases = [
        (pol("/api/**", method=None, pname="free-tier"), False, "seed /api/** stays allowed"),
        (pol("/api/v1/demo/profile", pname=name), False, "bench-profile itself"),
        (pol("/api/**/profile"), True, "FREE GET embedded **"),
        (pol("/api/**/demo/profile", method=None), True, "FREE any-method embedded **"),
        (pol("/api/v1/**/profile"), True, "FREE GET embedded **"),
        (pol("/**/profile"), True, "FREE GET leading **"),
        (pol("/api/a**b/profile"), True, "FREE GET uncertain pattern"),
        (pol("/api/v1/demo/prof?le"), True, "FREE GET ? (proven override)"),
        (pol("/api/v1/demo/profile/**"), True, "FREE GET trailing **"),
        (pol("/api/**/profile", tier_=None), False, "any tier: cannot take precedence"),
        (pol("/api/**/profile", tier_="PREMIUM"), False, "other tier"),
        (pol("/api/**/profile", method="POST"), False, "other method"),
        (pol("/api/**/profile", enabled=False), False, "disabled"),
        (pol("/api/v1/**/premium"), False, "cannot match the endpoint"),
        (pol("/api/v1/demo/{n:[a-z&&[a-z]]+}"), True, "FREE GET Java-only regex semantics"),
        (pol("/api/v1/demo/{n:(pub|pri).*}"), True, "FREE GET regex outside the subset"),
        (pol("/api/v1/demo/{n:[0-9]+}"), False, "FREE GET subset regex that cannot match"),
        (pol("/api/v1/demo/pro{x}file"), True, "FREE GET empty-capable {var}"),
    ]
    failures = 0
    print(f"can_match(pattern, {endpoint}):")
    for pattern, expected, note in match_cases:
        got, reason = can_match(pattern, endpoint)
        ok = got == expected
        failures += not ok
        print(f"  {'PASS' if ok else 'FAIL'}  {pattern:28} expected={str(expected):5} got={str(got):5} {note} [{reason}]")
    print(f"conflict classification (tier {tier}, {name}):")
    for p, expected, note in class_cases:
        got = bool(conflicts([p], name, endpoint, tier))
        ok = got == expected
        failures += not ok
        print(f"  {'PASS' if ok else 'FAIL'}  {p['endpointPattern']:28} tier={str(p['tier']):7} method={str(p['httpMethod']):4} "
              f"enabled={str(p['enabled']):5} expected={'conflict' if expected else 'allowed':8} {note}")
    print("Java/Python regex dialect differences (the guard must report a conflict regardless of Python's verdict):")
    import warnings
    for rx, java, note in dialect_cases:
        with warnings.catch_warnings():
            warnings.simplefilter("ignore")              # Python warns about possible set operations in "&&"
            try:
                py = re.fullmatch(rx, "profile") is not None
            except re.error:
                py = "error"
        guard, reason = can_match("/api/v1/demo/{n:" + rx + "}", endpoint)
        ok = guard is True and not regex_in_safe_subset(rx)
        failures += not ok
        print(f"  {'PASS' if ok else 'FAIL'}  {rx:16} Python={str(py):5} Java={'error' if java is None else java!s:5} "
              f"guard={'conflict' if guard else 'allowed'} ({note}) [{reason}]")
    total = len(match_cases) + len(class_cases) + len(dialect_cases)
    print(f"selftest: {total - failures}/{total} passed")
    sys.exit(1 if failures else 0)

mode = sys.argv[1]
if mode == "check":
    check(*sys.argv[2:6])
elif mode == "selftest":
    selftest(*sys.argv[2:5])
else:
    sys.exit("unknown guard mode " + mode)
PY
)

if [ "${1:-}" = selftest ]; then
  exec python3 -c "$GUARD_PY" selftest "$NAME" "$ENDPOINT" "$TIER"
fi

[ -f .env ] || { echo "Missing .env" >&2; exit 2; }
PORT=$(sed -n 's/^NGINX_HOST_PORT=//p' .env | tail -1)
BASE=${BASE_URL:-http://localhost:${PORT:-8080}}
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

die() { echo "ERROR: $*" >&2; exit 1; }

usage() { sed -n '2,8p' "$0" | sed 's/^# \{0,1\}//'; exit 2; }

algorithm_enum() {
  case "$1" in
    token_bucket) echo TOKEN_BUCKET ;; fixed_window) echo FIXED_WINDOW ;; sliding_window) echo SLIDING_WINDOW ;;
    *) die "unknown algorithm '$1' (token_bucket | fixed_window | sliding_window)" ;;
  esac
}
algorithm_suffix() {
  case "$1" in token_bucket) echo tb ;; fixed_window) echo fw ;; sliding_window) echo sw ;; esac
}

ADMIN=$(python3 scripts/bench/mint-tokens.py --print-admin --ttl 900)

# api METHOD PATH [JSON-BODY] -> prints the HTTP status; the response body is in $TMP/body
api() {
  local args=(-s --noproxy '*' -o "$TMP/body" -w '%{http_code}' -X "$1" "$BASE$2" -H "Authorization: Bearer $ADMIN")
  if [ $# -ge 3 ]; then args+=(-H 'Content-Type: application/json' --data "$3"); fi
  curl "${args[@]}"
}

# Writes every policy (all pages) to $TMP/all.jsonl, one JSON object per line.
list_policies() {
  local page=0 status
  : > "$TMP/all.jsonl"
  while :; do
    status=$(api GET "/api/v1/policies?page=$page&size=100")
    [ "$status" = 200 ] || die "listing policies failed: HTTP $status $(cat "$TMP/body")"
    python3 -c 'import json,sys; [print(json.dumps(p)) for p in json.load(open(sys.argv[1]))["content"]]' \
      "$TMP/body" >> "$TMP/all.jsonl"
    python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); sys.exit(0 if d["page"]+1 < d["totalPages"] else 1)' \
      "$TMP/body" || break
    page=$((page + 1))
  done
}

# Prints the stored bench policy as JSON (empty if absent).
find_policy() {
  list_policies
  python3 - "$TMP/all.jsonl" "$NAME" <<'PY'
import json, sys
path, name = sys.argv[1:]
mine = [p for p in (json.loads(l) for l in open(path) if l.strip()) if p["name"] == name]
print(json.dumps(mine[0]) if mine else "")
PY
}

# Exits with an error listing every enabled policy that could take precedence over bench-profile (see header).
check_conflicts() {
  list_policies
  python3 -c "$GUARD_PY" check "$TMP/all.jsonl" "$NAME" "$ENDPOINT" "$TIER"
}

policy_body() {  # ALGORITHM_ENUM VERSION|null
  python3 - "$NAME" "$ENDPOINT" "$TIER" "$LIMIT" "$WINDOW_MS" "$REFILL_TOKENS" "$1" "$2" "$DESCRIPTION" <<'PY'
import json, sys
name, endpoint, tier, limit, window_ms, refill, algorithm, version, description = sys.argv[1:]
print(json.dumps({
    "name": name,
    "description": description,
    "httpMethod": "GET", "endpointPattern": endpoint, "tier": tier, "keyBy": "USER",
    "algorithm": algorithm, "limit": int(limit), "windowMs": int(window_ms),
    "refillTokens": int(refill) if algorithm == "TOKEN_BUCKET" else None,
    "failureMode": None, "priority": 0, "enabled": True,
    "version": None if version == "null" else int(version),
}))
PY
}

field() { python3 -c 'import json,sys; v=json.loads(sys.argv[1])[sys.argv[2]]; print("" if v is None else v)' "$1" "$2"; }

redis_exists() { docker compose exec -T redis redis-cli EXISTS "$1" | tr -d '\r'; }

# Upstream address of the probe request with this User-Agent marker, from Nginx's access log (upstream=<addr>;
# after a retry the list is "a, b" and the last entry answered). Prints "none" if no log line appears within ~5 s.
probe_upstream() {
  local marker=$1 since=$2 tries=0 line
  while [ "$tries" -lt 25 ]; do
    line=$(docker compose logs --no-log-prefix --since "$since" nginx 2>/dev/null | grep -F "\"ratelimiter-probe/$marker\"" | tail -1)
    if [ -n "$line" ]; then
      echo "$line" | sed -n 's/.* upstream=\(.*\) upstream_time=.*/\1/p' | awk -F', ' '{print $NF}'
      return
    fi
    tries=$((tries + 1)); sleep 0.2
  done
  echo none
}

# Upstream address -> instance number 1..3 (static Compose addresses), 0 if unknown
instance_number() {
  case "${1%%:*}" in 172.28.0.11) echo 1 ;; 172.28.0.12) echo 2 ;; 172.28.0.13) echo 3 ;; *) echo 0 ;; esac
}

# probe_until EXPECT POLICY_ID [SUFFIX]          (bash 3.2 compatible: indexed arrays only)
#   EXPECT=present: every instance answers with limit 120 and creates only the key with SUFFIX
#   EXPECT=absent:  every instance answers without creating any key of POLICY_ID (policy no longer applied)
# An instance counts as confirmed only while its MOST RECENT probe shows the expected state.
probe_until() {
  local expect=$1 id=$2 want=${3:-} start i=0 user token headers upstream n limit status found s confirmed since
  local ok=(0 0 0 0) probes=(0 0 0 0) last=("" "" "" "")
  start=$(date +%s)
  while :; do
    confirmed=$(( ok[1] + ok[2] + ok[3] ))
    [ "$confirmed" -lt 3 ] || break
    if [ $(( $(date +%s) - start )) -ge "$PROPAGATION_TIMEOUT_S" ]; then
      echo "TIMEOUT after ${PROPAGATION_TIMEOUT_S}s; last observation per instance:" >&2
      for n in 1 2 3; do echo "  app$n: ${last[$n]:-no probe answered}" >&2; done
      [ -z "${last[0]}" ] || echo "  unknown upstream: ${last[0]}" >&2
      return 1
    fi
    user="bench-probe-$(date +%s)-$$-$i"; i=$((i + 1))
    token=$(python3 scripts/bench/mint-tokens.py --print-user "$user" FREE --ttl 300)
    since=$(( $(date +%s) - 2 ))
    headers=$(curl -s --noproxy '*' -o /dev/null -D - -A "ratelimiter-probe/$user" "$BASE$ENDPOINT" \
      -H "Authorization: Bearer $token" | tr -d '\r')
    status=$(echo "$headers" | awk 'NR==1{print $2}')
    upstream=$(probe_upstream "$user" "$since")
    limit=$(echo "$headers" | awk -F': ' 'tolower($1)=="x-ratelimit-limit"{print $2}')
    n=$(instance_number "${upstream:-none}")
    probes[n]=$(( probes[n] + 1 ))
    found=""
    for s in tb fw sw; do
      if [ "$(redis_exists "rl:{$id:u:$user}:$s")" = 1 ]; then found="$found$s"; fi
    done
    last[n]="status=$status limit=${limit:-none} policy-keys=${found:-none} (upstream ${upstream:-none}, probes ${probes[n]})"
    [ "$n" -ne 0 ] || continue
    if [ "$expect" = present ]; then
      if [ "$status" = 200 ] && [ "$limit" = "$LIMIT" ] && [ "$found" = "$want" ]; then ok[n]=1; else ok[n]=0; fi
    else
      if [ "$status" = 200 ] && [ -z "$found" ]; then ok[n]=1; else ok[n]=0; fi
    fi
  done
  echo "all 3 instances confirmed in $(( $(date +%s) - start ))s ($i probes):"
  for n in 1 2 3; do echo "  app$n: ${last[n]}"; done
}

cmd=${1:-}
case "$cmd" in
  apply)
    [ $# -eq 2 ] || usage
    alg_enum=$(algorithm_enum "$2")
    [ "${POLICY_VARIANT:-default}" != concurrency ] || [ "$alg_enum" = TOKEN_BUCKET ] || die "the concurrency policy is token-bucket only"
    check_conflicts
    current=$(find_policy)
    if [ -z "$current" ]; then
      status=$(api POST /api/v1/policies "$(policy_body "$alg_enum" null)")
      [ "$status" = 201 ] || die "create failed: HTTP $status $(cat "$TMP/body")"
      echo "created $NAME (id $(field "$(cat "$TMP/body")" id), $alg_enum)"
    else
      id=$(field "$current" id); version=$(field "$current" version)
      status=$(api PUT "/api/v1/policies/$id" "$(policy_body "$alg_enum" "$version")")
      [ "$status" = 200 ] || die "update failed: HTTP $status $(cat "$TMP/body")"
      echo "updated $NAME (id $id) $(field "$current" algorithm) -> $alg_enum (version $version -> $(field "$(cat "$TMP/body")" version))"
    fi
    "$0" verify "$2"
    ;;
  verify)
    [ $# -eq 2 ] || usage
    alg_enum=$(algorithm_enum "$2")
    check_conflicts
    current=$(find_policy)
    [ -n "$current" ] || die "$NAME does not exist"
    [ "$(field "$current" algorithm)" = "$alg_enum" ] || die "stored algorithm is $(field "$current" algorithm), not $alg_enum"
    id=$(field "$current" id)
    echo "verifying $NAME (id $id, $alg_enum, limit $LIMIT / ${WINDOW_MS} ms) on every instance via $BASE"
    probe_until present "$id" "$(algorithm_suffix "$2")"
    ;;
  show)
    current=$(find_policy)
    [ -n "$current" ] && python3 -m json.tool <<< "$current" || echo "$NAME does not exist"
    ;;
  delete)
    current=$(find_policy)
    [ -n "$current" ] || { echo "$NAME does not exist; nothing to delete"; exit 0; }
    id=$(field "$current" id)
    status=$(api DELETE "/api/v1/policies/$id")
    [ "$status" = 204 ] || die "delete failed: HTTP $status $(cat "$TMP/body")"
    echo "deleted $NAME (id $id); checking that no instance applies it any more"
    probe_until absent "$id"
    ;;
  *) usage ;;
esac
