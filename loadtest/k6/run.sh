#!/usr/bin/env bash
# Runs a k6 script from this folder in the grafana/k6 Docker image, on the docker compose network,
# so it reaches the app as http://app:8080 (no local k6 install needed).
#
#   ./loadtest/k6/run.sh browse.js
#   PROFILE=load ./loadtest/k6/run.sh reservations.js
#   PROFILE=spike EVENT_ID=7 PURCHASE=true USERS=50 ./loadtest/k6/run.sh reservations.js
#
# Extra arguments go to `k6 run`, e.g. `--summary-export=/scripts/results/summary.json`.
set -euo pipefail

script="${1:?usage: run.sh <script.js> [k6 run options...]}"
shift
here="$(cd "$(dirname "$0")" && pwd)"
# The network the app container is on (the compose project's default network).
network="${K6_NETWORK:-$(docker inspect eventmanager-app --format '{{range $name, $_ := .NetworkSettings.Networks}}{{$name}} {{end}}' 2>/dev/null | awk '{print $1}' || true)}"
image="${K6_IMAGE:-grafana/k6:1.0.0}"

if [[ -z "$network" ]]; then
  echo "The eventmanager-app container isn't running; start the stack with 'docker compose up -d', or set K6_NETWORK." >&2
  exit 1
fi
mkdir -p "$here/results"

exec docker run --rm -i \
  --network "$network" \
  -v "$here:/scripts" \
  -e BASE_URL="${BASE_URL:-http://app:8080}" \
  -e PROFILE="${PROFILE:-smoke}" \
  -e EVENT_ID="${EVENT_ID:-}" \
  -e USERS="${USERS:-}" \
  -e PURCHASE="${PURCHASE:-false}" \
  -e K6_PASSWORD="${K6_PASSWORD:-}" \
  "$image" run "$@" "/scripts/$script"
