# Foxy Ticket Service — Frontend

A Vite + React 19 + TypeScript single-page app for the Event Manager REST API (`/api/v1/**`). This document describes how a client — i.e. a person using the app in a browser — interacts with it, end to end.

For internal structure (file layout, how the API client is wired, dev proxy vs. prod config) see the [Frontend section of the root CLAUDE.md](../CLAUDE.md#frontend). This document covers the *user-facing* flow.

## Prerequisites

The backend must be running before the frontend is useful — the app has no mock or offline mode.

```bash
# from the repo root, in a separate terminal
mvn spring-boot:run
# or
docker compose up -d
```

## Running the app

```bash
npm install
npm run dev       # http://localhost:5173, proxies /api/* -> http://localhost:8080
```

For a production build: `npm run build` (output in `dist/`), then `npm run preview` to serve it locally. In a real deployment, set `VITE_API_BASE_URL` at build time to the backend's public origin (see CLAUDE.md) unless a reverse proxy already forwards `/api` to it.

## How a client uses it

### 1. Browsing — no account needed

Open `http://localhost:5173`. The landing page is the Events list. Events, Performers, and Venues are all readable without logging in:

- `/events` — list, filterable by venue, performer, and/or a date range. Fill in any combination and they all apply together. "From" or "To" alone works as an open-ended range. Results are in date order.
- `/events/:id` — detail: date, venue, price, remaining tickets, performer lineup
- `/performers` — list, filterable by name (substring) or genre (exact)
- `/performers/:id` — detail, including any video URLs on file
- `/venues` — list, filterable by city

None of these pages require a token; the client can look around freely.

### 2. Creating an account

Click **Register** in the top nav. The form asks for a username (3–50 chars), email, and password (8–100 chars). On submit, the app calls `POST /api/v1/auth/register`, which returns a JWT immediately — there's no separate email-verification or login step. The client is logged in as soon as registration succeeds.

Every new account is `ROLE_USER`. There is no self-service way to become an admin from the UI — that's a deliberate backend restriction (an operator has to flip the `role` column in the database).

Already have an account? **Log in** posts to `POST /api/v1/auth/login` instead.

### 3. What changes once logged in

The nav bar now shows the username and role, plus a **Log out** button. Concretely, being logged in (as `ROLE_USER` or `ROLE_ADMIN`) unlocks:

| Action | Where |
|---|---|
| Browse an event's available tickets and reserve a specific one | Available Tickets list on `/events/:id` |
| Reserve whichever ticket is next available, without choosing | **Reserve available ticket** on `/events/:id` |
| See every ticket you own, across all events | **My Tickets** (`/my-tickets`) in the nav bar |
| Purchase, or release, a reserved ticket | **Purchase** / **Release** on `/my-tickets` |
| Cancel a purchased ticket | **Cancel** on `/my-tickets` |

**Purchasing.** Clicking **Purchase** on a reserved ticket opens an inline checkout showing the event's ticket price and asking for a payment token (passed through to the backend's payment service). The outcome is one of:

- **Approved** — the ticket becomes `SOLD` and a confirmation banner appears.
- **Declined** — the ticket is released back to `AVAILABLE` (it disappears from My Tickets); reserve it again to retry with a different payment method.
- **Payment service unreachable / timed out** — the ticket is left `PAYING`, because the charge may have gone through even though the backend never heard back. The UI does not offer retry or release for a `PAYING` ticket (the backend rejects both, to avoid a double charge or double sale); it has to be resolved by an operator.
- **Network error before any response** — the checkout stays open and can be retried. The checkout generates one `paymentRequestId` when it opens and reuses it on every retry, so a retry of a request that did reach the backend is recognized as the same payment rather than a new one.

The client never has to re-enter credentials for this — once logged in, every API call automatically carries the session's JWT.

Session persistence: the token and basic profile (username/email/role) are kept in the browser's `localStorage`, so refreshing the page or closing and reopening the tab keeps the client logged in. Logging out clears it. There's no server-side session to expire early — the JWT itself is valid for 24 hours from issuance regardless of browser activity.

### 4. Admin-only actions

A subset of clients — those whose account has `ROLE_ADMIN` — additionally see:

- Create / edit / delete **events**
- Create / edit / delete **performers**, including adding or removing video URLs on a performer's detail page
- Create / edit / delete **venues**

Being a plain `ROLE_USER` is not enough for any of the above, even though the account is logged in — only the ticket actions in the previous section are available to non-admin accounts. A `ROLE_USER` client who tries to reach an admin screen directly (e.g. typing `/events/new` in the address bar) is silently redirected away — the UI hides what it knows the account can't do. This is a convenience, not the actual security boundary: the backend independently rejects any unauthorized request with a 403, whether or not it came through this UI.

### 5. Errors

If a request fails — bad credentials, a validation error, insufficient tickets, a 404 — the page shows a red banner with the backend's message rather than failing silently. The client doesn't need to open dev tools to find out what went wrong.

## Quick reference: what requires what

| Screen | Anonymous | Logged in (ROLE_USER) | Admin (ROLE_ADMIN) |
|---|---|---|---|
| Browse events/performers/venues | ✅ | ✅ | ✅ |
| Register / log in | ✅ | — | — |
| Reserve/release/purchase/cancel tickets, My Tickets | ❌ | ✅ | ✅ |
| Create/edit/delete events | ❌ | ❌ | ✅ |
| Create/edit/delete performers, manage videos | ❌ | ❌ | ✅ |
| Create/edit/delete venues | ❌ | ❌ | ✅ |
