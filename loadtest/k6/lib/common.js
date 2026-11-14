// Shared settings and helpers for the Event Manager k6 scripts.
import http from "k6/http";
import { check, fail } from "k6";

/** Backend origin. run.sh sets http://app:8080 (the Compose network); default is for a local k6. */
export const BASE_URL = (__ENV.BASE_URL || "http://localhost:8080").replace(/\/$/, "");
export const API = `${BASE_URL}/api/v1`;

export const JSON_HEADERS = { "Content-Type": "application/json" };

export function authHeaders(token) {
  return { ...JSON_HEADERS, Authorization: `Bearer ${token}` };
}

/**
 * Load shapes, chosen with PROFILE=smoke|load|stress|spike (default smoke). Each stays well under
 * 30 minutes, the JWT lifetime: tokens are fetched once in setup() and aren't refreshed.
 */
export const PROFILES = {
  // A sanity check: does every request work at all?
  smoke: { executor: "constant-vus", vus: 1, duration: "30s" },
  // Typical traffic: ramp up, hold, ramp down.
  load: {
    executor: "ramping-vus",
    startVUs: 0,
    stages: [
      { duration: "1m", target: 20 },
      { duration: "3m", target: 20 },
      { duration: "30s", target: 0 },
    ],
  },
  // Step up past normal traffic to find where latency or errors start to climb.
  stress: {
    executor: "ramping-vus",
    startVUs: 0,
    stages: [
      { duration: "1m", target: 50 },
      { duration: "2m", target: 50 },
      { duration: "1m", target: 100 },
      { duration: "2m", target: 100 },
      { duration: "1m", target: 150 },
      { duration: "2m", target: 150 },
      { duration: "1m", target: 0 },
    ],
  },
  // A sudden burst, e.g. tickets going on sale, then back to normal.
  spike: {
    executor: "ramping-vus",
    startVUs: 5,
    stages: [
      { duration: "30s", target: 5 },
      { duration: "10s", target: 150 },
      { duration: "1m", target: 150 },
      { duration: "10s", target: 5 },
      { duration: "30s", target: 5 },
    ],
  },
};

export function scenarioFor(defaultProfile = "smoke") {
  const name = __ENV.PROFILE || defaultProfile;
  const profile = PROFILES[name];
  if (!profile) {
    throw new Error(`Unknown PROFILE "${name}"; use one of: ${Object.keys(PROFILES).join(", ")}`);
  }
  return profile;
}

/** Whole number from an environment variable, or the fallback. */
export function intEnv(name, fallback) {
  const value = parseInt(__ENV[name], 10);
  return Number.isFinite(value) ? value : fallback;
}

/**
 * Logs in as each of `count` load-test users (k6-user-1, k6-user-2, ...), registering any that
 * don't exist yet, and returns their tokens. Called from setup(), so the slow BCrypt password check
 * happens once per user rather than on every iteration.
 */
export function loginUsers(count) {
  const password = __ENV.K6_PASSWORD || "k6-password-123";
  const tokens = [];
  for (let i = 1; i <= count; i++) {
    const username = `k6-user-${i}`;
    let res = http.post(`${API}/auth/login`, JSON.stringify({ username, password }), {
      headers: JSON_HEADERS,
      // 401 just means the user doesn't exist yet (first run): not a failed request.
      responseCallback: http.expectedStatuses(200, 401),
      tags: { name: "setup login" },
    });
    if (res.status === 401) {
      res = http.post(
        `${API}/auth/register`,
        JSON.stringify({ username, email: `${username}@loadtest.local`, password }),
        { headers: JSON_HEADERS, tags: { name: "setup register" } },
      );
    }
    if (!check(res, { "setup: logged in or registered": (r) => r.status === 200 || r.status === 201 })) {
      fail(`Could not log in or register ${username}: HTTP ${res.status} ${res.body}`);
    }
    tokens.push(res.json("token"));
  }
  return tokens;
}

/** Ids of every event, read once in setup() so the scripts work with whatever data exists. */
export function eventIds() {
  const res = http.get(`${API}/events`, { tags: { name: "setup events" } });
  if (res.status !== 200) fail(`GET /events failed: HTTP ${res.status}`);
  const ids = res.json().map((e) => e.id);
  if (ids.length === 0) fail("No events exist; create one first (POST /api/v1/events as an admin).");
  return ids;
}

export function pick(list) {
  return list[Math.floor(Math.random() * list.length)];
}
