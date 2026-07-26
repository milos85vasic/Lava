# F3 (api-app x86_64 embed SIGSYS crash) — fix status (companion to 2026-07-04-search-not-working-root-causes.json)

## STATUS: RESOLVED — CONFIRMED FIXED LIVE 2026-07-04

**Fix landed (option B, corrected target):** a fork of `modernc.org/libc` at `lava-api-go/third_party/modernc-libc` (`replace` in `lava-api-go/go.mod`) adds `syscall_musl_seccomp_amd64.go` (+ `syscall_musl_seccomp_other.go` stub) that remaps the blocked legacy path-syscalls (`lstat`/`stat`/`open`/`access`/`readlink`/`unlink`/`rmdir`/`mkdir`/`chmod`/`chown`/`lchown`/`rename`/`link`/`symlink`/`mknod`) to their `*at` equivalents at the **single `X__syscallN` dispatcher choke point** in the hand-written `syscall_musl.go`. This is the file set that ACTUALLY compiles for `GOOS=android GOARCH=amd64` (the musl-derived ccgo path), which is why the option-B first attempt (patching `libc_linux_amd64.go`) had no effect — that file is NOT in the android build set (`GOOS=android` ≠ `GOOS=linux` for filename constraints; verified via `go list`).

**Live gate PASS (rock-solid physical evidence, `.lava-ci-evidence/sixth-law-incidents/F3-fix-evidence/`):**
- `SIGSYS` lines in full logcat: **0** (was 3 — `syscall 6 (lstat)`, process died ~3s).
- api-app process death line: **none** (process stays alive).
- `/health` probe: **OK after 9s**, body `{"status":"alive"}` (real HTTP 200 from the embedded Go API on the x86_64 emulator).
- Embed booted: `[MdnsAdvertiser] Adding service name: Lava API, type: _lava-api-dev._tcp, port: 8443, engine=go-dev version=2.3.33 platform=android`.

All 4 Android ABIs compile clean (amd64 remap + arm64/arm/386 stub); host `internal/{storage,mobile,cache}` tests green with the remap (behavior intact). arm64 real devices were never affected (musl there emits `*at` natively). armeabi-v7a/x86 keep upstream behavior (stubbed; not x86_64-gate targets) — documented limitation.

---

## Option A (modernc/sqlite bump) — TRIED, VERIFIED INSUFFICIENT LIVE, REVERTED
- Bumped `modernc.org/sqlite v1.18.1 → v1.53.0` (libc `v1.17.1 → v1.73.4`). `internal/{storage,mobile,cache}` tests PASS; embed `cmd/lavaapi-cshared` builds; rebuilt x86_64 `liblavaapi.so` + api-app.
- **LIVE re-run on the emulator: STILL `SIGSYS`, STILL `syscall 6 (lstat)`, `rax=0x6`, `internal/runtime/syscall/linux.Syscall6`** (new .so BuildId 9c2c2e7f). The bump alone does NOT fix it.
- Reverted (also surfaced an UNRELATED pre-existing gap: `helixqa/pkg/ticket` imports `digital.vasic.llmorchestrator` with no `replace` directive → `go mod tidy` fails on that path — separate issue, not F3).

## CONFIRMED source (ruled out Go stdlib)
The custom `go1.26.2-X:nodwarf5` `os` package uses `fstatat` (`$GOROOT/src/syscall/syscall_linux.go:183`), so Go stdlib is NOT the source. The raw legacy syscalls are **`modernc.org/libc`'s own `libc_linux_amd64.go`** — it has NO android special-casing and issues 11 raw legacy path-syscalls (identical in v1.17.1 and v1.73.4):

| line | syscall | *at replacement (Android-safe, AT_FDCWD=-100) |
|---|---|---|
| 110 | `SYS_LSTAT` | `newfstatat(AT_FDCWD, path, buf, AT_SYMLINK_NOFOLLOW=0x100)` |
| 129 | `SYS_STAT` | `newfstatat(AT_FDCWD, path, buf, 0)` |
| 313 | `SYS_MKDIR` | `mkdirat(AT_FDCWD, path, mode)` |
| 345 | `SYS_CHMOD` | `fchmodat(AT_FDCWD, path, mode, 0)` |
| 377 | `SYS_UNLINK` | `unlinkat(AT_FDCWD, path, 0)` |
| 393 | `SYS_ACCESS` | `faccessat(AT_FDCWD, path, mode, 0)` |
| 412 | `SYS_RMDIR` | `unlinkat(AT_FDCWD, path, AT_REMOVEDIR=0x200)` |
| 428 | `SYS_RENAME` | `renameat(AT_FDCWD, old, AT_FDCWD, new)` |
| 454 | `SYS_CHOWN` | `fchownat(AT_FDCWD, path, owner, group, 0)` |
| 507 | `SYS_READLINK` | `readlinkat(AT_FDCWD, path, buf, bufsize)` |
| 540 | `SYS_OPEN` | `openat(AT_FDCWD, path, flags, mode)` |

arm64's `libc_linux_arm64.go` already uses the `*at` forms → that is why **real arm64 devices are unaffected**; only the x86_64 emulator crashes.

## FIX = option B (surgical, OWED)
`replace modernc.org/libc => <local fork>` in `lava-api-go/go.mod`; in the fork's `libc_linux_amd64.go` convert the 11 legacy syscalls above to their `*at` forms. Then: rebuild x86_64 embed (`build-cshared.sh`) → `:api-app:assembleDebug` → install on live emulator → `am start … START_API=true` → **ACCEPTANCE: no `SIGSYS` in logcat AND `/health` 200** + `go test ./internal/{storage,mobile,cache}` green. (Alternative option C: CGO `mattn/go-sqlite3` → bionic `*at`.)

## Interim: the goapi (host-API) path is GREEN today (Challenge70 archiveorg×story PASS). Only the on-device api-app embed path is blocked by this, on x86_64 emulators only.
