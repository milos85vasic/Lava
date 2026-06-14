# Issue Challenge Video Analysis — Frame-Level Verification

| | |
|---|---|
| Revision | 1 |
| Created | 2026-06-14 |
| Last modified | 2026-06-14 |
| Status | active |

## Table of contents

- [Scope](#scope)
- [Method](#method)
- [Summary verdict table](#summary-verdict-table)
- [Per-video evidence](#per-video-evidence)
- [Conclusion](#conclusion)

## Scope

Operator request: "validate and verify what is happening on recorded video
materials." Four delivered issue Challenge recordings in the host user's
Downloads were analyzed at the frame level to answer ONE question per video:
**does this video contain real, changing on-screen content (live playback),
or is it a frozen / single-frame / near-empty capture?**

The four files:

- `/Users/milosvasic/Downloads/issue1-onboarding-select-all-providers.mp4`
- `/Users/milosvasic/Downloads/issue2-onboarding-password-masking-eye.mp4`
- `/Users/milosvasic/Downloads/issue3-api-search-auth-not-something-went-wrong.mp4`
- `/Users/milosvasic/Downloads/issue4-settings-server-list-no-duplicate.mp4`

## Method

Two independent analysis paths were used; BOTH ran, and they AGREE.

1. **HelixQA `cmd/recording-analyzer` `--post-analyze` (§11.4.107 post-hoc
   liveness correlator).** Built from source at
   `submodules/helixqa/cmd/recording-analyzer` with Go 1.26.2
   (`GOMAXPROCS=2 nice -n 19 go build`), binary at `/tmp/recording-analyzer`.
   The analyzer's OCR mode requires Tesseract/Whisper containers (not
   available here), but `--post-analyze` is a pure-ffprobe freeze/SSIM +
   decoded-frame-count oracle that needs no containers. This is the analyzer
   path appropriate for a liveness verdict. Verbatim per-video output is
   captured below. **The analyzer ran for real and returned FAIL (exit 1)
   on all four videos.**

2. **Local ffprobe + ffmpeg ground truth (always run regardless).**
   - `ffprobe -count_frames` for the authoritative decoded-frame count + duration.
   - `ffmpeg -vf fps=1` frame extraction to `/tmp/lava_frames/`, then a
     per-neighbour frame-size delta liveness signal: count how many adjacent
     extracted-frame pairs differ in PNG size by >2% (a frozen/static screen
     produces near-identical encoded frame sizes; real changing content does not).

ffprobe 8.1.1, ffmpeg 8.1.1 (Homebrew). No frames or binaries are committed —
only this report.

## Summary verdict table

| Video | Duration | Decoded frames | Analyzer (§11.4.107) | Local liveness (>2% frame-pair delta) | Verdict |
|---|---|---|---|---|---|
| issue1-onboarding-select-all-providers | 40.05 s | 16 | FAIL — frozen 35.0 s (min adj-sim 0.9737) | 8% (3/39 pairs change) | **FAIL — frozen** |
| issue2-onboarding-password-masking-eye | 40.56 s | 14 | FAIL — frozen 37.0 s (min adj-sim 0.9479) | 10% (4/40 pairs change) | **FAIL — frozen** |
| issue3-api-search-auth-not-something-went-wrong | N/A | 1 | FAIL — 1 frame < floor 2 (Bug #24 class) | N/A (<2 frames) | **FAIL — single still frame** |
| issue4-settings-server-list-no-duplicate | 1.73 s | 2 | FAIL — frozen 1.0 s (adj-sim 0.9999) | 0% (0/1 pair changes) | **FAIL — 2 near-identical frames** |

All four FAIL the "real, changing on-screen content" bar. Resolution is
1080x2424 (h264) for every file — a portrait phone screen capture.

## Per-video evidence

### issue1-onboarding-select-all-providers.mp4

- Size 315766 bytes; duration 40.047744 s; 1080x2424 h264; 16 decoded frames.
- Analyzer verdict (verbatim): `DECISION : FAIL — frozen: adjacent frames
  >= 0.9990 similar for 35.00s >= freeze window 1.00s (stuck decoder / single
  stale frame)`; max-freeze-run 35.00 s; min-adj-sim 0.9737.
- Local: 40 frames extracted at 1fps. The first ~36 extracted frames are
  byte-identical at 160842 bytes — i.e. a STATIC screen held for ~36 s — with
  visible content change only in the final ~4 frames (sizes 19115 / 100327 /
  27137 / 27172). Only 3 of 39 neighbour pairs differ by >2% (8% liveness).
- Interpretation: the recording is a near-static screen for ~35 of its 40 s.
  It does NOT show a live, continuously-changing flow. **FAIL.**

### issue2-onboarding-password-masking-eye.mp4

- Size 266341 bytes; duration 40.555756 s; 1080x2424 h264; 14 decoded frames.
- Analyzer verdict (verbatim): `DECISION : FAIL — frozen: adjacent frames
  >= 0.9990 similar for 37.00s >= freeze window 1.00s`; max-freeze-run 37.00 s;
  min-adj-sim 0.9479.
- Local: 41 frames at 1fps. First ~37 frames byte-identical at 160842 bytes
  (static for ~37 s); change only in the last ~4 frames. 4 of 40 neighbour
  pairs differ >2% (10% liveness).
- Interpretation: near-static for ~37 of 40 s. **FAIL.**

### issue3-api-search-auth-not-something-went-wrong.mp4

- Size 51817 bytes; duration reported `N/A`; 1080x2424 h264; **1 decoded frame**.
- Analyzer verdict (verbatim): `DECISION : FAIL — decoded-frame count 1 < floor
  2 — 0/low-frame recording (Bug #24 class)`.
- Local: `ffmpeg -vf fps=1` extracted 0 frames (a single still has no 1-second
  sample beyond frame 0). This is effectively a single still image, not a video.
- Interpretation: there is no playback to validate — a single frame cannot show
  a flow or a before/after state. **FAIL.**

### issue4-settings-server-list-no-duplicate.mp4

- Size 52314 bytes; duration 1.734333 s; 1080x2424 h264; **2 decoded frames**.
- Analyzer verdict (verbatim): `DECISION : FAIL — frozen: adjacent frames
  >= 0.9990 similar for 1.00s >= freeze window 1.00s`; min-adj-sim 0.9999.
- Local: 2 frames at 1fps, sizes 160842 and 161102 — essentially identical
  (0.16% delta). 0 of 1 neighbour pair differs >2%.
- Interpretation: 1.7 s of two near-identical frames — too short and too static
  to demonstrate the "no duplicate server list" behaviour the filename claims.
  **FAIL.**

## Conclusion

Honest result, no bluff: **NONE of the four delivered issue Challenge videos
contains real, changing on-screen content sufficient to demonstrate the flow
its filename claims.** issue1 and issue2 are ~40 s recordings that are frozen
on a static screen for ~35-37 s with only a few seconds of change at the end.
issue3 is a single still frame (not a video). issue4 is 1.7 s of two
near-identical frames.

This verdict is corroborated by two independent methods that agree: the HelixQA
`recording-analyzer --post-analyze` §11.4.107 liveness correlator (FAIL on all
four, exit 1) and the local ffprobe decoded-frame-count + ffmpeg frame-size
liveness signal. The recording-analyzer ran for real (built from
`submodules/helixqa/cmd/recording-analyzer`, Go 1.26.2); its OCR/vision mode was
NOT used because it requires Tesseract/Whisper containers unavailable on this
host — the container-free `--post-analyze` ffprobe oracle was used instead, and
the local ffprobe/ffmpeg ground-truth path was run alongside it as required.

These four files do not stand as valid video evidence of the issues they are
named for. They would need to be re-recorded as continuous screen captures
that actually traverse the claimed flow.
