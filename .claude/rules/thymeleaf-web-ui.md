---
paths:
  - "src/main/java/com/eventmanager/web/**"
  - "src/main/resources/templates/**"
---

# Thymeleaf Web UI

`spring-boot-starter-thymeleaf` and `thymeleaf-extras-springsecurity6` are on the classpath. All UI pages are served under `/ui/**` by a dedicated set of `@Controller` classes in `com.eventmanager.web`. The REST API under `/api/v1/**` is entirely unchanged.

**Authentication for the web UI** is session-based and completely separate from the JWT-based REST API. When a user POSTs to `POST /ui/login`, Spring Security validates credentials against the same `UserDetailsServiceImpl` / user table, creates an `HttpSession`, and redirects to `/ui/events`. The session cookie is used for all subsequent `/ui/**` requests. CSRF protection is enabled on the web filter chain (Spring Security default); Thymeleaf injects the CSRF token automatically into all `th:action` forms.

**Web security filter chain** (`SecurityConfig.webFilterChain`, `@Order(3)`, `securityMatcher("/ui/**")`):
- `GET /ui/login` — public (login page)
- `GET /ui/events`, `GET /ui/events/{id}` — public (read-only event browsing)
- `GET /ui/venues`, `GET /ui/venues/{id}` — public
- `GET /ui/performers`, `GET /ui/performers/{id}` — public
- All other `/ui/**` — requires authentication; individual write/delete methods also use `@PreAuthorize` for role checks

**Web controllers** (`com.eventmanager.web`):

| Controller | Base path | Notes |
|---|---|---|
| `WebAuthController` | `/ui/login`, `/ui` | Login page GET; `GET /ui` redirects to `/ui/events` |
| `WebEventController` | `/ui/events` | Full CRUD; `@InitBinder` handles `datetime-local` input format |
| `WebVenueController` | `/ui/venues` | Full CRUD; city filter on list |
| `WebPerformerController` | `/ui/performers` | Full CRUD + video add/remove; name/genre filter on list |
| `WebTicketController` | `/ui/tickets` | `GET /me` (paginated "My Tickets" list) plus `POST /{id}/reserve`, `/release`, `/purchase`, `/cancel` — no admin gate, same ownership rules as the REST API |

All web controllers delegate directly to the existing services (`EventService`, `VenueService`, `PerformerService`, `TicketService`) — no duplicate business logic. Flash attributes (`RedirectAttributes.addFlashAttribute`) carry success (`successMessage`) and, for `WebTicketController` only, error (`errorMessage`) messages across the POST-redirect-GET cycle — `errorMessage` is a pattern introduced specifically for ticket actions, since `IllegalArgumentException`/`AccessDeniedException` from `TicketService` (stale status, wrong owner) are real, expected outcomes a user needs to see, not exceptional failures. `WebTicketController` catches both directly and flashes the message rather than letting them propagate to `GlobalExceptionHandler`, which (being a `@RestControllerAdvice` with no package/type scoping) applies to every controller app-wide including these `@Controller` classes, and would otherwise hand the browser a raw JSON body instead of a redirect back to an HTML page.

**`datetime-local` binding** — HTML `<input type="datetime-local">` produces values in the format `yyyy-MM-dd'T'HH:mm` (no seconds). Spring MVC's default `LocalDateTime` converter expects full ISO-8601. `WebEventController` registers a `PropertyEditorSupport` via `@InitBinder` that parses and formats `LocalDateTime` using `DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")`, avoiding any change to `EventRequest` or application.yml.

**Delete pattern** — HTML forms only support GET and POST. Delete actions use `POST /ui/{entity}/{id}/delete` with a JavaScript `confirm()` dialog on the submit button. No `HiddenHttpMethodFilter` is needed.

**Thymeleaf templates** (`src/main/resources/templates/`):

| File | Purpose |
|---|---|
| `login.html` | Standalone login card; no nav |
| `fragments/nav.html` | `th:fragment="nav"` — Bootstrap 5 navbar included in every page via `th:replace`; uses `sec:authorize` to show/hide Login vs. username + Logout, and to show a "My Tickets" link only when authenticated |
| `events/list.html` | Card grid of all events; Create button visible to authenticated users |
| `events/view.html` | Event detail: venue link, performer badges, ticket counts (total + live available count), and a paginated "Available Tickets" list with a per-ticket Reserve form (any authenticated user; anonymous visitors see a "Log in to reserve" link instead) |
| `events/form.html` | Create/edit form; venue select dropdown, performer checkboxes; reused for both create (`POST /ui/events`) and edit (`POST /ui/events/{id}/edit`) via conditional `th:action` |
| `venues/list.html` | Table with city filter form |
| `venues/view.html` | Venue detail |
| `venues/form.html` | Create/edit form |
| `performers/list.html` | Card grid with name/genre search |
| `performers/view.html` | Performer detail; admin panel for adding/removing video URLs inline |
| `performers/form.html` | Create/edit form (name, genre, bio only — videos managed on the view page) |
| `tickets/my-tickets.html` | Paginated list of the current user's own tickets across all events; Purchase (inline `userCredentials` text input) + Release forms for `RESERVED` tickets, Cancel for `SOLD` ones; empty state and `successMessage`/`errorMessage` banners |

**`sec:authorize` in templates** — `thymeleaf-extras-springsecurity6` provides the `sec:` namespace. Admin-only buttons (Create venue, Edit/Delete venue, Edit/Delete performer, video management panel) are wrapped in `sec:authorize="hasRole('ADMIN')"` so they are not rendered for non-admin users. The server-side `@PreAuthorize` annotation on each controller method enforces the same rules independently.

**URL summary:**

| Method | Path | Auth | Action |
|---|---|---|---|
| GET | `/ui` | public | Redirect to `/ui/events` |
| GET | `/ui/login` | public | Login page |
| POST | `/ui/login` | public | Spring Security processes credentials |
| POST | `/ui/logout` | authenticated | Invalidates session |
| GET | `/ui/events` | public | List all events |
| GET | `/ui/events/{id}` | public | View event |
| GET | `/ui/events/new` | ADMIN | New event form |
| POST | `/ui/events` | ADMIN | Create event |
| GET | `/ui/events/{id}/edit` | ADMIN | Edit form pre-filled from existing event |
| POST | `/ui/events/{id}/edit` | ADMIN | Update event |
| POST | `/ui/events/{id}/delete` | ADMIN | Delete event |
| GET | `/ui/venues` | public | List venues (optional `?city=`) |
| GET | `/ui/venues/{id}` | public | View venue |
| GET | `/ui/venues/new` | ADMIN | New venue form |
| POST | `/ui/venues` | ADMIN | Create venue |
| GET | `/ui/venues/{id}/edit` | ADMIN | Edit form |
| POST | `/ui/venues/{id}/edit` | ADMIN | Update venue |
| POST | `/ui/venues/{id}/delete` | ADMIN | Delete venue |
| GET | `/ui/performers` | public | List performers (optional `?name=` or `?genre=`) |
| GET | `/ui/performers/{id}` | public | View performer; admin video panel |
| GET | `/ui/performers/new` | ADMIN | New performer form |
| POST | `/ui/performers` | ADMIN | Create performer |
| GET | `/ui/performers/{id}/edit` | ADMIN | Edit form |
| POST | `/ui/performers/{id}/edit` | ADMIN | Update performer |
| POST | `/ui/performers/{id}/videos/add` | ADMIN | Add video URL |
| POST | `/ui/performers/{id}/videos/delete` | ADMIN | Remove video URL |
| POST | `/ui/performers/{id}/delete` | ADMIN | Delete performer |
| GET | `/ui/tickets/me` | authenticated | Paginated list of the caller's own tickets (`?page=&size=`) |
| POST | `/ui/tickets/{id}/reserve` | authenticated | Body: `eventId` (hidden field, so both success and failure redirect back to the event page); redirects to `/ui/events/{eventId}` |
| POST | `/ui/tickets/{id}/release` | authenticated | Redirects to `/ui/tickets/me` |
| POST | `/ui/tickets/{id}/purchase` | authenticated | Body: `userCredentials`; redirects to `/ui/tickets/me` |
| POST | `/ui/tickets/{id}/cancel` | authenticated | Redirects to `/ui/tickets/me` |
