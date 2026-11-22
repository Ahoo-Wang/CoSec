#!/usr/bin/env bash
#
# Starts the system test's stack (COMPOSE_FILE) and waits until every service is healthy, as an orchestrator would:
# a service whose process exits while the stack starts is started again, at most MAX_STARTS times in all, within one
# bound for the whole start. Every service started more than once is reported, with the last lines of the run that
# exited, on stderr and in the job's summary: a restart is never silent. A service that keeps exiting fails the step with
# its log. Once the stack is healthy nothing restarts anything: the scenarios see every exit.
#
# The allowance exists for Ahoo-Wang/CoCache#1009: CoCache's eviction check reads Redis's maxmemory-policy once at
# startup, bounded by its 2 s command timeout, and a read that times out is fatal. On a runner starting five JVMs at once
# that read can time out with Redis healthy. Remove this allowance (back to `docker compose up --wait`) once CoCache reads
# the policy without racing its command timeout.
set -euo pipefail

MAX_STARTS=${MAX_STARTS:-3}
BOUND=${BOUND:-240}
TAIL=${TAIL:-40}

deadline=$((SECONDS + BOUND))
declare -A starts=()
restarted=()

report() {
  echo "$1" >&2
  if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
    printf '%s\n' "$1" >> "$GITHUB_STEP_SUMMARY"
  fi
}

while true; do
  remaining=$((deadline - SECONDS))
  if [ "$remaining" -le 0 ]; then
    report "The stack was not healthy within ${BOUND}s."
    docker compose ps --all >&2 || true
    exit 1
  fi
  if docker compose up --detach --no-recreate --wait --wait-timeout "$remaining"; then
    break
  fi
  # `up --wait` returns as soon as a service exits: note each one that did, then start the stack again.
  exited=0
  for service in $(docker compose config --services); do
    container=$(docker compose ps --all --quiet "$service")
    [ -n "$container" ] || continue
    status=$(docker inspect --format '{{.State.Status}} {{.State.ExitCode}}' "$container")
    if [ "${status%% *}" != "exited" ]; then
      continue
    fi
    exited=1
    count=$(( ${starts[$service]:-1} + 1 ))
    starts[$service]=$count
    lines=$(docker logs --tail "$TAIL" "$container" 2>&1 || true)
    if [ "$count" -gt "$MAX_STARTS" ]; then
      report "$(printf '### %s exited %s times while the stack started (exit %s)\n\n```\n%s\n```\n' \
        "$service" "$MAX_STARTS" "${status#* }" "$lines")"
      exit 1
    fi
    restarted+=("$service")
    report "$(printf '### %s exited while the stack started (exit %s); starting it again (start %s of at most %s)\n\nThe last %s lines of the run that exited:\n\n```\n%s\n```\n' \
      "$service" "${status#* }" "$count" "$MAX_STARTS" "$TAIL" "$lines")"
  done
  if [ "$exited" -eq 0 ]; then
    # Nothing exited: up failed for another reason (a health check that never passed, a dependency): no allowance.
    docker compose ps --all >&2 || true
    exit 1
  fi
done

if [ "${#restarted[@]}" -gt 0 ]; then
  report "The stack is healthy; started again while it started: ${restarted[*]} (Ahoo-Wang/CoCache#1009)."
else
  echo "The stack is healthy; no service was started more than once." >&2
fi
