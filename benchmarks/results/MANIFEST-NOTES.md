# Notes on MANIFEST.sha256

- `MANIFEST.sha256` lists SHA-256 checksums of every file in every run directory (6257 files), including the raw
  files that are not committed (see `benchmarks/README.md`).
- **Redaction (2026-11-12, approved):** 73 files contained expired test JWTs (fresh single-user tokens for B5,
  B6 and B8 runs and three dry runs; 2-hour TTL, synthetic users). Only the token strings were replaced with
  `<redacted: expired test JWT>`; no other content changed, and validity, reconciliation and correctness records were
  not touched. The checksums above are of the redacted files. The original files are kept locally in
  `benchmarks/archive/phase-3b-jwt-originals.tar.gz` (git-ignored; SHA-256
  `4224931bd76f4cffe2a8e6d227b8dc8c32e9543511fb872dfb883e0175884064`).
- The tooling no longer writes tokens into run artifacts (`runner.py` redacts `run-meta.json`; `common.js` redacts
  `k6-config.json`).
