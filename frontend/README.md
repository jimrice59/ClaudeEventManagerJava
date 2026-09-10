# Event Manager — Frontend

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

- `/events` — list, with filters by venue or date range
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
| Reserve or release tickets | Ticket form on `/events/:id` |

The client never has to re-enter credentials for this — once logged in, every API call automatically carries the session's JWT.

Session persistence: the token and basic profile (username/email/role) are kept in the browser's `localStorage`, so refreshing the page or closing and reopening the tab keeps the client logged in. Logging out clears it. There's no server-side session to expire early — the JWT itself is valid for 24 hours from issuance regardless of browser activity.

### 4. Admin-only actions

A subset of clients — those whose account has `ROLE_ADMIN` — additionally see:

- Create / edit / delete **events**
- Create / edit / delete **performers**, including adding or removing video URLs on a performer's detail page
- Create / edit / delete **venues**

Being a plain `ROLE_USER` is not enough for any of the above, even though the account is logged in — only the ticket reserve/release action in the previous section is available to non-admin accounts. A `ROLE_USER` client who tries to reach an admin screen directly (e.g. typing `/events/new` in the address bar) is silently redirected away — the UI hides what it knows the account can't do. This is a convenience, not the actual security boundary: the backend independently rejects any unauthorized request with a 403, whether or not it came through this UI.

### 5. Errors

If a request fails — bad credentials, a validation error, insufficient tickets, a 404 — the page shows a red banner with the backend's message rather than failing silently. The client doesn't need to open dev tools to find out what went wrong.

## Quick reference: what requires what

| Screen | Anonymous | Logged in (ROLE_USER) | Admin (ROLE_ADMIN) |
|---|---|---|---|
| Browse events/performers/venues | ✅ | ✅ | ✅ |
| Register / log in | ✅ | — | — |
| Reserve/release tickets | ❌ | ✅ | ✅ |
| Create/edit/delete events | ❌ | ❌ | ✅ |
| Create/edit/delete performers, manage videos | ❌ | ❌ | ✅ |
| Create/edit/delete venues | ❌ | ❌ | ✅ |
