#!/usr/bin/env bash
# scripts/autonomous-qa/vision-analyze.sh
# ---------------------------------------------------------------------------
# Vision + log analysis of recorded autonomous-QA materials.
#
# Reads the RAW media/logs an iteration produced (see run-iteration.sh) and
# emits a CURATED vision-analysis.md flagging the §6.AB non-crashing defect
# classes that a green JUnit verdict can hide from a real user:
#   * blank / white / monochrome render        (color heuristics, ffmpeg)
#   * stuck / frozen screen                     (ffmpeg freezedetect)
#   * wrong screen reached / missing download   (OCR — ONLY if tesseract present)
#   * crashes / ANRs                            (logcat.txt grep)
#
# Raw layout per iteration (run-iteration.sh):
#   <iter>/raw/{rec_*.mp4 | qa_rec_*.mp4}   chunked screenrecord
#   <iter>/raw/logcat.txt                   threadtime logcat
#   <iter>/raw/gradle-connected.log         connectedDebugAndroidTest stdout
#   <iter>/verdict.json                     curated JUnit verdict (optional cross-ref)
#
# Modes:
#   --iteration-dir <dir>   analyze ONE iteration's raw/ -> <dir>/vision-analysis.md
#   --cycle-dir <backend>   roll up every iteration subdir -> <backend>/vision-analysis.md
#
# Temp frames are extracted under <iter>/raw/.vision-frames/ which is ALREADY
# gitignored (.lava-ci-evidence/autonomous-qa/**/raw/) and are removed on exit
# unless --keep-frames is passed. Frames are NEVER committed.
#
# ANTI-BLUFF (§6.J / §6.AB / §6.L): this script NEVER prints a defect-free
# (CLEAN) verdict for analysis it did not actually perform.
#   * ffmpeg missing                       -> exit 2 (TOOL-MISSING), no verdict
#   * no analyzable recordings AND no log  -> exit 2 (CANNOT-ANALYZE)
#   * recordings missing/unreadable        -> verdict INCOMPLETE, exit 2 (never CLEAN)
#   * tesseract absent                     -> OCR checks SKIPPED + said so plainly;
#                                             screen-content claims are NOT fabricated
# Exit codes: 0 = CLEAN, 1 = DEFECTS-FOUND, 2 = CANNOT-ANALYZE / INCOMPLETE / TOOL-MISSING.
#
# Usage:
#   vision-analyze.sh --iteration-dir <dir> [opts]
#   vision-analyze.sh --cycle-dir <backend-dir> [opts]
# Options:
#   --keep-frames            keep extracted PNG frames (default: delete on exit)
#   --package <pkg>          app package for logcat crash grep
#                            (default: digital.vasic.lava.client.dev)
#   --expect-tokens <csv>    OCR affordance tokens to confirm result/download UI
#                            (default: download,magnet,torrent,seed,size)
#   --ocr-frames <N>         max frames to OCR per recording (default: 24)
#   -h|--help                this help
# ---------------------------------------------------------------------------
set -euo pipefail

# --- tunable detection thresholds -----------------------------------------
SAMPLE_FPS=1                  # frames extracted per second of recording
UNIFORM_FULL_RANGE_MAX=40     # YMAX-YMIN <= this  => frame draws ~nothing (uniform)
WHITE_YAVG_MIN=200            # uniform frame with YAVG >= this  => WHITE blank
BLACK_YAVG_MAX=32             # uniform frame with YAVG <= this  => BLACK blank
BLANK_RUN_FRAMES=5            # >= this many CONSECUTIVE uniform frames => stuck-blank defect
BLANK_FRACTION_PCT=50         # >= this %% of frames uniform => blank-dominated defect
FREEZE_SECONDS=10             # freezedetect minimum frozen span to flag (seconds)
FREEZE_NOISE="-60dB"          # freezedetect noise tolerance

usage() { sed -n '2,/^set -euo/p' "$0" | sed 's/^# \{0,1\}//;/^set -euo/d'; }
die()   { echo "vision-analyze: $*" >&2; exit 2; }

# --- args ------------------------------------------------------------------
ITER_DIR=""; CYCLE_DIR=""; KEEP_FRAMES=0
PACKAGE="digital.vasic.lava.client.dev"
EXPECT_TOKENS="download,magnet,torrent,seed,size"
OCR_FRAMES=24
while [[ $# -gt 0 ]]; do
  case "$1" in
    --iteration-dir) ITER_DIR="$2"; shift 2;;
    --cycle-dir)     CYCLE_DIR="$2"; shift 2;;
    --keep-frames)   KEEP_FRAMES=1; shift;;
    --package)       PACKAGE="$2"; shift 2;;
    --expect-tokens) EXPECT_TOKENS="$2"; shift 2;;
    --ocr-frames)    OCR_FRAMES="$2"; shift 2;;
    -h|--help)       usage; exit 0;;
    *) echo "vision-analyze: unknown arg '$1'" >&2; usage >&2; exit 2;;
  esac
done
[[ -n "$ITER_DIR" && -n "$CYCLE_DIR" ]] && die "pass exactly one of --iteration-dir / --cycle-dir"
[[ -z "$ITER_DIR" && -z "$CYCLE_DIR" ]] && { usage >&2; die "pass --iteration-dir <dir> OR --cycle-dir <dir>"; }

# --- tool detection (runtime) ---------------------------------------------
FFMPEG="${FFMPEG:-$(command -v ffmpeg 2>/dev/null || true)}"
[[ -n "$FFMPEG" ]] || die "ffmpeg is REQUIRED and was not found on PATH (set \$FFMPEG to override). Refusing to emit a verdict."

HAVE_TESSERACT=0; TESS=""; TESS_LANGS="(none)"
if TESS="$(command -v tesseract 2>/dev/null)" && [[ -n "$TESS" ]]; then
  HAVE_TESSERACT=1
  TESS_LANGS="$("$TESS" --list-langs 2>/dev/null | tail -n +2 | tr '\n' ',' | sed 's/,$//')"
  [[ -n "$TESS_LANGS" ]] || TESS_LANGS="(unknown)"
fi
# imagemagick: optional, informational only (ffmpeg signalstats is the primary path).
HAVE_IM=0; IM_TOOL=""
if IM_TOOL="$(command -v magick 2>/dev/null || command -v convert 2>/dev/null || true)" && [[ -n "$IM_TOOL" ]]; then
  HAVE_IM=1
fi

# --- per-recording frame analysis -----------------------------------------
# Extract frames -> classify uniform/blank via signalstats -> echo summary line:
#   "<total> <blank> <white> <black> <mono> <maxrun>"  (all integers)
# total==0 signals an unreadable/empty recording.
analyze_recording_frames() {
  local rec="$1" fdir="$2" stats nframes
  rm -rf "$fdir"; mkdir -p "$fdir"
  # 1fps PNG extraction (best-effort; loglevel error suppresses harmless dts warnings).
  "$FFMPEG" -hide_banner -loglevel error -y -i "$rec" -an -vf "fps=${SAMPLE_FPS}" \
    "$fdir/f_%05d.png" >/dev/null 2>&1 || true
  nframes="$(find "$fdir" -maxdepth 1 -name 'f_*.png' -type f 2>/dev/null | wc -l | tr -d ' ')"
  if [[ "$nframes" -eq 0 ]]; then
    echo "0 0 0 0 0 0"; return 0
  fi
  # signalstats over the extracted frame sequence -> per-frame metadata file.
  stats="$fdir/signalstats.txt"
  "$FFMPEG" -hide_banner -loglevel error -framerate 1 -start_number 1 \
    -i "$fdir/f_%05d.png" -vf "signalstats,metadata=print:file=${stats}" -f null - \
    >/dev/null 2>&1 || true
  if [[ ! -s "$stats" ]]; then
    # Frames exist but signalstats failed -> report frames, zero blank (honest: not analyzed for blankness).
    echo "$nframes 0 0 0 0 0"; return 0
  fi
  awk -v RMAX="$UNIFORM_FULL_RANGE_MAX" -v WMIN="$WHITE_YAVG_MIN" -v BMAX="$BLACK_YAVG_MAX" '
    function flush(){
      if (have){
        total++
        rng = ymax - ymin
        if (rng <= RMAX){
          blank++; run++
          if (run>maxrun) maxrun=run
          if (yavg>=WMIN) white++
          else if (yavg<=BMAX) black++
          else mono++
        } else { run=0 }
      }
      have=0; ymin=0; ymax=0; yavg=0
    }
    /^frame:/ { flush(); have=1; next }
    /\.YMIN=/ { split($0,a,"="); ymin=a[2]+0 }
    /\.YMAX=/ { split($0,a,"="); ymax=a[2]+0 }
    /\.YAVG=/ { split($0,a,"="); yavg=a[2]+0 }
    END { flush(); printf "%d %d %d %d %d %d\n", total+0, blank+0, white+0, black+0, mono+0, maxrun+0 }
  ' "$stats"
}

# Echo freeze spans found by freezedetect on the ORIGINAL mp4: "<count> <max_seconds>".
analyze_recording_freeze() {
  local rec="$1" out count maxdur
  out="$("$FFMPEG" -hide_banner -loglevel error -i "$rec" -an \
        -vf "freezedetect=n=${FREEZE_NOISE}:d=${FREEZE_SECONDS},metadata=print:file=-" \
        -f null - 2>/dev/null | grep -oE 'freeze_duration=[0-9.]+' || true)"
  count="$(printf '%s\n' "$out" | grep -c 'freeze_duration=' || true)"
  if [[ "$count" -eq 0 ]]; then echo "0 0"; return 0; fi
  maxdur="$(printf '%s\n' "$out" | sed 's/freeze_duration=//' \
            | awk 'BEGIN{m=0}{if($1+0>m)m=$1+0}END{printf "%.1f", m}')"
  echo "$count $maxdur"
}

# OCR a spread of frames -> lowercase corpus on stdout (caller decides). tesseract only.
ocr_corpus() {
  local fdir="$1" total step i n=0
  total="$(find "$fdir" -maxdepth 1 -name 'f_*.png' -type f 2>/dev/null | wc -l | tr -d ' ')"
  [[ "$total" -eq 0 ]] && return 0
  step=$(( (total + OCR_FRAMES - 1) / OCR_FRAMES )); [[ "$step" -lt 1 ]] && step=1
  while IFS= read -r f; do
    n=$((n+1))
    (( (n-1) % step != 0 )) && continue
    "$TESS" "$f" stdout --psm 6 2>/dev/null || true
  done < <(find "$fdir" -maxdepth 1 -name 'f_*.png' -type f 2>/dev/null | sort)
}

# --- logcat crash analysis -> echoes "<crash_count>"; appends detail to $1 (md) --
analyze_logcat() {
  local md="$1" log="$2" n
  if [[ ! -s "$log" ]]; then
    { echo "- logcat: **MISSING** (\`$log\` absent or empty) — crash analysis not possible."; } >> "$md"
    echo "-1"; return 0   # -1 sentinel: log unavailable (distinct from 0 = clean log)
  fi
  n="$(grep -cE 'FATAL EXCEPTION|E AndroidRuntime|ANR in |beginning of crash' "$log" 2>/dev/null || true)"
  n="${n:-0}"
  if [[ "$n" -eq 0 ]]; then
    { echo "- logcat: clean — no \`FATAL EXCEPTION\` / \`E AndroidRuntime\` / \`ANR in\` lines."; } >> "$md"
  else
    {
      echo "- logcat: **$n crash/ANR marker line(s) found**:"
      echo '```'
      grep -nE 'FATAL EXCEPTION|E AndroidRuntime|ANR in |beginning of crash' "$log" 2>/dev/null | head -20 || true
      if grep -qE "$PACKAGE" "$log" 2>/dev/null; then
        echo "--- context (lines mentioning $PACKAGE around first crash) ---"
        grep -nE "$PACKAGE" "$log" 2>/dev/null | head -8 || true
      fi
      echo '```'
    } >> "$md"
  fi
  echo "$n"
}

# --- analyze ONE iteration; writes <iter>/vision-analysis.md ---------------
# Returns: 0 CLEAN, 1 DEFECTS-FOUND, 2 INCOMPLETE/CANNOT-ANALYZE.
analyze_iteration() {
  local iter="${1%/}" raw md defects_file rc=0
  raw="$iter/raw"
  md="$iter/vision-analysis.md"
  defects_file="$(mktemp)"
  : > "$defects_file"

  # gather recordings (both naming variants run-iteration may leave behind)
  local recs=()
  while IFS= read -r r; do recs+=("$r"); done < <(
    find "$raw" -maxdepth 1 \( -name 'rec_*.mp4' -o -name 'qa_rec_*.mp4' \) -type f 2>/dev/null | sort
  )

  {
    echo "# Vision analysis — $(basename "$iter")"
    echo ""
    echo "- Generated: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "- Iteration dir: \`$iter\`"
    echo "- Tools: ffmpeg=\`$FFMPEG\`; tesseract=$( [[ $HAVE_TESSERACT -eq 1 ]] && echo "\`$TESS\` (langs: $TESS_LANGS)" || echo "**absent — OCR checks skipped**" ); imagemagick=$( [[ $HAVE_IM -eq 1 ]] && echo "\`$IM_TOOL\`" || echo "absent" )"
    echo "- Heuristics: uniform-frame = YMAX-YMIN ≤ ${UNIFORM_FULL_RANGE_MAX}; blank defect = ≥${BLANK_RUN_FRAMES} consecutive uniform frames OR ≥${BLANK_FRACTION_PCT}% uniform; frozen defect = freezedetect span ≥${FREEZE_SECONDS}s."
    if [[ -f "$iter/verdict.json" ]]; then
      echo "- JUnit cross-ref (verdict.json): $(grep -oE '"verdict": *"[A-Z]+"' "$iter/verdict.json" 2>/dev/null | grep -oE '[A-Z]+$' || echo '?'), failures=$(grep -oE '"failures": *[0-9]+' "$iter/verdict.json" 2>/dev/null | grep -oE '[0-9]+$' || echo '?'), errors=$(grep -oE '"errors": *[0-9]+' "$iter/verdict.json" 2>/dev/null | grep -oE '[0-9]+$' || echo '?')"
    fi
    echo ""
    echo "## Recordings"
    echo ""
  } > "$md"

  local analyzed=0 unreadable=0
  if [[ ${#recs[@]} -eq 0 ]]; then
    echo "_No \`rec_*.mp4\` / \`qa_rec_*.mp4\` recordings found under \`$raw\`._" >> "$md"
    echo "NO-RECORDINGS" >> "$defects_file"
  fi

  local rec base fdir line total blank white black mono maxrun fz fzcount fzmax pct
  for rec in "${recs[@]}"; do
    base="$(basename "$rec")"; base="${base%.mp4}"
    fdir="$raw/.vision-frames/$base"
    line="$(analyze_recording_frames "$rec" "$fdir")"
    read -r total blank white black mono maxrun <<< "$line"
    {
      echo "### \`$base.mp4\`"
    } >> "$md"
    if [[ "$total" -eq 0 ]]; then
      unreadable=$((unreadable+1))
      { echo "- **UNREADABLE** — ffmpeg extracted 0 frames (empty/corrupt recording). Cannot assess render."; } >> "$md"
      echo "UNREADABLE: $base.mp4 (0 frames extracted)" >> "$defects_file"
      echo "" >> "$md"
      continue
    fi
    analyzed=$((analyzed+1))
    pct=$(( blank * 100 / total ))
    fz="$(analyze_recording_freeze "$rec")"; read -r fzcount fzmax <<< "$fz"
    {
      echo "- frames sampled (@${SAMPLE_FPS}fps): **$total**"
      echo "- uniform/blank frames: **$blank** (${pct}%) — white=$white black=$black mono/colored=$mono; longest consecutive blank run: **${maxrun}** frame(s)"
      echo "- frozen spans ≥${FREEZE_SECONDS}s (freezedetect): **$fzcount** (longest ${fzmax}s)"
    } >> "$md"

    # blank/white-render defect
    if [[ "$maxrun" -ge "$BLANK_RUN_FRAMES" || "$pct" -ge "$BLANK_FRACTION_PCT" ]]; then
      local kind="blank"
      if   [[ "$white" -ge "$black" && "$white" -ge "$mono" ]]; then kind="WHITE/blank render"
      elif [[ "$black" -ge "$white" && "$black" -ge "$mono" ]]; then kind="BLACK render"
      else kind="MONOCHROME/uniform render"; fi
      { echo "- 🚩 **DEFECT (§6.AB blank/white screen): $kind** — ${blank}/${total} frames uniform (${pct}%), longest run ${maxrun}s."; } >> "$md"
      echo "BLANK-SCREEN: $base.mp4 — $kind ${pct}% uniform, ${maxrun}s longest run" >> "$defects_file"
    fi
    # frozen/stuck-screen defect
    if [[ "$fzcount" -gt 0 ]]; then
      { echo "- 🚩 **DEFECT (§6.AB stuck/frozen screen):** ${fzcount} frozen span(s), longest ${fzmax}s — UI not progressing. (Note: an *animated* spinner stays animated and is NOT caught here; see OCR section.)"; } >> "$md"
      echo "FROZEN-SCREEN: $base.mp4 — ${fzcount} span(s), longest ${fzmax}s" >> "$defects_file"
    fi

    # OCR — content confirmation (tesseract only; otherwise honestly skipped)
    if [[ "$HAVE_TESSERACT" -eq 1 ]]; then
      local corpus found_list="" missing_list="" tok have_any_text=0
      corpus="$(ocr_corpus "$fdir" | tr '[:upper:]' '[:lower:]')"
      [[ -n "${corpus//[[:space:]]/}" ]] && have_any_text=1
      IFS=',' read -ra _toks <<< "$EXPECT_TOKENS"
      local any_found=0
      for tok in "${_toks[@]}"; do
        tok="$(printf '%s' "$tok" | tr '[:upper:]' '[:lower:]' | sed 's/^ *//;s/ *$//')"
        [[ -z "$tok" ]] && continue
        if printf '%s' "$corpus" | grep -qiF -- "$tok"; then
          found_list="${found_list:+$found_list, }$tok"; any_found=1
        else
          missing_list="${missing_list:+$missing_list, }$tok"
        fi
      done
      local loading_hit=0
      if printf '%s' "$corpus" | grep -qiE 'loading|searching|please wait|connecting|загруз'; then loading_hit=1; fi
      {
        echo "- OCR (tesseract, langs: $TESS_LANGS): text recognized on sampled frames: $( [[ $have_any_text -eq 1 ]] && echo yes || echo '**none**' )"
        echo "  - expected affordance tokens found: ${found_list:-(none)}"
        echo "  - expected affordance tokens MISSING: ${missing_list:-(none)}"
      } >> "$md"
      if [[ "$have_any_text" -eq 0 ]]; then
        { echo "  - note: OCR found no text anywhere — corroborates a blank/non-rendering screen (already flagged above if uniform)."; } >> "$md"
      elif [[ "$any_found" -eq 0 ]]; then
        if [[ "$loading_hit" -eq 1 ]]; then
          { echo "- 🚩 **DEFECT (§6.AB stuck progress / wrong screen, OCR):** loading/searching text seen but NONE of the expected result/download affordances ($EXPECT_TOKENS) ever appeared."; } >> "$md"
          echo "OCR-STUCK-LOADING: $base.mp4 — loading text present, no affordance tokens" >> "$defects_file"
        else
          { echo "- 🚩 **DEFECT (§6.AB wrong screen / missing download affordance, OCR):** none of the expected affordances ($EXPECT_TOKENS) appeared on any sampled frame."; } >> "$md"
          echo "OCR-NO-AFFORDANCE: $base.mp4 — none of [$EXPECT_TOKENS] seen" >> "$defects_file"
        fi
      fi
    else
      { echo "- OCR: **SKIPPED** (tesseract not installed). Screen-content / download-affordance / wrong-screen checks were NOT performed — no claim is made about on-screen text."; } >> "$md"
    fi
    echo "" >> "$md"
  done

  # logcat
  { echo "## Logcat"; echo ""; } >> "$md"
  local crashes; crashes="$(analyze_logcat "$md" "$raw/logcat.txt")"
  if [[ "$crashes" -gt 0 ]]; then
    echo "CRASH: $crashes crash/ANR marker line(s) in logcat.txt" >> "$defects_file"
  fi
  echo "" >> "$md"

  # gradle log cross-reference (secondary signal)
  if [[ -s "$raw/gradle-connected.log" ]]; then
    local gf; gf="$(grep -cE 'FAILED|AssertionError|androidx.test.*Exception' "$raw/gradle-connected.log" 2>/dev/null || true)"
    { echo "## Gradle connected log"; echo ""; echo "- failure/exception marker lines: ${gf:-0} (cross-reference only; JUnit verdict is authoritative)"; echo ""; } >> "$md"
  fi

  # ---- verdict (anti-bluff) ----
  local ndef; ndef="$(grep -cvE '^$' "$defects_file" 2>/dev/null || true)"; ndef="${ndef:-0}"
  # real (non-incompleteness) defects vs incompleteness markers
  local nreal; nreal="$(grep -cvE '^(NO-RECORDINGS|UNREADABLE:)' "$defects_file" 2>/dev/null || true)"; nreal="${nreal:-0}"
  local logmissing=0; [[ "$crashes" == "-1" ]] && logmissing=1

  {
    echo "## Verdict"
    echo ""
  } >> "$md"

  local verdict
  if [[ "$nreal" -gt 0 ]]; then
    verdict="DEFECTS-FOUND"; rc=1
  elif [[ "$analyzed" -eq 0 || "$unreadable" -gt 0 || "$logmissing" -eq 1 ]]; then
    # Nothing fully analyzable, or some material missing/unreadable -> NEVER fake CLEAN.
    verdict="INCOMPLETE"; rc=2
  else
    verdict="CLEAN"; rc=0
  fi

  {
    if [[ "$verdict" == "CLEAN" ]]; then
      echo "**CLEAN** — $analyzed recording(s) analyzed; no blank/frozen/OCR/crash defects detected."
    elif [[ "$verdict" == "DEFECTS-FOUND" ]]; then
      echo "**DEFECTS-FOUND** ($nreal):"
      grep -vE '^(NO-RECORDINGS|UNREADABLE:)$|^NO-RECORDINGS$' "$defects_file" 2>/dev/null | grep -vE '^$' | sed 's/^/- /' || true
      if [[ "$ndef" -ne "$nreal" ]]; then
        echo ""
        echo "Plus incompleteness:"
        grep -E '^(NO-RECORDINGS|UNREADABLE:)' "$defects_file" 2>/dev/null | sed 's/^/- /' || true
      fi
    else
      echo "**INCOMPLETE** — analysis could not be completed; NOT reporting CLEAN. Reasons:"
      [[ "$analyzed" -eq 0 ]]   && echo "- no recordings were analyzable"
      [[ "$unreadable" -gt 0 ]] && echo "- $unreadable recording(s) unreadable (0 frames)"
      [[ "$logmissing" -eq 1 ]] && echo "- logcat.txt missing/empty (crash analysis impossible)"
      grep -E '^(NO-RECORDINGS|UNREADABLE:)' "$defects_file" 2>/dev/null | sed 's/^/- /' || true
    fi
  } >> "$md"

  rm -f "$defects_file"
  echo "[vision] $(basename "$iter"): $verdict" >&2
  return "$rc"
}

# --- cleanup of extracted frames (kept under raw/, gitignored) -------------
cleanup_frames() {
  [[ "$KEEP_FRAMES" -eq 1 ]] && return 0
  local root="$1"
  find "$root" -type d -name '.vision-frames' -prune -exec rm -rf {} + 2>/dev/null || true
}

# --- iteration mode --------------------------------------------------------
if [[ -n "$ITER_DIR" ]]; then
  [[ -d "$ITER_DIR" ]] || die "iteration dir not found: $ITER_DIR"
  [[ -d "$ITER_DIR/raw" ]] || die "no raw/ under iteration dir: $ITER_DIR/raw (nothing recorded — refusing to fake a verdict)"
  trap 'cleanup_frames "$ITER_DIR/raw"' EXIT
  set +e; analyze_iteration "$ITER_DIR"; rc=$?; set -e
  echo "[vision] wrote $ITER_DIR/vision-analysis.md" >&2
  exit "$rc"
fi

# --- cycle rollup mode -----------------------------------------------------
[[ -d "$CYCLE_DIR" ]] || die "cycle dir not found: $CYCLE_DIR"
CYCLE_DIR="${CYCLE_DIR%/}"

iters=()
while IFS= read -r d; do iters+=("$(dirname "$d")"); done < <(
  find "$CYCLE_DIR" -mindepth 2 -maxdepth 2 -type d -name 'raw' 2>/dev/null | sort
)
[[ ${#iters[@]} -eq 0 ]] && die "no iteration subdirs with raw/ found under $CYCLE_DIR (nothing to analyze — refusing to fake CLEAN)"

trap 'cleanup_frames "$CYCLE_DIR"' EXIT

CMD="$CYCLE_DIR/vision-analysis.md"
{
  echo "# Vision analysis (cycle rollup) — $(basename "$CYCLE_DIR")"
  echo ""
  echo "- Generated: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "- Cycle dir: \`$CYCLE_DIR\`"
  echo "- Iterations: ${#iters[@]}"
  echo "- OCR: $( [[ $HAVE_TESSERACT -eq 1 ]] && echo "enabled ($TESS_LANGS)" || echo "DISABLED (tesseract absent — screen-content checks skipped)" )"
  echo ""
  echo "| iteration | verdict | detail |"
  echo "|---|---|---|"
} > "$CMD"

cycle_rc=0; n_clean=0; n_def=0; n_inc=0
for it in "${iters[@]}"; do
  set +e; analyze_iteration "$it" >/dev/null 2>&1; ir=$?; set -e
  iv="?"; det=""
  if [[ -f "$it/vision-analysis.md" ]]; then
    iv="$(grep -oE '\*\*(CLEAN|DEFECTS-FOUND|INCOMPLETE)\*\*' "$it/vision-analysis.md" | tail -1 | tr -d '*' || true)"
    # compact defect codes from the Verdict bullets (BLANK-SCREEN/FROZEN-SCREEN/OCR-*/CRASH/UNREADABLE/...)
    det="$(grep -oE '(BLANK-SCREEN|FROZEN-SCREEN|OCR-[A-Z-]+|CRASH|UNREADABLE|NO-RECORDINGS)[^|]*' "$it/vision-analysis.md" 2>/dev/null | sed 's/|/\\|/g' | paste -sd '; ' - | cut -c1-220 || true)"
  fi
  [[ -z "$iv" ]] && iv="?"
  case "$ir" in
    0) n_clean=$((n_clean+1));;
    1) n_def=$((n_def+1)); cycle_rc=1;;
    *) n_inc=$((n_inc+1)); [[ "$cycle_rc" -eq 0 ]] && cycle_rc=2;;
  esac
  echo "| \`$(basename "$it")\` | $iv | ${det:-—} |" >> "$CMD"
done

{
  echo ""
  if [[ "$n_def" -gt 0 ]]; then
    echo "## Cycle verdict: **DEFECTS-FOUND**"
  elif [[ "$n_inc" -gt 0 ]]; then
    echo "## Cycle verdict: **INCOMPLETE** (not CLEAN — some iterations could not be fully analyzed)"
  else
    echo "## Cycle verdict: **CLEAN**"
  fi
  echo ""
  echo "Totals: CLEAN=$n_clean DEFECTS-FOUND=$n_def INCOMPLETE=$n_inc (of ${#iters[@]} iterations)."
} >> "$CMD"

echo "[vision] wrote $CMD (CLEAN=$n_clean DEFECTS=$n_def INCOMPLETE=$n_inc)" >&2
exit "$cycle_rc"
