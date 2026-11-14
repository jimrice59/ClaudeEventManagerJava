# Load testing with k6

[Grafana k6](https://grafana.com/docs/k6/latest/) scripts that put load on the Event Manager backend.
They run against the docker compose stack; no local k6 install is needed.

| Script | Simulates | Changes data? |
|---|---|---|
| `browse.js` | anonymous visitors: events list and filters, an event's page and ticket availability, performers, venues | no |
| `reservations.js` | logged-in users reserving the next available ticket for an event, then releasing it; with `PURCHASE=true`, buying it and cancelling the purchase instead | default: no. `PURCHASE=true`: adds transaction rows (see below) |

## Running

Start the stack first (`docker compose up -d`), then from the project root:

```bash
./loadtest/k6/run.sh browse.js                                   # smoke test: 1 user, 30 s
PROFILE=load ./loadtest/k6/run.sh browse.js                       # 20 users, ~4.5 min
PROFILE=spike EVENT_ID=7 ./loadtest/k6/run.sh reservations.js     # on-sale rush, up to 150 users
PROFILE=load PURCHASE=true ./loadtest/k6/run.sh reservations.js   # include purchase + cancel
```

`run.sh` runs the `grafana/k6` Docker image on the compose network, where the app is
`http://app:8080`. Extra arguments go to `k6 run`, for example to save a JSON summary into
`loadtest/k6/results/` (git-ignored):

```bash
./loadtest/k6/run.sh browse.js --summary-export=/scripts/results/browse.json
```

With k6 installed locally (`brew install k6`), run the scripts directly against the published port:

```bash
k6 run -e PROFILE=load -e BASE_URL=http://localhost:8080 loadtest/k6/browse.js
```

### Settings

| Variable | Default | Meaning |
|---|---|---|
| `PROFILE` | `smoke` | load shape, see below |
| `BASE_URL` | `http://app:8080` (run.sh), `http://localhost:8080` (local k6) | backend origin |
| `EVENT_ID` | the first event | `reservations.js`: the event whose tickets are reserved. Pick one with plenty of tickets |
| `USERS` | `20` | `reservations.js`: how many test accounts to log in as. Virtual users share them when there are more VUs than accounts |
| `PURCHASE` | `false` | `reservations.js`: buy and cancel instead of release |
| `K6_PASSWORD` | `k6-password-123` | password for the test accounts |
| `K6_IMAGE` | `grafana/k6:1.0.0` | k6 Docker image (run.sh) |
| `K6_NETWORK` | the app container's network | Docker network to join (run.sh) |

### Load profiles

Defined in [lib/common.js](lib/common.js). "Users" are k6 virtual users (VUs); each pauses between
steps the way a person would, so 20 VUs is roughly 20 people using the site at once, not 20
requests per second.

| Profile | Shape | Duration | Use it to |
|---|---|---|---|
| `smoke` | 1 VU | 30 s | check that every request works |
| `load` | ramp to 20 VUs, hold, ramp down | 4.5 min | measure normal performance |
| `stress` | steps of 50, 100, 150 VUs | 10 min | find where latency or errors start to rise |
| `spike` | 5 VUs, jump to 150 for a minute, back to 5 | 2.3 min | simulate tickets going on sale |

## Test accounts

`reservations.js` logs in as `k6-user-1` … `k6-user-N` in its setup step, registering any that
don't exist yet, so the slow password check happens once per account rather than per request.
The accounts are ordinary `ROLE_USER` users and stay in the database between runs. To remove them
(only once they own no tickets and have no transactions you want to keep):

```sql
DELETE FROM ticket_operations WHERE user_id IN (SELECT id FROM users WHERE username LIKE 'k6-user-%');
DELETE FROM users WHERE username LIKE 'k6-user-%';
```

## What `PURCHASE=true` leaves behind

Each iteration buys a ticket and then cancels the purchase, so the ticket is `AVAILABLE` again
afterwards. It still records a `PURCHASE` and a `CANCEL` transaction (in Postgres and Cassandra's
`ticket_operations`) for the test account, and briefly writes the ticket to Cassandra's `tickets`
table. Payments are simulated in docker compose (`PAYMENT_SERVICE_ENABLED=false`, the default
there). **Don't run it against a real payment service**: each purchase would charge the payment
token.

The default mode (reserve, then release) only touches Redis holds, which it releases; holds left
by an interrupted run expire on their own (10 minutes in docker compose).

## Pass/fail thresholds

Each script sets thresholds; k6 exits non-zero and prints `✗` next to any it misses, which makes the
scripts usable in CI.

| Script | Threshold |
|---|---|
| both | under 1% failed requests; over 99% of checks pass |
| `browse.js` | 95% of requests under 500 ms; the events list and event page under 300 ms; the available-tickets page under 500 ms |
| `reservations.js` | 95% of reserve-next calls under 800 ms; over 95% of reserve attempts succeed; with `PURCHASE=true`, 95% of purchases under 1.5 s |

In `reservations.js`, `409` (no unheld ticket left) counts as an expected answer, not a failed
request. The script also reports `reserve_sold_out` (409s) and `reserve_unavailable` (503s: Redis,
which stores the holds, is down).

## Watching the app during a test

k6's summary shows what clients saw. To see the server side at the same time (latency per
endpoint, Postgres connection pool, Redis, Cassandra writes, memory), open Grafana while the test
runs; see [docs/monitoring-with-prometheus-grafana.md](../../docs/monitoring-with-prometheus-grafana.md).
Useful there during a reservations test: `hikaricp_connections_pending` (requests queueing for a
database connection) and the `lettuce_command_completion_seconds` Redis timings.

## Things that affect results

- **Rate limiter:** the app allows 1000 requests per second per client IP, and all k6 traffic comes
  from one IP, so beyond that you'll see `429` responses (and failed thresholds). To test past it,
  restart the app with the limiter off: `RATE_LIMIT_ENABLED=false docker compose up -d app`.
- **Token lifetime:** tokens are fetched once at the start and last 30 minutes
  (`JWT_EXPIRATION_MS`). Every profile is shorter than that; a longer custom run would start getting
  `401`s.
- **Same machine:** k6, the app, and its databases share one computer here, so results show
  relative changes (before and after a code change) better than absolute capacity.
- **Data size:** reserve-next picks the lowest-numbered free ticket, so an event with more tickets
  doesn't make it slower; but browsing deep pages of available tickets does more work than page 0.

## Results from a first run (2026-11-14)

Against docker compose on a laptop, payment simulated:

| Run | Requests | Failed | p95 latency |
|---|---|---|---|
| `browse.js`, smoke | 44 | 0 | 44 ms |
| `reservations.js`, smoke | 34 | 0 | reserve 54 ms |
| `reservations.js`, smoke, `PURCHASE=true` | 34 | 0 | reserve 56 ms, purchase 72 ms |
| `reservations.js`, spike (150 VUs), event 9 | 9,458 (3,140 reservations) | 0 | reserve 30 ms |
