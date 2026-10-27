#!/usr/bin/env bash
# Record the phone's screen over adb (via scrcpy) into ~/Downloads/GBook/recordings.
# Usage: scripts/record-screen.sh [name] [seconds]
#   name     file name without extension (default: gbook-<timestamp>)
#   seconds  optional time limit; otherwise stop with Ctrl+C or by closing the mirror window
set -euo pipefail

out_dir="$HOME/Downloads/GBook/recordings"
mkdir -p "$out_dir"
name="${1:-gbook-$(date +%Y%m%d-%H%M%S)}"
out="$out_dir/$name.mp4"

args=(--record="$out" --no-audio --show-touches --stay-awake --video-bit-rate=12M
      --window-title="GBook recording → $name.mp4")
[[ -n "${2:-}" ]] && args+=(--time-limit="$2")

adb get-state >/dev/null
adb shell monkey -p com.gbook -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true

echo "Recording to $out  (Ctrl+C or close the window to stop)"
scrcpy "${args[@]}" || true
[[ -s "$out" ]] && echo "Saved $out ($(du -h "$out" | cut -f1))"
