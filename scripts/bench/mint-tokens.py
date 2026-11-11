#!/usr/bin/env python3
"""Mints HS256 JWTs for smoke tests and benchmarks, offline, with JWT_SECRET from .env.

The prod profile has no demo-token endpoint (by design, 2B), so tokens cannot be fetched at runtime.
Claims match the application's rules: iss=distributed-rate-limiter, sub, tier, roles, iat, exp.

  python3 scripts/bench/mint-tokens.py --free 2000 --hot 10 --out load-tests/k6/.tokens/tokens.json
  python3 scripts/bench/mint-tokens.py --print-admin            # one admin token on stdout
  python3 scripts/bench/mint-tokens.py --print-user u1 FREE     # one user token on stdout

Output files contain bearer tokens: keep them out of git (load-tests/k6/.tokens/ is ignored).
"""
import argparse
import base64
import hashlib
import hmac
import json
import pathlib
import sys
import time
import uuid

ISSUER = "distributed-rate-limiter"


def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def read_env(path: pathlib.Path) -> dict:
    env = {}
    for line in path.read_text().splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            k, v = line.split("=", 1)
            env[k.strip()] = v.strip()
    return env


def mint(secret_b64: str, sub: str, tier: str, roles=(), ttl=3600) -> str:
    key = base64.b64decode(secret_b64)
    if len(key) < 32:
        sys.exit("JWT_SECRET must decode to >= 32 bytes")
    now = int(time.time())
    header = b64url(json.dumps({"alg": "HS256", "typ": "JWT"}, separators=(",", ":")).encode())
    claims = {"iss": ISSUER, "sub": sub, "tier": tier, "roles": list(roles), "iat": now, "exp": now + ttl}
    payload = b64url(json.dumps(claims, separators=(",", ":")).encode())
    signature = hmac.new(key, f"{header}.{payload}".encode(), hashlib.sha256).digest()
    return f"{header}.{payload}.{b64url(signature)}"


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--env", default=".env")
    p.add_argument("--ttl", type=int, default=3600)
    p.add_argument("--prefix", default=None, help="user id prefix (default: random per run)")
    p.add_argument("--free", type=int, default=0, help="FREE users well below their limit")
    p.add_argument("--hot", type=int, default=0, help="FREE users that will exceed their limit")
    p.add_argument("--premium", type=int, default=0)
    p.add_argument("--enterprise", type=int, default=0)
    p.add_argument("--out")
    p.add_argument("--print-admin", action="store_true")
    p.add_argument("--print-user", nargs=2, metavar=("USER_ID", "TIER"))
    a = p.parse_args()

    secret = read_env(pathlib.Path(a.env)).get("JWT_SECRET")
    if not secret:
        sys.exit("JWT_SECRET missing from " + a.env)
    if a.print_admin:
        print(mint(secret, "admin-smoke", "ENTERPRISE", ["ADMIN"], a.ttl))
        return
    if a.print_user:
        print(mint(secret, a.print_user[0], a.print_user[1], (), a.ttl))
        return

    prefix = a.prefix or "run-" + uuid.uuid4().hex[:8]
    users = []
    for group, tier, n in (("free", "FREE", a.free), ("hot", "FREE", a.hot),
                           ("premium", "PREMIUM", a.premium), ("enterprise", "ENTERPRISE", a.enterprise)):
        for i in range(n):
            uid = f"{prefix}-{group}-{i}"
            users.append({"id": uid, "group": group, "tier": tier, "token": mint(secret, uid, tier, (), a.ttl)})
    out = pathlib.Path(a.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps({"prefix": prefix, "admin": mint(secret, "admin-smoke", "ENTERPRISE", ["ADMIN"], a.ttl),
                               "users": users}))
    print(f"wrote {len(users)} user tokens + 1 admin token to {out}")


if __name__ == "__main__":
    main()
