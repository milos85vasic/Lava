# Incident — Host Poweroff During SP-2 Implementation (2026-04-28 18:37)

> **Status:** investigated, root cause confirmed external to this codebase, hardening codified.
> **Severity:** medium — one in-flight Claude Code session lost; all five completed SP-2 phases were preserved by the commit-and-push-after-every-phase discipline.
> **Affected scope:** the developer workstation `nezha` (Linux 6.12.61-6.12-alt1, ALT Linux). No Lava-API services were running in production at the time.

## Timeline

| Time (UTC+3) | Event |
|---|---|
| 18:37:14 | `gnome-shell[754617]: Shutting down GNOME Shell` |
| 18:37:15 | `Reached target gnome-session-shutdown.target - Shutdown running GNOME Session` |
| 18:37:25 | `Reached target shutdown.target - Shutdown` |
| 18:37:55 | `Stopping session-19.scope - Session 19 of User milosvasic` (the session running this Claude Code instance) |
| 18:37:55 | `Starting plymouth-poweroff.service - Show Plymouth Power Off Screen` |
| 18:37:56 | systemd-poweroff.service start job dispatched, all services SIGTERM'd |
| 18:37:57 | `Reached target shutdown.target` (final) — system off |
| 18:45:08 | `kernel: Low-power S0 idle used by default for system suspend` (informational, on next boot) |
| 18:45 | System back up, GNOME login screen restored |

## Forensic findings

### What this WAS

A **user-space-initiated graceful poweroff** triggered at the GNOME Shell layer (process `gnome-shell[754617]`), propagated through the standard systemd shutdown sequence (`gnome-session-shutdown.target` → `shutdown.target` → `plymouth-poweroff.service` → `systemd-poweroff.service`). The teardown was orderly: every long-running service received SIGTERM and exited cleanly within ~40 seconds.

### What this was NOT

- **NOT a suspend / hibernate / hybrid-sleep.** No `org.freedesktop.login1.Manager.Suspend` D-Bus call appears in the journal in the relevant window, and no `systemd-suspend.service` entry was logged. The hardening rules forbidding suspend remain effective.
- **NOT a kernel panic / oops / hardlockup / softlockup / NMI.** `journalctl -k` between 18:30 and 18:45 shows zero entries matching `panic|oops|bug|stuck|hung task|cpu#.*lockup|softlockup|hardlockup|nmi`.
- **NOT an OOM kill.** System memory at the time of forensics: 9.9 GB used of 62 GB total. No `out of memory|killed process|invoked oom-killer|memory cgroup out of memory` entries in the relevant window.
- **NOT a thermal event.** No `thermal` entries in `dmesg`. No watchdog timeouts.
- **NOT a disk-pressure event.** `/run/media/milosvasic/DATA4TB` was at 38% utilization (1.4 TB of 3.7 TB).
- **NOT triggered by any command issued in this session by the assistant or any subagent.** Verified by `grep -rE 'systemctl (suspend|hibernate|poweroff|reboot|halt|kill)|loginctl (suspend|hibernate|poweroff|kill-session)|pm-suspend|pm-hibernate|^shutdown |dbus-send.*Suspend|dbus-send.*Hibernate|busctl.*Suspend|gsettings set.*sleep-inactive'` over every script under `lava-api-go/scripts/`, `scripts/`, `start.sh`, `stop.sh` — **zero matches**.

### Most probable root cause (external to this codebase)

A **manual GNOME "Power Off" click** by the operator, OR a **physical power-button press**, OR an out-of-scope scheduled task. All three trigger the exact GNOME-Shell-initiated shutdown sequence observed in the journal. This is consistent with the user's own report that they were uncertain whether it was "suspended / sent to standby / hybernated or signed out".

### Why no work was lost

Every completed SP-2 phase (0 through 5) had been **committed and pushed to all four upstreams** (github + gitflic + gitlab + gitverse) before the poweroff. After the system came back up, `git ls-remote` against each upstream showed master at `4df0e82` — the exact commit produced by the last successful phase. The commit-and-push-per-phase discipline encoded in the SP-2 plan's mirror-policy paragraph is what made this incident a non-event for the work output.

## Hardening applied

The following rules are codified into root `CLAUDE.md`, root `AGENTS.md`, and the constitutional documents (CLAUDE.md / AGENTS.md / CONSTITUTION.md) of every `vasic-digital` submodule we own:

### Explicit forbidden-command list (Host Machine Stability — STRENGTHENED)

The pre-existing constitutional rule "IT IS FORBIDDEN to directly or indirectly cause the host machine to suspend, hibernate, sign out the user, or terminate the session" remains binding. Strengthened with an explicit, verifiable list of forbidden invocations:

```
systemctl  {suspend, hibernate, hybrid-sleep, suspend-then-hibernate,
            poweroff, halt, reboot, kexec, kill-user, kill-session}
loginctl   {suspend, hibernate, hybrid-sleep, suspend-then-hibernate,
            poweroff, halt, reboot, kill-user, kill-session, terminate-user, terminate-session}
pm-suspend  pm-hibernate  pm-suspend-hybrid
shutdown   {-h, -r, -P, -H, now, --halt, --poweroff, --reboot}
dbus-send / busctl  →  org.freedesktop.login1.Manager.{Suspend, Hibernate, HybridSleep,
                                                       SuspendThenHibernate, PowerOff, Reboot}
dbus-send / busctl  →  org.freedesktop.UPower.{Suspend, Hibernate, HybridSleep}
gsettings set       →  *.power.sleep-inactive-{ac,battery}-type set to anything except 'nothing' or 'blank'
gsettings set       →  *.power.power-button-action  set to anything except 'nothing' or 'interactive'
```

Any of these in any committed script, any subagent's planned action, or any agent's tool call is a **constitutional violation** and a **release blocker**.

### Verification commands

Before any push to any upstream, the following grep MUST return empty (a CI hook in `lava-api-go/scripts/ci.sh` enforces this for the lava-api-go service tree, and a parallel pre-push hook applies to the rest):

```bash
git ls-files -z | xargs -0 grep -lE \
  'systemctl[[:space:]]+(suspend|hibernate|hybrid-sleep|suspend-then-hibernate|poweroff|halt|reboot|kexec|kill-user|kill-session)|loginctl[[:space:]]+(suspend|hibernate|hybrid-sleep|suspend-then-hibernate|poweroff|halt|reboot|kill-user|kill-session|terminate-user|terminate-session)|pm-(suspend|hibernate|suspend-hybrid)|^[[:space:]]*shutdown[[:space:]]|dbus-send.*org\.freedesktop\.(login1\.Manager|UPower)\.(Suspend|Hibernate|HybridSleep|SuspendThenHibernate|PowerOff|Reboot)|busctl.*org\.freedesktop\.(login1\.Manager|UPower)\.(Suspend|Hibernate|HybridSleep|SuspendThenHibernate|PowerOff|Reboot)|gsettings[[:space:]]+set.*sleep-inactive-(ac|battery)-type|gsettings[[:space:]]+set.*power-button-action' \
  2>/dev/null
```

### Recovery discipline (in case a host power event happens despite the rule)

The procedure that worked for this incident:

1. **Verify state**: `git status` clean? `git log -1` matches the last expected commit?
2. **Verify mirror integrity**: `for r in github gitflic gitlab gitverse; do git ls-remote $r refs/heads/master | awk '{print $1}'; done` — all four MUST agree on the same commit.
3. **Inspect for leaked containers**: `podman ps -a` (and `docker ps -a` if applicable). Stop and remove any with names matching project conventions (e.g. `lava-*`, `vasic-*-pg-test-*`).
4. **Inspect for leaked images / volumes**: `podman images` / `podman volume ls`. Remove project-tagged images so the next `start.sh` rebuilds from current code.
5. **Verify resource pressure was not the cause**: `free -h`, `df -h`, `journalctl -k | grep -iE 'oom|panic|thermal'`. If any of these show signs of stress, the next session should bound parallelism more tightly (`GOMAXPROCS=2`, `nice -n 19`, `ionice -c 3`).
6. **Resume work** at the last committed phase boundary. No replay of completed phases is needed.

### Docker / Podman analysis

The incident was NOT caused by container runtimes. However, the investigation surfaced operational rules worth recording:

- **Rootless podman containers do not, by themselves, hold a logind session open.** The current `start.sh`/`tools/lava-containers/` workflow uses host-network containers under the user's session — when the user session ends (poweroff/logout/etc.), the containers receive SIGTERM via their parent's session-scope teardown. This is the desired behavior; do not change it without re-evaluating the failure mode.
- **`podman run --rm`** is the right discipline for transient test containers. Combined with shell `trap '<cleanup>' EXIT INT TERM HUP` patterns (already in `lava-api-go/scripts/run-test-pg.sh` and `submodules/cache/scripts/run-postgres-test.sh`), leaked containers from interrupted test runs are minimised.
- **Long-running containers from previous sessions** can leak if the user issues a hard kill of the controlling shell (kill -9 on the parent) instead of a graceful exit. The recovery discipline above includes the standard `podman ps -a` cleanup step for exactly this case.
- **Gradle daemon** is JVM-resident and survives session restart. This is a feature (faster subsequent builds) but it does mean memory accumulated by long-running gradle work persists; bound it with `--no-daemon` for unattended / long-running jobs.

## Anti-Bluff Testing Pact (re-affirmation)

Independent of the host-poweroff incident, the operator re-emphasised that the Anti-Bluff Testing Pact (Sixth Law — Real User Verification) MUST be present in the constitutional documents of every submodule and the main project. This document records the audit performed:

| Submodule | CLAUDE.md | AGENTS.md | CONSTITUTION.md |
|---|---|---|---|
| Auth | Pact added 2026-04-28 | Pact added 2026-04-28 | Pact added 2026-04-28 |
| Cache | Pre-existing | Pre-existing | Pre-existing |
| Challenges | Pre-existing | Pre-existing | Pre-existing |
| Config | Pact added 2026-04-28 | Pact added 2026-04-28 | Pact added 2026-04-28 |
| Containers | Pre-existing | Pre-existing | Pre-existing |
| Database | Pact added 2026-04-28 | Pact added 2026-04-28 | Pact added 2026-04-28 |
| Discovery | Pact + Host-Power added 2026-04-28 | Pact + Host-Power added 2026-04-28 | Pact + Host-Power added 2026-04-28 |
| HTTP3 | Pre-existing (Sixth Law inheritance); Host-Power added | Pre-existing; Host-Power added | Pre-existing; Host-Power added |
| Mdns | Pre-existing (Sixth Law inheritance); Host-Power added | Pre-existing; Host-Power added | Pre-existing; Host-Power added |
| Middleware | Pact added 2026-04-28 | Pact added 2026-04-28 | Pact added 2026-04-28 |
| Observability | Pact added 2026-04-28 | Pact added 2026-04-28 | Pact added 2026-04-28 |
| RateLimiter | Pact added 2026-04-28 | Pact added 2026-04-28 | Pact added 2026-04-28 |
| Recovery | Pact added 2026-04-28 | Pact added 2026-04-28 | Pact added 2026-04-28 |
| Security | Pre-existing | Pre-existing | Pre-existing |

The pact text is identical to the Sixth Law in root `/CLAUDE.md`. Submodule documents reference it inline (so each submodule is self-contained and readers don't need to chase external pointers).
