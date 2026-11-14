// Signed-in users competing for tickets: the write path (Redis holds, Postgres row locks, and with
// PURCHASE=true the payment saga and Cassandra writes).
//
//   ./loadtest/k6/run.sh reservations.js                                 # smoke, reserve + release
//   PROFILE=spike EVENT_ID=7 ./loadtest/k6/run.sh reservations.js         # on-sale rush
//   PROFILE=load PURCHASE=true ./loadtest/k6/run.sh reservations.js       # also buy, then cancel
//
// Each iteration: reserve the next available ticket for the event (the server picks it), look at
// My Tickets, then either release the hold (default, leaves no data behind) or, with
// PURCHASE=true, buy the ticket and cancel the purchase.
//
// PURCHASE=true writes lasting data: every purchase and cancel adds two ticket_operations rows
// (Postgres and Cassandra) for a k6-user-N account. Tickets end up AVAILABLE again. It needs the
// payment service simulated (docker compose's default, PAYMENT_SERVICE_ENABLED=false); against a
// real payment service it would charge the payment token.
import http from "k6/http";
import { check, fail, sleep } from "k6";
import { Counter, Rate, Trend } from "k6/metrics";
import { API, authHeaders, eventIds, intEnv, loginUsers, scenarioFor } from "./lib/common.js";

const PURCHASE = (__ENV.PURCHASE || "false").toLowerCase() === "true";
const USERS = intEnv("USERS", 20);

// App-level outcomes, on top of k6's built-in HTTP metrics.
const reserveTime = new Trend("reserve_next_duration", true);
const purchaseTime = new Trend("purchase_duration", true);
const reserveSucceeded = new Rate("reserve_succeeded");
const soldOut = new Counter("reserve_sold_out"); // 409: no unheld AVAILABLE ticket left
const redisDown = new Counter("reserve_unavailable"); // 503: Redis, the hold store, is down

export const options = {
  scenarios: { reservations: scenarioFor("smoke") },
  thresholds: {
    // 409 (sold out) is an expected answer under contention, so it isn't counted as a failure; see
    // responseCallback below. Anything else unexpected is.
    http_req_failed: ["rate<0.01"],
    reserve_next_duration: ["p(95)<800"],
    reserve_succeeded: ["rate>0.95"],
    ...(PURCHASE ? { purchase_duration: ["p(95)<1500"] } : {}),
    checks: ["rate>0.99"],
  },
};

const ok = http.expectedStatuses(200, 409);

export function setup() {
  const events = eventIds();
  const eventId = intEnv("EVENT_ID", events[0]);
  if (!events.includes(eventId)) fail(`EVENT_ID ${eventId} doesn't exist; events: ${events.join(", ")}`);
  const available = http.get(`${API}/events/${eventId}/tickets/available/count`).json();
  console.log(`Event ${eventId}: ${available} tickets available. ${USERS} users, purchase=${PURCHASE}.`);
  return { eventId, tokens: loginUsers(USERS) };
}

export default function (data) {
  // Several virtual users can share an account when there are more VUs than USERS; that's fine,
  // since a user may hold several tickets.
  const headers = authHeaders(data.tokens[(__VU - 1) % data.tokens.length]);

  const reserve = http.post(`${API}/events/${data.eventId}/tickets/reserve`, null, {
    headers,
    responseCallback: ok,
    tags: { name: "POST /events/{id}/tickets/reserve" },
  });
  reserveTime.add(reserve.timings.duration);
  reserveSucceeded.add(reserve.status === 200);
  if (reserve.status === 409) soldOut.add(1);
  if (reserve.status === 503) redisDown.add(1);
  if (!check(reserve, { "reserve: 200 or sold out (409)": (r) => r.status === 200 || r.status === 409 }) || reserve.status !== 200) {
    sleep(1);
    return;
  }
  const ticketId = reserve.json("id");
  check(reserve, { "reserve: ticket is RESERVED": (r) => r.json("status") === "RESERVED" });
  sleep(1);

  const mine = http.get(`${API}/tickets/me?size=20`, { headers, tags: { name: "GET /tickets/me" } });
  check(mine, { "my tickets: 200": (r) => r.status === 200 });
  sleep(1 + Math.random());

  if (!PURCHASE) {
    const release = http.post(`${API}/tickets/${ticketId}/release`, null, {
      headers,
      tags: { name: "POST /tickets/{id}/release" },
    });
    check(release, { "release: 200": (r) => r.status === 200 });
    sleep(1);
    return;
  }

  // The server generates the paymentRequestId when it's omitted.
  const purchase = http.post(`${API}/tickets/${ticketId}/purchase`, JSON.stringify({ paymentToken: "tok_k6_loadtest" }), {
    headers,
    tags: { name: "POST /tickets/{id}/purchase" },
  });
  purchaseTime.add(purchase.timings.duration);
  if (!check(purchase, { "purchase: SOLD": (r) => r.status === 200 && r.json("status") === "SOLD" })) {
    sleep(1);
    return;
  }
  sleep(1);

  // Put the ticket back so repeated runs don't sell the event out.
  const cancel = http.post(`${API}/tickets/${ticketId}/cancel`, null, {
    headers,
    tags: { name: "POST /tickets/{id}/cancel" },
  });
  check(cancel, { "cancel: back to AVAILABLE": (r) => r.status === 200 && r.json("status") === "AVAILABLE" });
  sleep(1);
}
