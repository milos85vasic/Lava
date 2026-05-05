# Firebase scripts — quick reference

| Script | Purpose | Typical use |
|---|---|---|
| `scripts/firebase-env.sh` | Sourced by other scripts to load Firebase keys from `.env`. | `source scripts/firebase-env.sh` |
| `scripts/firebase-setup.sh` | One-time Firebase project bootstrap; refreshes `app/google-services.json` + `lava-api-go/firebase-web-config.json`; invites testers. | First clone after `firebase login:ci` |
| `scripts/firebase-distribute.sh` | Uploads built APKs to Firebase App Distribution + invites testers from `.env`. | After `./build_and_release.sh` |
| `scripts/firebase-stats.sh` | Prints dashboard URLs + machine-readable summary of Firebase services. | Operator inspection |
| `scripts/distribute.sh` | Umbrella: rebuilds artifacts + distributes via Firebase + prints stats. | One-command operator distribute |

## Examples

### First-time setup on a fresh clone

```bash
# 1. Authenticate Firebase CLI
firebase login:ci          # copy the token into .env as LAVA_FIREBASE_TOKEN

# 2. Copy template + fill in real values
cp .env.example .env
$EDITOR .env

# 3. Refresh Firebase config files + invite testers
./scripts/firebase-setup.sh
```

### Distribute a new build to testers

```bash
# Rebuild + upload + invite — one command
./scripts/distribute.sh

# Skip rebuild (use the artifacts already in releases/<version>/)
./scripts/distribute.sh --no-rebuild

# Distribute only the release-signed APK
./scripts/distribute.sh --release-only

# Provide custom release notes
./scripts/firebase-distribute.sh --release-notes "Hotfix: API_VERSION drift caught + fixed"
```

### Inspect Firebase health / stats

```bash
./scripts/firebase-stats.sh                   # human-readable, last 7 days
./scripts/firebase-stats.sh --days 30         # last 30 days
./scripts/firebase-stats.sh --json            # machine-readable
```

### Refresh config files without re-inviting testers

```bash
./scripts/firebase-setup.sh --refresh
```

### Re-invite testers without touching config files

```bash
./scripts/firebase-setup.sh --invite-only
```

## Constitutional bindings (per `CLAUDE.md`)

- **§6.H Credential Security** — `LAVA_FIREBASE_TOKEN` and `firebase-admin-key.json` are gitignored; pre-push hooks reject pushes that introduce them.
- **§6.J Anti-Bluff** — every script uses `set -euo pipefail`; there is no WARN-swallow pattern; failures propagate.
- **§6.K Builds-Inside-Containers** — `build_and_release.sh` invocations from `distribute.sh` route through the container path for release builds.

See `docs/FIREBASE.md` for the full integration architecture.
