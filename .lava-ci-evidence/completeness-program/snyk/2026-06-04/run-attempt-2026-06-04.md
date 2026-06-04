# Snyk scan attempt — 2026-06-04 (completeness program, Phase 2C)

- **Host:** Darwin/arm64; runtime: rootless **podman**.
- **Command:** `./scripts/snyk-scan.sh deps`.

## Result: BLOCKED on missing SNYK_TOKEN (honest refusal — no fake pass)

`SNYK_TOKEN` is unset in the root `.env`, so the script refused to run and
exited non-zero (exit code `1`) with:

```
ERROR: SNYK_TOKEN is not set. Set SNYK_TOKEN in .env (see .env.example).
       Obtain it from https://app.snyk.io/account (Auth Token).
       Interactive 'snyk auth' is NOT used here by design (§6.R/§6.U).
       This is NOT a passed scan — nothing ran.
```

This is the §6.J / §6.Z anti-bluff behavior: no token ⇒ no scan ⇒ no fabricated
"passed" result. Interactive `snyk auth` is intentionally NOT used.

## Operator action required to complete

1. Obtain a Snyk Auth Token from <https://app.snyk.io/account>.
2. Add `SNYK_TOKEN=<token>` to the root `.env` (gitignored — §6.H).
3. Re-run `./scripts/snyk-scan.sh` (or `deps` / `code`).
4. SARIF + log land under `.lava-ci-evidence/completeness-program/snyk/<date>/`.
