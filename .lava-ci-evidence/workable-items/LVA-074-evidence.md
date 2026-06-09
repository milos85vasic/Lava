# LVA-074 — Genymotion runner screen-wake before connectedDebugAndroidTest
runner: containers-submodule
runtime: genymotion-vm (Pixel 9 / API 35 / arm64 @ 127.0.0.1:6555; §6.AH VM path, §6.AG Containers-driven)
Commit 062720bd. A sleeping Genymotion VM screen idles the render pipeline → SurfaceFlinger
commit timeouts → spurious "No compose hierarchies found" Challenge failures (the app composes fine).
Live-proven: with the screen asleep C00/C01 RED; after wake+stay-on the core-flow gate
C00+C01+C07+C08 went GREEN on the same VM (evidence .lava-ci-evidence/genymotion/coreflow-gate-20260609T160325Z).
Incident 2026-06-09-genymotion-surface-render-timeout RESOLVED (class III — VM render state, not code).
