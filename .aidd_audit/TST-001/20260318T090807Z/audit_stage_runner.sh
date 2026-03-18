#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<USAGE
Usage:
  audit_stage_runner.sh \
    --project-dir <path> --plugin-dir <path> --audit-dir <path> --ticket <id> \
    --step <step_id> --run <n> --command <slash_or_text> \
    [--mode stream-json|text] [--budget-seconds <int>] [--poll-seconds <int>]
USAGE
}

PROJECT_DIR=""
PLUGIN_DIR=""
AUDIT_DIR=""
TICKET=""
STEP=""
RUN=""
CMD=""
MODE="stream-json"
BUDGET_SECONDS="1200"
POLL_SECONDS="15"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --project-dir) PROJECT_DIR="$2"; shift 2 ;;
    --plugin-dir) PLUGIN_DIR="$2"; shift 2 ;;
    --audit-dir) AUDIT_DIR="$2"; shift 2 ;;
    --ticket) TICKET="$2"; shift 2 ;;
    --step) STEP="$2"; shift 2 ;;
    --run) RUN="$2"; shift 2 ;;
    --command) CMD="$2"; shift 2 ;;
    --mode) MODE="$2"; shift 2 ;;
    --budget-seconds) BUDGET_SECONDS="$2"; shift 2 ;;
    --poll-seconds) POLL_SECONDS="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; usage; exit 2 ;;
  esac
done

if [[ -z "$PROJECT_DIR" || -z "$PLUGIN_DIR" || -z "$AUDIT_DIR" || -z "$TICKET" || -z "$STEP" || -z "$RUN" || -z "$CMD" ]]; then
  usage
  exit 2
fi

LOG_PREFIX="$AUDIT_DIR/${STEP}_run${RUN}"
MAIN_LOG="$LOG_PREFIX.log"
HEAD_FILE="$LOG_PREFIX.head.txt"
TAIL_FILE="$LOG_PREFIX.tail.log"
HEARTBEAT_FILE="$LOG_PREFIX.heartbeat.log"
SUMMARY_FILE="$LOG_PREFIX.summary.txt"
DISK_PREFLIGHT_FILE="$AUDIT_DIR/${STEP}_disk_preflight_run${RUN}.txt"
STREAM_PATHS_FILE="$AUDIT_DIR/${STEP}_stream_paths_run${RUN}.txt"
TERMINATION_ATTR_FILE="$AUDIT_DIR/${STEP}_termination_attribution.txt"
SIGNAL_COUNTS_FILE="$AUDIT_DIR/${STEP}_signal_counts_run${RUN}.txt"

mkdir -p "$AUDIT_DIR"

# Disk preflight (>=1GiB).
df -Pk "$PROJECT_DIR" > "$DISK_PREFLIGHT_FILE"
avail_kb=$(awk 'NR==2 {print $4}' "$DISK_PREFLIGHT_FILE" | tr -d '[:space:]')
avail_kb=${avail_kb:-0}
free_bytes=$((avail_kb * 1024))
if (( free_bytes < 1073741824 )); then
  {
    echo "command=$CMD"
    echo "mode=$MODE"
    echo "budget_seconds=$BUDGET_SECONDS"
    echo "classification=ENV_MISCONFIG(no_space_left_on_device)"
    echo "free_bytes=$free_bytes"
    echo "exit_code=125"
  } > "$SUMMARY_FILE"
  exit 125
fi

: > "$MAIN_LOG"
: > "$HEARTBEAT_FILE"

declare -a RUNNER
RUNNER=(claude -p "$CMD" --dangerously-skip-permissions)
if [[ "$MODE" == "stream-json" ]]; then
  RUNNER+=(--verbose --output-format stream-json --include-partial-messages)
fi
RUNNER+=(--plugin-dir "$PLUGIN_DIR")

start_ts=$(date +%s)
last_growth_ts=$start_ts
prev_signature=""
killed=0
watchdog_marker=0
silent_stall=0
termination_classification="completed"

cd "$PROJECT_DIR"
"${RUNNER[@]}" > "$MAIN_LOG" 2>&1 &
pid=$!

extract_stream_paths() {
  python3 - "$PROJECT_DIR" "$MAIN_LOG" "$STREAM_PATHS_FILE" "$TICKET" <<'PY'
import json
import os
import pathlib
import re
import sys
from typing import Iterable

project_dir = pathlib.Path(sys.argv[1]).resolve()
main_log = pathlib.Path(sys.argv[2])
out_file = pathlib.Path(sys.argv[3])
ticket = sys.argv[4]

lines = []
if main_log.exists():
  try:
    lines = main_log.read_text(errors='replace').splitlines()
  except Exception:
    lines = []

primary = []
# 1) Extract from system/init JSON payload only.
for line in lines:
  s = line.strip()
  if not s.startswith('{'):
    continue
  try:
    obj = json.loads(s)
  except Exception:
    continue
  if obj.get('type') == 'system' and obj.get('subtype') == 'init':
    def walk(v):
      if isinstance(v, dict):
        for vv in v.values():
          yield from walk(vv)
      elif isinstance(v, list):
        for vv in v:
          yield from walk(vv)
      elif isinstance(v, str):
        if v.endswith('.stream.jsonl') or v.endswith('.stream.log'):
          yield v
    for candidate in walk(obj):
      primary.append(('init', candidate))

# 2) Extract from control headers only.
header_re = re.compile(r'^==>\s+streaming enabled.*?(?:stream=([^\s]+))?(?:.*?log=([^\s]+))?', re.I)
for line in lines:
  m = header_re.match(line.strip())
  if not m:
    continue
  for g in m.groups():
    if g:
      primary.append(('header', g))

normalized = []
seen = set()
for source, raw in primary:
  p = pathlib.Path(raw)
  if not p.is_absolute():
    p = (project_dir / p).resolve()
  else:
    p = p.resolve()
  k = (source, str(p), raw)
  if k in seen:
    continue
  seen.add(k)
  normalized.append((source, raw, p))

valid_inside = []
out_lines = []
for source, raw, p in normalized:
  p_str = str(p)
  inside = p_str == str(project_dir) or p_str.startswith(str(project_dir) + os.sep)
  if not inside:
    out_lines.append(f"source={source} stream_path_invalid={p_str}")
    continue
  if not p.exists():
    out_lines.append(f"source={source} stream_path_missing={p_str}")
    continue
  out_lines.append(f"source={source} path={p_str}")
  valid_inside.append(p_str)

fallback_used = False
if not valid_inside:
  fallback_used = True
  loops_dir = project_dir / 'aidd' / 'reports' / 'loops' / ticket
  discovered = []
  if loops_dir.exists() and loops_dir.is_dir():
    for pattern in ('*.stream.jsonl', '*.stream.log'):
      for p in loops_dir.rglob(pattern):
        if p.is_file():
          discovered.append(p)
  discovered.sort(key=lambda p: p.stat().st_mtime if p.exists() else 0.0, reverse=True)
  for p in discovered[:8]:
    out_lines.append(f"source=fallback_scan path={str(p.resolve())}")
    valid_inside.append(str(p.resolve()))

if fallback_used:
  out_lines.append('fallback_scan=1')
if not valid_inside:
  out_lines.append('stream_path_not_emitted_by_cli=1')

out_file.write_text('\n'.join(out_lines) + ('\n' if out_lines else ''), encoding='utf-8')
PY
}

collect_signature() {
  local sig
  sig="main:$(stat -f%z "$MAIN_LOG" 2>/dev/null || echo 0)"
  if [[ -f "$STREAM_PATHS_FILE" ]]; then
    while IFS= read -r line; do
      case "$line" in
        source=*" path="*)
          p="${line##* path=}"
          if [[ -f "$p" ]]; then
            sz=$(stat -f%z "$p" 2>/dev/null || echo 0)
            sig="$sig|$p:$sz"
          else
            sig="$sig|$p:missing"
          fi
          ;;
      esac
    done < "$STREAM_PATHS_FILE"
  fi
  printf '%s' "$sig"
}

while kill -0 "$pid" 2>/dev/null; do
  now_ts=$(date +%s)
  extract_stream_paths
  signature=$(collect_signature)
  if [[ "$signature" != "$prev_signature" ]]; then
    last_growth_ts=$now_ts
    prev_signature="$signature"
    growth=1
  else
    growth=0
  fi
  printf '%s elapsed=%s growth=%s signature=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$((now_ts - start_ts))" "$growth" "$signature" >> "$HEARTBEAT_FILE"

  if (( now_ts - start_ts >= BUDGET_SECONDS )); then
    killed=1
    watchdog_marker=1
    termination_classification="watchdog_budget_exhausted"
    kill "$pid" 2>/dev/null || true
    sleep 2
    kill -9 "$pid" 2>/dev/null || true
    break
  fi

  if (( now_ts - last_growth_ts > 1200 )); then
    killed=1
    watchdog_marker=0
    silent_stall=1
    termination_classification="silent_stall"
    kill "$pid" 2>/dev/null || true
    sleep 2
    kill -9 "$pid" 2>/dev/null || true
    break
  fi

  sleep "$POLL_SECONDS"
done

exit_code=0
if wait "$pid"; then
  exit_code=0
else
  exit_code=$?
fi

head -n 120 "$MAIN_LOG" > "$HEAD_FILE" || true
tail -n 120 "$MAIN_LOG" > "$TAIL_FILE" || true
extract_stream_paths

python3 - "$MAIN_LOG" "$SUMMARY_FILE" "$CMD" "$MODE" "$BUDGET_SECONDS" "$start_ts" "$killed" "$watchdog_marker" "$silent_stall" "$exit_code" "$termination_classification" "$SIGNAL_COUNTS_FILE" <<'PY'
import json
import pathlib
import sys
import time

main_log = pathlib.Path(sys.argv[1])
summary_file = pathlib.Path(sys.argv[2])
cmd = sys.argv[3]
mode = sys.argv[4]
budget = int(sys.argv[5])
start_ts = int(sys.argv[6])
killed = int(sys.argv[7])
watchdog_marker = int(sys.argv[8])
silent_stall = int(sys.argv[9])
exit_code = int(sys.argv[10])
termination_classification = sys.argv[11]
signal_counts_file = pathlib.Path(sys.argv[12])

text = main_log.read_text(errors='replace') if main_log.exists() else ''
lines = text.splitlines()

init_obj = None
result_count = 0
for line in lines:
  s = line.strip()
  if not s.startswith('{'):
    continue
  try:
    obj = json.loads(s)
  except Exception:
    continue
  if obj.get('type') == 'system' and obj.get('subtype') == 'init' and init_obj is None:
    init_obj = obj
  if obj.get('type') == 'result':
    result_count += 1

plugin_loaded = 0
slash_status_present = 0
skills_present = 0
init_cwd = ''
permission_mode = ''
if init_obj:
  init_cwd = str(init_obj.get('cwd', ''))
  permission_mode = str(init_obj.get('permissionMode', ''))
  plugins = init_obj.get('plugins') or []
  plugin_loaded = 1 if any(isinstance(p, dict) and p.get('name') == 'feature-dev-aidd' for p in plugins) else 0
  slash = init_obj.get('slash_commands') or []
  slash_status_present = 1 if 'feature-dev-aidd:status' in slash else 0
  skills = init_obj.get('skills') or []
  skills_present = 1 if any(str(s).startswith('feature-dev-aidd:') for s in skills) else 0

unknown_skill_hits = text.count('Unknown skill')
cwd_wrong_hits = text.count('refusing to use plugin repository as workspace root')
module_not_found_hits = text.count("ModuleNotFoundError: No module named 'aidd_runtime'")
non_canonical_runtime_hits = text.count("python3 skills/") + text.count("can't open file")
requires_approval_hits = text.count('requires approval')

elapsed = int(time.time()) - start_ts

summary_lines = [
  f'command={cmd}',
  f'mode={mode}',
  f'budget_seconds={budget}',
  f'elapsed_seconds={elapsed}',
  f'exit_code={exit_code}',
  f'killed={killed}',
  f'watchdog_marker={watchdog_marker}',
  f'silent_stall={silent_stall}',
  f'termination_classification={termination_classification}',
  f'init_present={1 if init_obj else 0}',
  f'init_cwd={init_cwd}',
  f'permission_mode={permission_mode}',
  f'plugin_loaded={plugin_loaded}',
  f'slash_status_present={slash_status_present}',
  f'skills_present={skills_present}',
  f'result_count={result_count}',
  f'unknown_skill_hits={unknown_skill_hits}',
  f'cwd_wrong_hits={cwd_wrong_hits}',
  f'module_not_found_hits={module_not_found_hits}',
  f'non_canonical_runtime_hits={non_canonical_runtime_hits}',
  f'requires_approval_hits={requires_approval_hits}',
]
summary_file.write_text('\n'.join(summary_lines) + '\n', encoding='utf-8')

signal_lines = [
  f'unknown_skill_hits={unknown_skill_hits}',
  f'cwd_wrong_hits={cwd_wrong_hits}',
  f'module_not_found_hits={module_not_found_hits}',
  f'non_canonical_runtime_hits={non_canonical_runtime_hits}',
  f'requires_approval_hits={requires_approval_hits}',
  f'result_count={result_count}',
]
signal_counts_file.write_text('\n'.join(signal_lines) + '\n', encoding='utf-8')
PY

if [[ "$exit_code" == "143" ]]; then
  parent_pid=$(ps -o ppid= -p "$pid" 2>/dev/null | tr -d ' ' || true)
  {
    echo "exit_code=$exit_code"
    echo "signal=TERM"
    echo "killed_flag=$killed"
    echo "watchdog_marker=$watchdog_marker"
    echo "parent_pid=${parent_pid:-unknown}"
    echo "classification=${termination_classification}"
    echo "evidence_paths=$MAIN_LOG,$HEARTBEAT_FILE,$STREAM_PATHS_FILE"
  } > "$TERMINATION_ATTR_FILE"
fi

exit "$exit_code"
