---
paths:
  - "frontend/**"
---

# Frontend

`frontend/` is a standalone Vite + React 19 + TypeScript single-page app that consumes the JSON REST API under `/api/v1/**`. It is independent of the server-rendered Thymeleaf UI under `/ui/**` (see thymeleaf-web-ui.md) — same backend, two separate frontends, no shared code.

```bash
cd frontend
npm install
npm run dev      # dev server on http://localhost:5173, proxies /api -> http://localhost:8080
npm run build     # tsc -b && vite build; output in frontend/dist
```

The backend must be running on port 8080 (`mvn spring-boot:run`, `docker compose up -d`, or the `dev` profile to skip auth) before `npm run dev` will return data — the frontend has no mock/offline mode.

**Structure:**

| Path | Purpose |
|---|---|
| `src/types.ts` | TypeScript interfaces mirroring every backend DTO 1:1 (`EventRequest`, `EventResponse`, `VenueDto`, `PerformerDto`, `TicketResponse`, `PerformerSummary`, `PurchaseTicketRequest`, `PagedResponse<T>`, `VideoRequest`, `AuthResponse`, etc.) |
| `src/api/client.ts` | Axios instance with an auth interceptor that attaches `Authorization: Bearer <token>` from `localStorage`; `extractErrorMessage()` unwraps the backend's `{status, message}` / `{status, errors}` error shapes |
| `src/api/{auth,events,performers,venues,tickets}.ts` | One thin wrapper function per backend endpoint — no business logic, just typed request/response. `tickets.ts` covers `getTicket`, `getAvailableTickets` (paginated, per event), `getMyTickets` (paginated, current user), `reserveTicket`/`releaseTicket`/`purchaseTicket`/`cancelTicket` |
| `src/context/AuthContext.tsx` | Holds the logged-in user (`username`, `email`, `role`); persists token + user to `localStorage` (`event-manager.token`, `event-manager.user`) so a page refresh doesn't lose the session — there is no `GET /api/v1/auth/me` endpoint to re-fetch from |
| `src/components/ProtectedRoute.tsx` | `AdminRoute` (`ROLE_ADMIN` only) and `RequireAuth` (any authenticated user, no role check) route guards, mirroring the backend's `@PreAuthorize` rules per endpoint — `AdminRoute` gates event/performer/venue create and edit routes, `RequireAuth` gates `/my-tickets`. Both redirect to `/login` with `state={{from: location}}` when unauthenticated, so `LoginPage` can send the user back after a successful login. **Client-side only** — a UX convenience, not a security boundary; the backend re-enforces every rule independently |
| `src/pages/*` | One component per screen: `LoginPage`, `RegisterPage`, `EventsPage`/`EventDetailPage`/`EventFormPage` (create/edit share one form; `ticketsTotal` is `readOnly` in the form when editing since it's immutable; `EventDetailPage` shows the live available-ticket count via `getNumAvailableTickets` plus a paginated, browsable list of `AVAILABLE` tickets with a per-ticket Reserve button), `MyTicketsPage` (paginated list of the current user's own tickets across all events, with Purchase/Release for `RESERVED` tickets and Cancel for `SOLD` ones — the only screen that surfaces the full ticket lifecycle), `PerformersPage`/`PerformerDetailPage`/`PerformerFormPage` (video add/remove lives on the detail page, not the form), `VenuesPage`/`VenueFormPage` |

**Dev proxy vs. prod:** `vite.config.ts` proxies `/api/*` to `http://localhost:8080` in dev so no CORS configuration is needed locally. In production, set `VITE_API_BASE_URL` to the deployed backend origin (`src/api/client.ts` prepends it to `/api/v1`); if unset, requests go to the frontend's own origin, which only works behind a reverse proxy that forwards `/api` to the backend.

**Date handling:** `EventFormPage` reads/writes `<input type="datetime-local">`, which produces `yyyy-MM-ddTHH:mm` (no seconds). `EventRequest.eventDate` is built by appending `:00` before sending, matching `LocalDateTime` parsing on the backend (see `EventController`'s `@DateTimeFormat(iso = DATE_TIME)` for the equivalent list-filter parsing).
