#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$1"
PLUGIN_DIR="$2"
AUDIT_DIR="$3"
TICKET="$4"
STEP_TIMEOUT_SECONDS="$5"
STAGE_BUDGET_SECONDS="$6"
BLOCKED_POLICY="$7"
RECOVERABLE_BLOCK_RETRIES="$8"
LOG_POLL_SECONDS="${9:-15}"

LOG_PREFIX="$AUDIT_DIR/07_loop_run1"
MAIN_LOG="$LOG_PREFIX.log"
HEAD_FILE="$LOG_PREFIX.head.txt"
TAIL_FILE="$LOG_PREFIX.tail.log"
HEARTBEAT_FILE="$LOG_PREFIX.heartbeat.log"
SUMMARY_FILE="$LOG_PREFIX.summary.txt"
DISK_PREFLIGHT_FILE="$AUDIT_DIR/07_loop_disk_preflight_run1.txt"
STREAM_PATHS_FILE="$AUDIT_DIR/07_loop_stream_paths_run1.txt"
TERMINATION_ATTR_FILE="$AUDIT_DIR/07_loop_termination_attribution.txt"

mkdir -p "$AUDIT_DIR"

df -Pk "$PROJECT_DIR" > "$DISK_PREFLIGHT_FILE"
avail_kb=$(awk 'NR==2 {print $4}' "$DISK_PREFLIGHT_FILE" | tr -d '[:space:]')
avail_kb=${avail_kb:-0}
free_bytes=$((avail_kb * 1024))
if (( free_bytes < 1073741824 )); then
  {
    echo "classification=ENV_MISCONFIG(no_space_left_on_device)"
    echo "free_bytes=$free_bytes"
    echo "exit_code=125"
  } > "$SUMMARY_FILE"
  exit 125
fi

: > "$MAIN_LOG"
: > "$HEARTBEAT_FILE"

extract_stream_paths() {
  python3 - "$PROJECT_DIR" "$TICKET" "$STREAM_PATHS_FILE" <<'PY'
import pathlib, sys
project = pathlib.Path(sys.argv[1]).resolve()
ticket = sys.argv[2]
out_file = pathlib.Path(sys.argv[3])
loops_dir = project / 'aidd' / 'reports' / 'loops' / ticket
found = []
if loops_dir.exists() and loops_dir.is_dir():
    for pattern in ('*.stream.jsonl', '*.stream.log'):
        for p in loops_dir.rglob(pattern):
            if p.is_file():
                found.append(p.resolve())
found = sorted(found, key=lambda p: p.stat().st_mtime, reverse=True)
out = []
for p in found[:16]:
    out.append(f"source=fallback_scan path={p}")
if found:
    out.append('fallback_scan=1')
else:
    out.append('fallback_scan=1')
    out.append('stream_path_not_emitted_by_cli=1')
out_file.write_text('\n'.join(out) + '\n', encoding='utf-8')
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

start_ts=$(date +%s)
last_growth_ts=$start_ts
prev_signature=""
killed=0
watchdog_marker=0
silent_stall=0
termination_classification="completed"

cd "$PROJECT_DIR"
AIDD_LOOP_RUNNER="claude --dangerously-skip-permissions" \
CLAUDE_PLUGIN_ROOT="$PLUGIN_DIR" \
PYTHONPATH="$PLUGIN_DIR${PYTHONPATH:+:$PYTHONPATH}" \
python3 "$PLUGIN_DIR/skills/aidd-loop/runtime/loop_run.py" \
  --ticket "$TICKET" \
  --max-iterations 6 \
  --stream \
  --step-timeout-seconds "$STEP_TIMEOUT_SECONDS" \
  --stage-budget-seconds "$STAGE_BUDGET_SECONDS" \
  --blocked-policy "$BLOCKED_POLICY" \
  --recoverable-block-retries "$RECOVERABLE_BLOCK_RETRIES" \
  > "$MAIN_LOG" 2>&1 &
pid=$!

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

  # external watchdog at 2h to align with loop-run recommendation
  if (( now_ts - start_ts >= 7200 )); then
    killed=1
    watchdog_marker=1
    termination_classification="watchdog_budget_exhausted"
    kill "$pid" 2>/dev/null || true
    sleep 2
    kill -9 "$pid" 2>/dev/null || true
    break
  fi

  # silent stall: no growth in main and discovered stream files for >20m
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

  sleep "$LOG_POLL_SECONDS"
done

exit_code=0
if wait "$pid"; then
  exit_code=0
else
  exit_code=$?
fi

head -n 160 "$MAIN_LOG" > "$HEAD_FILE" || true
tail -n 160 "$MAIN_LOG" > "$TAIL_FILE" || true
extract_stream_paths

python3 - "$MAIN_LOG" "$SUMMARY_FILE" "$start_ts" "$exit_code" "$killed" "$watchdog_marker" "$silent_stall" "$termination_classification" <<'PY'
import json, pathlib, sys, time, re
main_log = pathlib.Path(sys.argv[1])
summary_file = pathlib.Path(sys.argv[2])
start_ts = int(sys.argv[3])
exit_code = int(sys.argv[4])
killed = int(sys.argv[5])
watchdog_marker = int(sys.argv[6])
silent_stall = int(sys.argv[7])
termination_classification = sys.argv[8]
text = main_log.read_text(errors='replace') if main_log.exists() else ''
lines = text.splitlines()
result_count = sum(1 for l in lines if l.startswith('{"type":"result"') or '"schema":"aidd.loop_result.v1"' in l)
permission_mode_default_hits = text.count('permissionMode=default') + text.count('"permissionMode":"default"')
requires_approval_hits = text.count('requires approval')
stage_result_missing_or_invalid_hits = text.count('stage_result_missing_or_invalid')
blocking_findings_hits = text.count('blocking_findings')
scope_drift_hits = text.count('scope_fallback_stale_ignored') + text.count('scope_shape_invalid')
elapsed = int(time.time()) - start_ts
summary_lines = [
    'command=python3 skills/aidd-loop/runtime/loop_run.py',
    'mode=text',
    f'elapsed_seconds={elapsed}',
    f'exit_code={exit_code}',
    f'killed={killed}',
    f'watchdog_marker={watchdog_marker}',
    f'silent_stall={silent_stall}',
    f'termination_classification={termination_classification}',
    f'result_count={result_count}',
    f'permission_mode_default_hits={permission_mode_default_hits}',
    f'requires_approval_hits={requires_approval_hits}',
    f'stage_result_missing_or_invalid_hits={stage_result_missing_or_invalid_hits}',
    f'blocking_findings_hits={blocking_findings_hits}',
    f'scope_drift_hits={scope_drift_hits}',
]
summary_file.write_text('\n'.join(summary_lines) + '\n', encoding='utf-8')
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
