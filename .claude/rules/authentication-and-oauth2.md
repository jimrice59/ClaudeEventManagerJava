---
paths:
  - "src/main/java/com/eventmanager/security/**"
  - "src/main/java/com/eventmanager/config/SecurityConfig.java"
  - "src/main/java/com/eventmanager/config/AuthorizationServerConfig.java"
  - "src/main/java/com/eventmanager/config/DevSecurityConfig.java"
---

# Authentication Flows & OAuth 2.0

## Authentication flows

**Custom JWT** (`POST /api/v1/auth/login` → `Authorization: Bearer <token>`)
`JwtTokenProvider` reads `jwt.secret` (BASE64-encoded, HMAC-SHA) and `jwt.expiration-ms` from config. Token contains only the username as subject. On each request, `JwtAuthenticationFilter` runs before `BearerTokenAuthenticationFilter`: it validates the HMAC signature, loads `UserDetails` from DB, and sets `UsernamePasswordAuthenticationToken` in the `SecurityContext`. `validateToken` catches all JJWT exceptions including `JwtException` (catch-all for algorithm mismatches when an RS256 OAuth2 token arrives) — returning false allows `BearerTokenAuthenticationFilter` to try next.

**OAuth2 `client_credentials`** (M2M — no user login):
```bash
curl -X POST http://localhost:8080/oauth2/token \
  -u event-manager-client:secret \
  -d "grant_type=client_credentials&scope=read write"
```
Returns an RS256-signed JWT with a `scope` claim (`SCOPE_read`, `SCOPE_write`). Custom JWT filter returns false (wrong algorithm) → `BearerTokenAuthenticationFilter` validates it and produces a `JwtAuthenticationToken` with `SCOPE_*` authorities.

**OAuth2 `authorization_code`** (user-delegated):
1. `GET /oauth2/authorize?response_type=code&client_id=event-manager-client&scope=openid+read+write&redirect_uri=http://localhost:8080/authorized`
2. AS redirects browser to `/login` → user logs in with username/password via form
3. AS issues auth code, redirects to `redirect_uri?code=...`
4. `POST /oauth2/token` with `grant_type=authorization_code&code=...`
5. Returns RS256 JWT with both `scope` and `roles` claims (e.g. `["ROLE_ADMIN"]`). `JwtAuthenticationConverter` maps `roles` → `ROLE_*` authorities, so `@PreAuthorize("hasRole('ADMIN')")` works.

Registered client: `clientId=event-manager-client`, `clientSecret=secret` (BCrypt-encoded). Secret rotates on restart unless externalized.

## OAuth 2.0 Authorization Server + Resource Server

`spring-boot-starter-oauth2-authorization-server` is on the classpath. The app acts as both an OAuth2 Authorization Server (issues RS256 JWTs) and a Resource Server (validates them). No external auth server is needed.

**Four security filter chains** (order matters — first match wins):

| Order | Class | `securityMatcher` | Purpose |
|---|---|---|---|
| 1 | `AuthorizationServerConfig.authorizationServerSecurityFilterChain` | AS endpoints (`/oauth2/**`, `/.well-known/**`) | Issues and manages tokens; redirects browsers to `/login` |
| 2 | `AuthorizationServerConfig.formLoginSecurityFilterChain` | `/login` | Provides form login page for `authorization_code` user authentication |
| 3 | `SecurityConfig.webFilterChain` | `/ui/**` | Session-based web UI: form login, CSRF enabled, Thymeleaf pages |
| 4 | `SecurityConfig.securityFilterChain` | everything else | Stateless API: custom JWT → OAuth2 Bearer → authorization rules |

**`AuthorizationServerConfig`** — all AS beans live here:
- `JWKSource<SecurityContext>`: RSA 2048-bit key pair generated at startup. Used to sign tokens and exposed at `/oauth2/jwks`. Rotates on every restart — externalize to a persistent key store for production.
- `JwtDecoder`: wraps the JWK source; used by both the AS internally and the `@Order(4)` Resource Server chain to validate tokens.
- `AuthorizationServerSettings`: issuer = `${OAUTH2_ISSUER:http://localhost:8080}`. Sets the `iss` claim in all tokens and the `issuer` field in the OIDC discovery document.
- `RegisteredClientRepository` (in-memory): one client — `event-manager-client` / `secret` (BCrypt), grants `client_credentials` + `authorization_code` + `refresh_token`, scopes `openid read write`, redirect URI `http://localhost:8080/authorized`.
- `OAuth2TokenCustomizer<JwtEncodingContext>`: for `authorization_code` access tokens only, loads the authenticated user's `GrantedAuthority` list and adds it as a `roles` claim (e.g. `["ROLE_ADMIN"]`). `client_credentials` tokens carry only `scope` claims (no user principal exists).

**Resource Server side** (`SecurityConfig.securityFilterChain`):
- `addFilterBefore(jwtAuthenticationFilter, BearerTokenAuthenticationFilter.class)`: custom JWT filter runs first. If it succeeds (HMAC validates, UserDetails loaded), SecurityContext is set and `BearerTokenAuthenticationFilter` skips. If the token is RS256 (OAuth2), `validateToken` returns false (JJWT's `JwtException` catch-all handles algorithm mismatches) and `BearerTokenAuthenticationFilter` takes over.
- `JwtAuthenticationConverter`: maps `scope` claim → `SCOPE_*` authorities (for `client_credentials` tokens) and `roles` claim → `ROLE_*` authorities (for `authorization_code` tokens), so `@PreAuthorize("hasRole('ADMIN')")` works for both token types.

**OIDC discovery:** `GET /.well-known/openid-configuration` — returns issuer, authorization endpoint, token endpoint, JWKS URI, supported grant types, scopes, and signing algorithms. Useful for configuring external clients.
