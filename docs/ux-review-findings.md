# UX Review Findings

A review of the two user-facing surfaces (the React SPA in `frontend/src` and the server-rendered
Thymeleaf UI in `src/main/resources/templates` + `src/main/java/com/eventmanager/web/*`) plus
API-wide error handling, looking for concrete rough edges that hurt the end-user experience.

**Last updated 2026-11-10.** The original headline finding (no ticket flow in either UI) has
since been resolved in both UIs — see [Resolved](#resolved) at the end. Line numbers below were
re-checked against the current code on that date.

## Priority summary

### High-impact
- **Login discards the user's destination (Thymeleaf).** `SecurityConfig.java:84` uses
  `.defaultSuccessUrl("/ui/events", true)` — the `true` forces every login to land on the events
  list, even if the user was redirected from, say, an edit page. The React SPA does this
  correctly (preserves `from`); Thymeleaf doesn't.
- **No registration page in the Thymeleaf UI.** Only `login.html` exists — a visitor to the
  server-rendered UI has no way to create an account at all (the SPA has a full `RegisterPage`).
- **Expired JWTs go undetected in the SPA.** No 401 response interceptor in `api/client.ts` —
  `AuthContext` trusts `localStorage` independent of token validity, so the nav bar keeps showing
  "logged in" after the token has actually expired server-side. Every subsequent call just shows
  a generic error banner; the user has to manually log out and back in themselves.
- **Uncaught exceptions in the Thymeleaf UI return a raw JSON error body instead of an HTML
  page** — e.g. a bad event id (`GET /ui/events/99999`) returns `{"status":404,"message":"Event
  not found with id: '99999'"}` straight to the browser instead of a styled 404 page. This is
  because `GlobalExceptionHandler` is a `@RestControllerAdvice` with no package/type scoping, so
  it applies to every controller app-wide — including the `@Controller` classes under
  `com.eventmanager.web` — and its handlers always return a JSON `ResponseEntity`, regardless of
  which controller threw. (Confirmed by direct request — an earlier pass of this review
  mis-attributed this to Spring Boot's Whitelabel Error Page, which is not what actually renders
  here.) There's no `/ui/**`-specific error template or a scoped `@ControllerAdvice` to intercept
  it first.

### Medium
- **Form validation is server-side only in several spots**, so mistakes only surface after
  submit via a generic error banner: `EventFormPage`'s "Tickets total" field has no max and no
  hint that it can't exceed venue capacity; `PerformerFormPage`/`VenueFormPage` have no
  required-field asterisks despite `@NotBlank` constraints (the Thymeleaf forms do have
  asterisks — this is an SPA-only gap).
- **Delete is a hard, un-undoable action everywhere** (both UIs) — native `confirm()` dialogs
  guard it, but there's no soft-delete/trash/undo window.
- **Detail pages collapse to a bare error banner on load failure** (`EventDetailPage`,
  `PerformerDetailPage`) — the whole page chrome disappears with no retry button, just browser
  back.
- **Loading states are a plain "Loading..." string**, no spinner, across all three list pages
  (and `MyTicketsPage`).
- **A ticket stuck in `PAYING` is a dead end for the user in both UIs.** When the payment-service
  call fails or times out, the ticket is deliberately left `PAYING` (releasing it could
  double-sell a ticket that was actually charged), and the backend rejects any further purchase
  attempt with 409. Nothing reconciles these tickets yet — `PaymentServiceClient.getPaymentStatus`
  exists but is never called (see docs/payment-service-integration.md, "Reconciling a stuck PAYING
  ticket"). Both UIs now say so honestly (contact support), but the underlying fix is a backend
  sweep, not a UI change.

### Low-effort polish
- No focus management after client-side route changes (SPA) — screen-reader/keyboard users get
  no cue a new page loaded.
- `extractErrorMessage` falls back to raw Axios error text ("Network Error") on connection
  failures instead of a friendly message.
- The Thymeleaf purchase form (`tickets/my-tickets.html:38-39`) asks for a payment token without
  showing the price being charged; the cancel confirmation (`my-tickets.html:48`) says "This
  cannot be undone", but a cancelled ticket simply returns to `AVAILABLE` and can be bought again.
  (The SPA fixed both of these in its own `PurchasePanel`/`MyTicketsPage`.)
- The Thymeleaf purchase path never sends a `paymentRequestId` (`WebTicketController.java:73`),
  so a browser-level resubmit (back + resubmit, a double-click before the redirect) becomes a new
  payment attempt rather than a retry of the same one. The backend's PAYING→409 guard limits the
  damage to the window before the first attempt reaches `beginPayment`; a hidden-field id
  generated when the page renders would close it, matching the SPA.
- `ErrorResponse.timestamp()` computes `LocalDateTime.now()` at serialization time rather than
  capturing when the error actually occurred — harmless but slightly misleading if you ever
  log/compare it.

---

## Detailed findings by surface

### React SPA (`frontend/src`)

**Loading/error states**
- `EventsPage.tsx:99-101` / `PerformersPage.tsx:80-82` / `VenuesPage.tsx:83-85`: loading state is
  a bare `<p>Loading...</p>` with no spinner; the previous list stays in state so the error
  banner and the "Loading..." text can appear stacked oddly, but on filter-error the stale list
  from before is simply cleared to a blank grid with no distinguishing message.
- `EventDetailPage.tsx:83-84` / `PerformerDetailPage.tsx:78-79`: if load fails, the whole page
  collapses to just an `ErrorBanner` — the page chrome (title, nav-back) disappears, effectively
  a dead end with no retry button, only browser back.
- `client.ts:31-41` `extractErrorMessage`: falls back to `error.message` (raw Axios message,
  e.g. "Network Error" or "Request failed with status code 500") when the backend doesn't send a
  `message` field — this happens for the generic 500 handler's body (which *does* send "An
  unexpected error occurred", so it's mostly fine) but for network failures/timeouts the user
  sees a raw Axios string.

**Form validation**
- `EventFormPage.tsx:115-122`: `ticketPrice` has `min={0} max={10000}` matching backend, but
  `ticketsTotal` (`EventFormPage.tsx:126-133`) has only `min={0}` with no `max`, and no inline
  hint that it can't exceed the venue's capacity (a real backend constraint per
  `EventController.java:86-88`) — the error only appears after submit via the generic error
  banner.
- `PerformerFormPage.tsx:56-65`: no `required` marker or asterisk on Name despite it being
  required server-side (`@NotBlank`); Genre/Bio have no length hints at all.
- `VenueFormPage.tsx:68-91`: none of the required fields (name/address/city/state) show an
  asterisk or visual required indicator (unlike the Thymeleaf equivalent which uses
  `<span class="text-danger">*</span>`), and there's no client-side state-code length hint.
- `EventsPage.tsx:46-49`: the date-range filter silently no-ops if only "From" or only "To" is
  filled — no message telling the user both are needed.

**Confirmation/undo**
- Delete actions (`EventDetailPage.tsx:55`, `PerformerDetailPage.tsx:66`, `VenuesPage.tsx:46`)
  use a native `confirm()` — functional but not styled/branded, and there is zero undo after
  confirming (immediate hard delete, `navigate(..., {replace:true})`).

**Accessibility**
- `PerformerDetailPage.tsx:104-116`: video links render the raw URL as link text with no
  descriptive label; `NavBar.tsx` logout is a plain `<button>` with no `aria-label` — fine, but
  role/username text (`NavBar.tsx:27`) uses parentheses text as the only role indicator (minor;
  no icon/color redundancy needed here).
- No `alt` text concerns since no `<img>` tags exist in the SPA, but this also means
  performer/venue/event cards are text-only with no visual identity.
- No focus management after route changes (`App.tsx`): after navigating (e.g., after
  delete/save `navigate(...)`), focus stays wherever it was (often on a now-unmounted button),
  so screen-reader/keyboard users get no cue of the new page/heading.

**Auth/session UX**
- `api/client.ts`: no response interceptor for 401 — an expired token doesn't trigger logout or
  redirect to `/login`. `AuthContext.tsx:23-27` (`loadStoredUser`) trusts `localStorage` state
  independent of token validity, so the NavBar keeps showing the user as "logged in" after the
  JWT actually expires server-side; each subsequent API call just shows the generic banner
  "Invalid credentials or session expired." (`client.ts:35`) with no automatic recovery — user
  must manually click "Log out" then log back in.
- Logout (`NavBar.tsx:8-11`) has no confirmation, which is reasonable, but also doesn't clear
  cached list data on other loaded pages/hooks so stale admin-only UI (Edit/Delete buttons) may
  flash briefly since `isAdmin` re-renders lag.

### Thymeleaf UI (`src/main/resources/templates` + `web/*`)

- **No registration page**: only `login.html` exists; there is no `/ui/register`, so a new
  visitor to the server-rendered UI has no way to create an account (inconsistent with the SPA,
  which has full `RegisterPage.tsx`) — an actual dead end.
- **Uncaught exceptions return raw JSON instead of a styled page**: `GlobalExceptionHandler` is
  a `@RestControllerAdvice` with no scoping, so it's global — it catches exceptions from
  `com.eventmanager.web`'s plain `@Controller` classes too, not just the REST API. A bad event id
  (`WebEventController.view()` → `eventService.getEventById(id)` throwing
  `ResourceNotFoundException`) returns `{"status":404,"message":"Event not found with id:
  '999'"}` as raw JSON straight to the browser — not Spring Boot's Whitelabel Error Page (verified
  directly; an earlier draft of this review guessed Whitelabel from reading the code, which was
  wrong), but still a jarring, unstyled JSON blob instead of the Bootstrap-themed 404 a user of
  this UI would expect.
- **Login always redirects to a fixed page, discarding intended destination**:
  `SecurityConfig.java:84` `.defaultSuccessUrl("/ui/events", true)` — the `true` (alwaysUse)
  means that even if an unauthenticated user was redirected to login from, say,
  `/ui/events/7/edit`, after login they land on `/ui/events`, not back on the edit page they
  wanted — silently dropping their original destination (contrast with the SPA's
  `AdminRoute`/`LoginPage`, which does preserve and restore `from`).
- Delete actions across `events/list.html:37`, `events/view.html:65`, `performers/list.html:42`,
  `performers/view.html:28`, `venues/list.html:44`, `venues/view.html:36` all use `onsubmit="return confirm(...)"` — good confirmation
  coverage, but no undo once confirmed (hard delete via `eventService.deleteEvent`/etc., no
  soft-delete/trash).
- `WebPerformerController.java:60-61`/`82-83` and `WebVenueController.java:52`: on validation failure
  (`result.hasErrors()`), the controller returns `"performers/form"`/`"venues/form"` directly
  without re-adding any needed model attributes — for events (`WebEventController.java:100-101`,
  `129-131`) this is done correctly via `populateFormModel`, so it's fine there, but it's a
  subtle asymmetry worth flagging if performer/venue forms ever add dropdown data.

### API-wide (`GlobalExceptionHandler.java`)

- `GlobalExceptionHandler.java:51-55`: `IllegalArgumentException` handler returns
  `ex.getMessage()` verbatim to the client — if that message ever originates from a
  library/framework exception rather than a deliberately-crafted service message, it could leak
  internal wording to API consumers.
- `GlobalExceptionHandler.java:71-75`: generic `Exception` handler correctly returns a friendly
  "An unexpected error occurred" with no stack trace — good. As noted above, this handler (like
  every handler in this class) is *not* scoped to the REST API — `@RestControllerAdvice` with no
  `basePackages`/`assignableTypes` applies app-wide, which is exactly why the Thymeleaf
  raw-JSON-response issue above happens: the same handlers that serve `/api/v1/**` also catch
  exceptions thrown by `/ui/**` controllers, just with a JSON body instead of an HTML page.
- `ErrorResponse` record's `timestamp()` (lines 78-82) computes `LocalDateTime.now()` at
  *serialization* time inside the record accessor rather than storing the value from when the
  error occurred — cosmetic/harmless for users but worth noting as it means the timestamp
  reflects response-writing time, not error time.

---

## Resolved

- **No way to get a ticket in either UI** (originally the headline finding). Both UIs now have
  the full reserve → purchase → release/cancel flow:
  - *React SPA:* `EventDetailPage` lists an event's `AVAILABLE` tickets (paginated, via the
    previously-unused `GET /events/{id}/tickets/available`) with a per-ticket Reserve button;
    `MyTicketsPage` (`/my-tickets`, linked from the nav bar) lists the user's own tickets with
    Purchase/Release/Cancel. Purchase opens an inline checkout (`components/PurchasePanel.tsx`)
    showing the price, reusing one `paymentRequestId` across retries, and distinguishing
    approved / declined / payment-service-unreachable / state-changed outcomes; `PAYING` tickets
    show an explanation instead of a dead button.
  - *Thymeleaf:* `events/view.html` lists available tickets with Reserve; `WebTicketController` +
    `tickets/my-tickets.html` (`/ui/tickets/me`) cover purchase/release/cancel with pagination.
    The remaining Thymeleaf issues with this flow are listed above.
- **SPA "Available now" count had no action attached** (`EventDetailPage`) — the count now sits
  above the browsable available-ticket list.
- **No pagination anywhere in either UI** — both ticket lists (per-event available tickets and My
  Tickets) are paginated in both UIs. Events/performers/venues lists remain unpaginated, but their
  backend endpoints aren't paginated either.
- **A declined payment showed "Ticket purchased!" in the Thymeleaf UI**, and its 502 message
  promised the ticket would resolve "shortly". `WebTicketController.purchase()` now reads the
  returned status — `SOLD` flashes success, anything else flashes a decline message — and the 502
  message explains the ticket is held as `PAYING` and needs support. Covered by
  `WebTicketControllerTest`.
