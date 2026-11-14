// Public browsing traffic: what anonymous visitors do before logging in. Read-only, so it can be
// run as often as you like without changing any data.
//
//   ./loadtest/k6/run.sh browse.js                  # smoke: 1 user, 30 s
//   PROFILE=load ./loadtest/k6/run.sh browse.js     # 20 users for ~4.5 min
//
// Each iteration is one visitor "session": the events list, a filter, one event's page with its
// ticket availability, and a look at performers or venues, with pauses in between.
import http from "k6/http";
import { check, group, sleep } from "k6";
import { API, eventIds, pick, scenarioFor } from "./lib/common.js";

export const options = {
  scenarios: { browse: scenarioFor("smoke") },
  thresholds: {
    // Fewer than 1% of requests fail, and 95% finish within half a second.
    http_req_failed: ["rate<0.01"],
    http_req_duration: ["p(95)<500"],
    // Per-page targets; the list endpoints are cached in Redis, so they should be fast.
    "http_req_duration{name:GET /events}": ["p(95)<300"],
    "http_req_duration{name:GET /events/{id}}": ["p(95)<300"],
    "http_req_duration{name:GET /events/{id}/tickets/available}": ["p(95)<500"],
    checks: ["rate>0.99"],
  },
};

export function setup() {
  const venues = http.get(`${API}/venues`).json().map((v) => v.id);
  const performers = http.get(`${API}/performers`).json().map((p) => p.id);
  return { events: eventIds(), venues, performers };
}

// The "name" tag groups URLs that differ only by id or query, so the summary and thresholds
// report one line per endpoint instead of one per URL.
function get(url, name) {
  const res = http.get(url, { tags: { name } });
  check(res, { [`${name} is 200`]: (r) => r.status === 200 });
  return res;
}

export default function (data) {
  group("events list", () => {
    get(`${API}/events`, "GET /events");
    sleep(1);
    // Visitors often narrow the list. Filters combine with AND.
    const filter = Math.random();
    if (filter < 0.4 && data.venues.length) {
      get(`${API}/events?venueId=${pick(data.venues)}`, "GET /events?venueId");
    } else if (filter < 0.7 && data.performers.length) {
      get(`${API}/events?performerId=${pick(data.performers)}`, "GET /events?performerId");
    }
  });
  sleep(1);

  const eventId = pick(data.events);
  group("event page", () => {
    get(`${API}/events/${eventId}`, "GET /events/{id}");
    get(`${API}/events/${eventId}/tickets/available/count`, "GET /events/{id}/tickets/available/count");
    // Browsing the ticket list: usually the first page, sometimes further in.
    const page = Math.random() < 0.7 ? 0 : Math.floor(Math.random() * 10);
    get(`${API}/events/${eventId}/tickets/available?page=${page}&size=20`, "GET /events/{id}/tickets/available");
  });
  sleep(2);

  group("performers and venues", () => {
    if (Math.random() < 0.5) {
      get(`${API}/performers`, "GET /performers");
      if (data.performers.length) get(`${API}/performers/${pick(data.performers)}`, "GET /performers/{id}");
    } else {
      get(`${API}/venues`, "GET /venues");
    }
  });
  sleep(1 + Math.random() * 2);
}
