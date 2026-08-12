# LVA-081 — submodule fetch+pull+merge build-verify evidence

Operator-authorized one-time override of the frozen-by-default submodule
pin policy (Decoupled Reusable Architecture rule). Dispatched to a parallel
subagent in an isolated git worktree; merged into master as commit range
starting `f4e86b52`, merge commit on master.

## Fetch/behind-count results (all 17 targets: 16 vasic-digital + constitution)

Bumped (3 of 17):

| Submodule | Old SHA | New SHA | Commits behind |
|---|---|---|---|
| `constitution` | `6bf67ce4` | `3cc71cd8` | 35 |
| `submodules/challenges` | `41d1a134` | `072724af` | 4 |
| `submodules/security` | `a0dad7dc` | `6633661b` | 2 |

Already current (14 of 17, `behind=0`, unchanged): `auth`, `cache`,
`concurrency`, `config`, `containers`, `database`, `discovery`, `http3`,
`mdns`, `middleware`, `observability`, `ratelimiter`, `recovery`,
`tracker_sdk`.

## Build-verify runs (real command output, not assumed)

1. Baseline before any bump: `./gradlew :app:compileDebugKotlin --no-daemon`
   -> `BUILD SUCCESSFUL` (1m 53s, 638 tasks).
2. After `security` bump: `cd lava-api-go && go build ./...` -> exit 0, no
   output.
3. After `challenges` bump: `go build ./...` -> exit 0, no output.
4. After `constitution` bump: `go build ./...` -> exit 0, no output.
5. Final combined check: `:app:compileDebugKotlin` -> `BUILD SUCCESSFUL`
   (637/638 tasks UP-TO-DATE).
6. Post-merge, main worktree re-verification (this session): main-worktree
   submodule checkouts synced via `git submodule update` to the new pins;
   `cd lava-api-go && GOMAXPROCS=2 go build ./...` -> exit 0 ("go build OK").
7. Push to GitHub (`git push github master`) completed with the full
   `scripts/ci.sh --changed-only` pre-push gate green, including the
   `§11.4.32 verify-all-constitution-rules sweep (STRICT)` and the
   `API<->embed source-sync gate` — both passed against the bumped
   submodule tree.

## Outcome

None of the 3 bumps broke the build; none were reverted. Constitution
submodule's 35 new commits were NOT reviewed/implemented here — that is
explicitly out of LVA-081's scope and is tracked separately as LVA-3.
