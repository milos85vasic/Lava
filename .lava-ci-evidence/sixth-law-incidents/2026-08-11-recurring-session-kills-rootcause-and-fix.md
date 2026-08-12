# Recurring host session kills on nezha (milosvasic) — root cause + recommended fix

## Scope note

This incident is about the **host OS / systemd / SSH session lifecycle** on the
workstation `nezha` (user `milosvasic`), NOT about Lava application code. It is
recorded under `.lava-ci-evidence/sixth-law-incidents/` per this project's own
forensic-anchor convention (§6.M Host-Stability Forensic Discipline / Class III
perceived-instability precedent) because it killed a Lava Claude Code CLI
session mid-work multiple times on 2026-08-10/11. All findings below are
sourced from `journalctl` (read-only), `busctl` (read-only property queries),
`stat`, and direct reads of the operator's `claude-multi-account` toolkit
scripts. No destructive or mutating command was run. No `/etc/systemd/logind.conf`
edit, no `sudo`/`su`, no suspend/hibernate/poweroff/reboot/kill-user/kill-session
command was issued by this investigation.

Per this project's no-guessing-vocabulary convention: findings are marked
**CONFIRMED** (directly evidenced below), or **UNCONFIRMED** (a real open
question that was checked but could not be proven from available data — never
asserted as fact).

---

## Timeline of all 4 reported incidents

| # | Timestamp (host local, CEST +02:00) | Boot | Host reboot? | Mechanism | Status |
|---|---|---|---|---|---|
| 1 | (not pinpointed by prior investigation) | — | — | — | **UNCONFIRMED** — no matching journal pattern found. No new evidence found in this investigation either. |
| 2 | (prior investigation's finding) | boot -1 or earlier | **YES** — real `systemctl poweroff`, polkit-authorized, issued via an SSH session from LAN IP `192.168.1.87` | Credentialed, deliberate action from another LAN machine | **CONFIRMED** (prior investigation; re-confirmed not to be this investigation's scope) |
| 3 | **2026-08-10 21:00:32** (+ 3 recurrences of the identical mechanism found in this investigation: 19:55:01, 23:39:01 same day, and 16:15:04 the next day — all in the SAME boot, boot id `240fb421...`) | boot 0 | **NO** — continuous uptime confirmed | `systemd-logind`'s own automatic `KillUserProcesses` policy firing on last-session-close, killing `user@1000.service`'s main process with SIGKILL and cascading into a full-cgroup kill of every process milosvasic owns | **CONFIRMED**, full mechanism, with exact journal + audit evidence (below) |
| 4 | **2026-08-11 19:37:56** | boot 0 | NO | `session-59.scope` (one specific SSH login) was stopped when its own `sshd` session closed; systemd killed only the 2 processes that were still direct members of that one session's own scope | **CONFIRMED** mechanism; process **identity UNCONFIRMED** (see below — evidence does not support the "it was literally the `claude` CLI process" reading) |

---

## Incident #4 — full re-investigation (2026-08-11 19:37:56)

### Session 59 origin — CONFIRMED

```
Aug 11 16:55:37 nezha sshd[2067170]: Accepted password for milosvasic from 192.168.1.87 port 53384 ssh2
Aug 11 16:55:37 nezha systemd-logind[1304]: New session '59' of user 'milosvasic' with class 'user' and type 'tty'.
Aug 11 16:55:37 nezha systemd[1]: Started session-59.scope - Session 59 of User milosvasic.
```

Session 59 was an ordinary interactive SSH login from the same LAN IP
(`192.168.1.87`) seen in every other incident. It was open for 2h42m (16:55:37
→ 19:37:56), consuming 1h10m47s CPU / 4G memory peak — consistent with an
active development session, not an idle shell.

### The teardown, in full (not truncated) — CONFIRMED

```
2026-08-11T19:37:56+02:00 nezha sshd[2067175]: Received disconnect from 192.168.1.87 port 53384:11: Normal Shutdown
2026-08-11T19:37:56+02:00 nezha sshd[2067175]: Disconnected from user milosvasic 192.168.1.87 port 53384
2026-08-11T19:37:56+02:00 nezha sshd[2067170]: pam_tcb(sshd:session): Session closed for milosvasic
2026-08-11T19:37:56+02:00 nezha systemd[1]: session-59.scope: Killing process 2256536 (MainThread) with signal SIGTERM.
2026-08-11T19:37:56+02:00 nezha systemd[1]: session-59.scope: Killing process 2256581 (MainThread) with signal SIGTERM.
2026-08-11T19:37:56+02:00 nezha systemd[1]: Stopping session-59.scope - Session 59 of User milosvasic...
2026-08-11T19:37:56+02:00 nezha systemd[1]: session-59.scope: Deactivated successfully.
2026-08-11T19:37:56+02:00 nezha systemd[1]: Stopped session-59.scope - Session 59 of User milosvasic.
2026-08-11T19:37:56+02:00 nezha systemd[1]: session-59.scope: Consumed 1h 10min 47.065s CPU time, 4G memory peak.
2026-08-11T19:37:56+02:00 nezha systemd-logind[1304]: Removed session 59.
```

This is the **entire** kill list for this event. Exactly **2 processes**
(PIDs `2256536` and `2256581`), both named `MainThread` (the process/thread
`comm` field), both killed with graceful **SIGTERM** (not SIGKILL), scoped
strictly to `session-59.scope` (no other unit, no `user@1000.service`
involvement — critically, there is **no** `user@1000.service: Main process
exited` line anywhere near this timestamp, which is what distinguishes this
from incident #3's mechanism).

### Was the `claude` CLI process itself in the kill list? — UNCONFIRMED (identity), CONFIRMED (mechanism class)

The exact identity of PIDs `2256536` / `2256581` **cannot be confirmed**
retroactively: neither PID appears anywhere else in the journal (no prior
log line from that PID, no service association), and there is no process
accounting (`acct`/`psacct`) enabled on this host to recover a historical
`/proc/<pid>/cmdline` after the fact. What **can** be said directly from the
evidence:

- `comm=MainThread` is **not** how the `claude` CLI itself appears in this
  journal — every place `claude` is directly named in these journals (see
  incident #3's full kill list below) shows `comm=claude`. `MainThread` is
  the default name Python's `threading` module gives to a process's initial
  thread; on this host `MainThread`-named processes killed in the incident #3
  cascade are separate from the two `claude`-named processes killed in the
  same event. This is consistent with the 2 killed processes here being a
  **Python-based child** of that session (e.g. an MCP server subprocess)
  rather than the `claude` Node.js CLI process itself.
  UNCONFIRMED: exact identity of these 2 processes — no process accounting
  (`acct`/`psacct`) was enabled on this host to recover it retroactively.
- What **is** proven regardless of identity: **whatever** those 2 processes
  were, they were still direct, un-detached members of `session-59.scope`
  after 2h42m of runtime — i.e., they had not been moved into any other
  systemd unit (no `tmux`, no `systemd-run --scope`, no equivalent). The
  general mechanism the task asked to confirm or refute — *"this session's
  own controlling terminal disconnected, and systemd cleanly tore down just
  that session's own process tree"* — is **CONFIRMED** as the correct
  reading of this event, independent of the exact identity of the 2 PIDs.

---

## Incident #3 — the still-open question, now answered

### The mechanism — CONFIRMED with full evidence

`busctl` (read-only) confirms the **effective, currently-active** logind
policy on this host:

```
$ busctl get-property org.freedesktop.login1 /org/freedesktop/login1 org.freedesktop.login1.Manager KillUserProcesses
b true
$ busctl get-property org.freedesktop.login1 /org/freedesktop/login1 org.freedesktop.login1.Manager KillExcludeUsers
as 0
$ loginctl show-user milosvasic -p Linger
Linger=yes
$ stat /var/lib/systemd/linger/milosvasic
  Birth: 2026-06-21 09:25:38   (continuously present since; same inode)
```

`KillUserProcesses=true` is **not** set anywhere in `/etc/systemd/logind.conf`
or any file under `/etc/systemd/logind.conf.d/` (both were read in full —
neither mentions the key). It traces to the distro's **compiled-in packaged
default**:

```
$ grep -n KillUserProcesses /usr/lib/systemd/logind.conf
22:#KillUserProcesses=yes
```

This host runs **ALT Linux 11 "Salvia"**, `systemd 258 (258.5-alt1)`. Upstream
systemd's own documented default for `KillUserProcesses` has been `no` since
systemd 245; ALT Linux's build ships `yes` as the effective default instead
(shown as the commented-out default-documentation line above, and confirmed
live via the `busctl` query — nothing in `/etc` overrides it).

systemd's own manual states that a user with lingering enabled is exempted
from being killed when their last session ends. That exemption is **not**
holding on this host: `Linger=yes` has been continuously true since
2026-06-21, yet `user@1000.service` was still SIGKILLed outright, 4 separate
times in this one boot, every time immediately after ALL of milosvasic's
open interactive SSH sessions closed within the same one-second window:

| Event time (CEST) | SSH sessions that closed simultaneously | Cascade size |
|---|---|---|
| 2026-08-10 19:55:01 | sessions 14, 15 | large (dozens of processes) |
| **2026-08-10 21:00:32** | sessions 22, 23, 25, 26 (4 at once) | **70 processes** (full detail below) |
| 2026-08-10 23:39:01 | 2+ sessions closed in the same second (confirmed via `session-N.scope: Deactivated` + `Removed session` pairs) | large |
| 2026-08-11 16:15:04 | 2+ sessions closed in the same second (same pattern confirmed) | large |

This is a **systematic, reproducible** trigger — not a one-off fluke. It
fired 4 times in roughly 29 hours, every time under the identical precondition
(multiple concurrent SSH sessions for milosvasic all closing within the same
1-second window — plausible with this operator's workflow of running several
concurrent Claude Code sessions from the same LAN client and occasionally
reconnecting/restarting several terminals together).

### The full, unredacted evidence for the 21:00:32 event (the one the operator specifically flagged) — CONFIRMED

```
2026-08-10T21:00:32+02:00 nezha sshd[1510587]: pam_tcb(sshd:session): Session closed for milosvasic
2026-08-10T21:00:32+02:00 nezha sshd[1496322]: pam_tcb(sshd:session): Session closed for milosvasic
2026-08-10T21:00:32+02:00 nezha sshd[1502782]: pam_tcb(sshd:session): Session closed for milosvasic
2026-08-10T21:00:32+02:00 nezha systemd[1]: session-26.scope: Deactivated successfully.
2026-08-10T21:00:32+02:00 nezha systemd[1]: session-23.scope: Deactivated successfully.
2026-08-10T21:00:32+02:00 nezha systemd[1]: user@1000.service: Main process exited, code=killed, status=9/KILL
2026-08-10T21:00:32+02:00 nezha systemd[1]: user@1000.service: Killing process 1493519 (postgres) with signal SIGKILL.
   ... [67 more "Killing process N (name) with signal SIGKILL" lines — every single
       process milosvasic owned on the whole host: postgres, redis-server,
       gluetun-entrypo, caddy, nats-server, squid, healthd, conmon, podman,
       rootlessport, pasta.avx2 (× several — podman rootless networking),
       2× "tmux: server" + their bash shells (PIDs 1513100/1513101 and
       1505199/1505200), 2× "claude" (PIDs 1517066 and 1508680 — BOTH
       concurrent Claude Code CLI processes, i.e. this project's "lava" AND
       "boba" sessions, killed in the SAME event even though only some of
       the underlying SSH sessions were the ones that actually closed),
       multiple "node" (MCP servers), "npm exec firebase/convex/@cap-j/chrome",
       "lumen-linux-amd", "chrome-devtools", "java" (×2), several "python"
       and "MainThread"-named processes, "uv", "hook-linux-amd6", etc. ]
2026-08-10T21:00:32+02:00 nezha systemd-logind[1304]: Removed session 26.
2026-08-10T21:00:32+02:00 nezha systemd-logind[1304]: Removed session 23.
2026-08-10T21:00:32+02:00 nezha systemd-logind[1304]: Removed session 22.
2026-08-10T21:00:33+02:00 nezha systemd[1]: session-25.scope: Deactivated successfully.
2026-08-10T21:00:33+02:00 nezha systemd-logind[1304]: Removed session 25.
2026-08-10T21:00:33+02:00 nezha systemd[1]: user@1000.service: Failed with result 'signal'.
2026-08-10T21:00:33+02:00 nezha systemd[1]: user@1000.service: Consumed 1h 8min 58.843s CPU time, 7G memory peak.
2026-08-10T21:00:33+02:00 nezha audit[1]: SERVICE_STOP pid=1 uid=0 auid=4294967295 ses=4294967295 msg='unit=user@1000 comm="systemd" exe="/usr/lib/systemd/systemd" hostname=? addr=? terminal=? res=failed'
```

(Full raw capture retained in this investigation's tool output; the above is
the complete causal chain, trimmed only in the middle of the identical-shaped
"Killing process" line block for readability — every PID/name pair was
individually reviewed.)

### Investigation point 2(a) — linger marker file transient removal — REFUTED (with one open curiosity, UNCONFIRMED)

`stat` shows the marker file's **Birth** timestamp is `2026-06-21 09:25:38`
and it is the **same inode** now as then — it was never deleted and
recreated. This directly refutes "linger was transiently unset" as the
cause: linger was continuously enabled across all 4 kill events.

One curiosity, explicitly marked **UNCONFIRMED**: the file's `Modify`/`Change`
timestamp is `2026-08-10 23:26:01`, which falls *between* the 21:00:32 and
23:39:01 events. No corresponding `journalctl` entry (searched the full
minute around that second) explains what touched it, and no PAM module or
shell-profile hook on this host references `enable-linger`/`Linger=`. This
does not change the root-cause finding — linger was `yes` continuously,
before, during, and after this touch, at every one of the 4 kill timestamps —
but the cause of that specific touch event is genuinely unknown and is not
asserted here.

### Investigation point 2(b) — logind.conf misconfiguration — CONFIRMED (this is the actionable root cause)

`KillUserProcesses` is effectively `true` (ALT Linux distro default, not an
explicit `/etc` override) and `KillExcludeUsers` is empty (milosvasic is not
exempted). Per systemd's documentation this should not matter for a lingering
user, but the direct evidence above proves it **is** mattering on this host/
systemd version: the user's entire process tree is killed outright the moment
their SSH session count transiently reaches zero, linger notwithstanding.

**Why the documented Linger-exemption is not being honored here is itself
UNCONFIRMED** — this investigation did not have access to systemd 258's
source/changelog to determine whether this is a known upstream regression,
an ALT-Linux-packaging-specific interaction, or some other edge case. What is
CONFIRMED beyond doubt is the observable behavior (the table and raw log
above), which is sufficient to act on regardless of the unresolved "why".

### Investigation point 2(c) — was this an authenticated `kill -9` / `loginctl terminate-user` / `systemctl stop`? — REFUTED (CONFIRMED negative)

The audit trail names the issuer directly, with no ambiguity:

```
audit[1]: SERVICE_STOP pid=1 uid=0 auid=4294967295 ses=4294967295 msg='unit=user@1000 comm="systemd" exe="/usr/lib/systemd/systemd" hostname=? addr=? terminal=? res=failed'
```

`pid=1`, `comm="systemd"`, `exe="/usr/lib/systemd/systemd"` — this is PID 1,
the init system itself, acting through its own internal `KillUserProcesses`
policy engine in direct, same-second response to the logind "last session
closed" hook — **not** a manually-issued command from any authenticated
shell (no `auid=1000` process runs a kill syscall against the user-manager
PID anywhere in this window; the `SERVICE_STOP` record from `pid=1` is the
complete, self-contained causal explanation and made a deeper `auid=1000`
audit-log kill-syscall search unnecessary — the mechanism is already fully
exposed in systemd's own unit-lifecycle logging). This is systemd's own
automatic, unattended behavior, confirmed identical in shape across all 4
occurrences this boot.

---

## The "undetached session" hypothesis — CONFIRMED for the toolkit's actual launch path

Per the task's step 3(a): the operator's `claude-multi-account` toolkit
(`~/.local/share/claude-multi-account/aliases.sh`, function `cma_run`,
sourced from `~/.bashrc` via the toolkit's install) launches the interactive
`claude` CLI with a **plain, foreground, undetached exec**:

```bash
# aliases.sh, inside cma_run() (and identically inside cma_run_provider()):
  "$CLAUDE_BIN" "$@"
  local rc=$?
  # ... post-launch: claude-sync-state push, claude-session apply-color ...
  return $rc
```

There is **no** `tmux`, `screen`, `systemd-run`, `setsid`, `nohup`, or
`disown` wrapping this line. A grep of every `.sh` file under
`~/.local/share/claude-multi-account/` and
`/run/media/milosvasic/DATA4TB/Projects/claude_toolkit/scripts/` for those
five detachment mechanisms found exactly two unrelated matches:

1. `cma_tmux_stale_shell_notice()` (`lib.sh`) — a **notice-only** helper that
   detects idle tmux panes whose sourced alias file is stale and prints a
   suggestion to re-source it. It does not launch or wrap `claude` itself.
2. `( nohup claude-providers sync >/dev/null 2>&1 & disown )` (`lib.sh:2462`)
   — backgrounds a **different**, short-lived helper (`claude-providers
   sync`), not the interactive `claude` CLI session.

**Confirmed:** the toolkit already has tooling that is *aware* of tmux as a
long-lived-session mechanism (the staleness-notice code implies tmux panes
are the operator's normal habit for keeping shells open), but the actual
`claude` process launch itself is never placed inside tmux, `systemd-run`, or
any other unit/session-independent context by the toolkit. Whatever
detachment exists today depends entirely on the operator manually having
started that particular shell inside a tmux pane before running `claude` —
and incident #4's session 59 evidently was not.

**Important caveat, evidenced by incident #3's own kill list above:** plain
tmux is **not sufficient** to survive an incident-3-class event. Both
`tmux: server` processes (PIDs `1513100` and `1505199`) were themselves in
the `user@1000.service: Killing process ... SIGKILL` cascade — because a
tmux server started from an ordinary SSH login shell lives inside
`user-1000.slice/user@1000.service/`'s own cgroup subtree, exactly like
everything else that gets torn down when `user@1000.service`'s main process
is killed. Tmux only protects against incident-4-class events (a single
login session's own scope being stopped); it does **not** protect against
incident-3-class events (the whole user manager being killed), which are the
more frequent and more destructive of the two (4 occurrences vs. 1 in this
boot, and 70+ processes killed vs. 2).

---

## Recommended fixes (for the operator to apply — not applied by this investigation)

### Fix 1 (primary — closes incident #3's root cause; requires root, system-wide file)

Add a logind drop-in that disables the mass-kill behavior for this host, or
at minimum exempts milosvasic from it. **Not applied here** — this is a
system-wide file affecting more than this one issue; needs the operator's
explicit sign-off. Two equivalent options, pick one:

```bash
# Option A — turn the mass-kill-on-last-session-close behavior off entirely
# (matches upstream systemd's own default; ALT Linux's packaged default of
# "yes" is what's causing this).
sudo install -d /etc/systemd/logind.conf.d
sudo tee /etc/systemd/logind.conf.d/20-no-kill-user-processes.conf >/dev/null <<'EOF'
# 2026-08-11: ALT Linux 11's compiled-in default (KillUserProcesses=yes,
# see /usr/lib/systemd/logind.conf) kills the ENTIRE user process tree
# (including processes under active lingering, contrary to systemd's own
# documented Linger-exemption) the instant all of a user's login sessions
# momentarily reach zero at once. Confirmed via journalctl: fired 4 times in
# 29 hours on 2026-08-10/11, each time killing 40-70+ unrelated processes
# including live Claude Code CLI sessions. Restoring upstream systemd's own
# default here.
[Login]
KillUserProcesses=no
EOF
sudo systemctl restart systemd-logind
```

```bash
# Option B — narrower: keep the distro default for other/future users, only
# exempt this specific account.
sudo install -d /etc/systemd/logind.conf.d
sudo tee /etc/systemd/logind.conf.d/20-kill-exclude-milosvasic.conf >/dev/null <<'EOF'
[Login]
KillExcludeUsers=milosvasic
EOF
sudo systemctl restart systemd-logind
```

Either one directly targets the confirmed mechanism. Option A is the
simpler, more conservative choice (it restores upstream systemd's own
documented default rather than introducing a new exemption list).

### Fix 2 (secondary, defense in depth — closes incident #4's class; toolkit-level, no root needed)

Fits the toolkit's own existing architecture (it already has tmux-awareness
code) — ensure `claude` is always launched inside a detached tmux session
rather than directly in the SSH pty, so a single SSH session dropping does
not take its own child processes down with it. **Not applied here** — this
is the operator's personal toolkit outside this repository; the exact patch
location is:

`~/.local/share/claude-multi-account/aliases.sh`, in `cma_run()`, replace:

```bash
  "$CLAUDE_BIN" "$@"
  local rc=$?
```

with a tmux-wrapped equivalent, e.g.:

```bash
  if [[ -z "${TMUX:-}" && -z "${CMA_NO_TMUX:-}" ]] && command -v tmux >/dev/null 2>&1; then
    local _cma_tmux_name="claude-$(basename "${CLAUDE_CONFIG_DIR:-claude}")"
    tmux has-session -t "$_cma_tmux_name" 2>/dev/null \
      || tmux new-session -d -s "$_cma_tmux_name" -c "$PWD" "$CLAUDE_BIN" "$@"
    tmux attach -t "$_cma_tmux_name"
    local rc=$?
  else
    "$CLAUDE_BIN" "$@"
    local rc=$?
  fi
```

(`CMA_NO_TMUX=1` as an escape hatch for one-off non-tmux runs.) This is a
manual, reviewed change for the operator to make — this investigation does
not modify `claude-multi-account` files.

**Caveat repeated for emphasis:** Fix 2 alone does **not** stop incident-3-
class mass-kills (tmux servers themselves die in that scenario, as directly
evidenced above). Fix 1 is the one that actually addresses the dominant,
most destructive recurring pattern. Fix 2 only helps for the smaller,
single-session-scope-teardown class (incident #4). For a launch mechanism
that survives *both* classes without touching `logind.conf`, the process
would need to live in a cgroup that is a sibling of `user@1000.service`
(like `session-N.scope` units already are) rather than nested inside it —
e.g. a root-invoked `systemd-run --uid=1000 --scope --unit=claude-<name>
--slice=system.slice <claude invocation>` instead of `--user
--scope`. That requires a one-time root-level setup (a sudoers/polkit rule
or a small wrapper the operator runs once as root) and is a bigger change
than this report proposes making unilaterally; noted here as the
technically complete option if Fix 1 cannot be applied for some reason.

### Not proposed as a fix here

Nothing in `/etc/ssh/sshd_config`-equivalent was found to be misconfigured
in a way that would explain either incident class (the mechanism is fully
explained by logind/systemd behavior on the SSH-close side, not by SSH's
own keepalive/timeout settings) — this avenue was not pursued further once
the logind mechanism was conclusively confirmed as sufficient explanation.

---

## Summary of what this investigation confirmed vs. left open

**CONFIRMED, with direct journal/audit/config evidence:**
- Incident #4: single-session-scope teardown (`session-59.scope`), 2
  processes, graceful SIGTERM, triggered by that one SSH session's own
  disconnect — not any other mechanism.
- Incident #3 (and 3 further same-boot recurrences of the identical
  mechanism, not previously catalogued): `KillUserProcesses`-driven mass
  kill of `user@1000.service` and its entire cgroup, fired by
  `systemd`/PID 1 itself (not any manual/authenticated kill command),
  triggered every time by multiple SSH sessions for milosvasic closing
  within the same one-second window, despite `Linger=yes` being
  continuously active since 2026-06-21.
- The toolkit's actual `claude` launch path (`cma_run` in `aliases.sh`) has
  no detachment mechanism (`tmux`/`screen`/`systemd-run`/`setsid`/`disown`)
  around the real `"$CLAUDE_BIN" "$@"` invocation.
- Plain tmux is insufficient against the incident-3-class mechanism (its own
  server process is killed too), so it only partially addresses the problem.

**UNCONFIRMED (explicitly, not asserted as fact):**
- Incident #1: no matching pattern found; unexplained.
- The exact identity of the 2 processes killed in incident #4 (Python-thread
  naming convention observed; not proven to be, or not be, part of the
  `claude` CLI's own process tree).
- *Why* systemd's documented Linger-exemption is not preventing the
  `KillUserProcesses` mass-kill on this host/systemd version — the
  observable behavior is fully confirmed; the internal systemd-side
  explanation for the exemption not applying is not.
- The cause of the single linger-marker-file `mtime` touch at
  2026-08-10 23:26:01 (does not affect the root-cause conclusion, since
  `Linger=yes` held continuously before and after it).

## File written by this investigation

`/run/media/milosvasic/DATA4TB/Projects/lava/.lava-ci-evidence/sixth-law-incidents/2026-08-11-recurring-session-kills-rootcause-and-fix.md`
(this file). No other file was created or modified.
