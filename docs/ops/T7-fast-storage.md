# T7 Fast Storage — relocation map (operator-designated main fast storage)

**Operator directive (2026-05-31):** "Use for any storage-demanding work T7 drive
and update paths references to it since this is our main fast storage."

T7 = Samsung T7 external SSD mounted at `/Volumes/T7` (1.8 TiB, ~1.2 TiB free).
It is the project's designated **main fast storage** for all storage-demanding
work (container image builds, emulator system-images/AVDs, build caches).

## Relocations in effect

| Concern | Host path | Target on T7 | Mechanism |
|---------|-----------|--------------|-----------|
| Podman VM + image storage | `~/.local/share/containers` | `/Volumes/T7/containers` | symlink |
| Gradle home | `~/.gradle` (via `GRADLE_USER_HOME`) | `/Volumes/T7/Gradle` | env var (`.zshrc`) |
| XDG cache | `~/.cache` | `/Volumes/T7/cache` | symlink |
| Android `.android` (AVDs, 24 GB) | `~/.android` | `/Volumes/T7/dot-android` | symlink |

## Podman VM (the §6.M Class-II disk-pressure fix, 2026-05-31)

The podman `applehv` machine stores its whole VM as one fixed-size `.raw`
disk image under the graphroot. Originally 50 GB and living on the host
data-volume (which had dropped to 16 GiB free / 97%); the lava-api-go OCI
image build filled it and `build_and_release.sh` exited 125 "no space left
on device" (incident `.lava-ci-evidence/sixth-law-incidents/2026-05-31-disk-pressure-podman-vm-image-build.json`).

Fix:
1. `podman machine stop`
2. `mv ~/.local/share/containers /Volumes/T7/containers`
3. `ln -s /Volumes/T7/containers ~/.local/share/containers`
4. `podman machine start`

**Verified result:** host data-volume freed ~50 GB (16 GiB → 66 GiB free). After
the move + restart, the live VM reported `/dev/vda4 50G total / 41G free` (the move
to a clean graphroot on T7 plus a `podman system prune` is what reclaimed the
working room) — and `build_and_release.sh` then completed EXIT 0 including the
lava-api-go OCI image build that previously failed at `COPY . .`.

**UNCONFIRMED:** `podman machine set --disk-size 150` was issued and returned 0,
but the running VM still reported a 50 GB root afterward — on `applehv` the
disk-size change does not appear to apply to the already-provisioned `.raw` without
a machine recreate. The 50 GB → 41 GB-free headroom on T7 was sufficient for this
build; if a future build needs more, recreate the machine (`podman machine rm` +
`podman machine init --disk-size N`) so the larger raw is provisioned fresh on T7.

## Caveat (§6.M)

These relocations make T7 a hard dependency for container + emulator work: if
T7 is unmounted, podman + the AVD matrix break until it is remounted. This is
the operator's deliberate choice (T7 is the designated main fast storage). The
pre-push hook + `scripts/run-challenge-matrix.sh` continue to work host-side;
only the container/emulator runtime needs T7 present.

`Classification:` project-specific (Lava's host storage layout).
