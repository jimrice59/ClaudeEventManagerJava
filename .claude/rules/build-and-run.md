# Build & Run Commands

**Java version requirement:** Lombok 1.18.32 is incompatible with Java 24. Always use Java 21.

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home
export MVN=/Users/jimrice1959/.sdkman/candidates/maven/current/bin/mvn
```

```bash
# App only — starts infrastructure + app container (port 8080); no Traefik
docker compose up -d

# App + Traefik — adds Traefik reverse proxy (port 80) and dashboard (port 9000)
docker compose --profile traefik up -d

# Compile
JAVA_HOME=... $MVN compile

# Run application locally (against docker compose infrastructure only)
JAVA_HOME=... $MVN spring-boot:run

# Run all tests (requires H2; Redis/Postgres not needed for tests)
JAVA_HOME=... $MVN test

# Run a single test class
JAVA_HOME=... $MVN test -Dtest=VenueServiceTest
JAVA_HOME=... $MVN test -Dtest=EventServiceTest
JAVA_HOME=... $MVN test -Dtest=PerformerServiceTest

# Package jar
JAVA_HOME=... $MVN package -DskipTests

# Run packaged jar
java -jar target/event-manager-1.0.0.jar
```

## Docker

```bash
# Build image (~137 MB content size)
docker build -t event-manager .

# Run against the compose stack (dependencies on host)
docker run -p 8080:8080 \
  -e DB_HOST=host.docker.internal \
  -e REDIS_HOST=host.docker.internal \
  -e CASSANDRA_HOST=host.docker.internal \
  -e KAFKA_BOOTSTRAP_SERVERS=host.docker.internal:9092 \
  event-manager
```

The Dockerfile is a two-stage build:
- **Build stage** (`maven:3.9-eclipse-temurin-21`): `pom.xml` is copied first and `dependency:go-offline` is run as a separate layer, so Maven dependencies are cached between builds and only re-downloaded when `pom.xml` changes.
- **Runtime stage** (`eclipse-temurin:21-jre-alpine`): copies only the packaged jar into a minimal Alpine JRE image. No JDK, no Maven, no source in the final image.

`.dockerignore` excludes `target/`, `.git/`, `.claude/`, `*.md`, and `dump.rdb`.

All config values default to localhost with `postgres/postgres` credentials. Override via env vars: `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD`, `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD`, `CASSANDRA_HOST`, `CASSANDRA_PORT`, `CASSANDRA_KEYSPACE`, `CASSANDRA_DATACENTER`, `KAFKA_BOOTSTRAP_SERVERS`, `JWT_SECRET`, `JWT_EXPIRATION_MS`, `OAUTH2_ISSUER`.

`docker compose up -d` also starts Prometheus (port 9090) and Grafana (port 3000, `admin`/`admin`). Prometheus scrapes `/actuator/prometheus` from both `app:8080` (docker-compose mode) and `host.docker.internal:8080` (local `mvn spring-boot:run` mode) — unreachable targets show as DOWN without affecting the other. In Grafana, add `http://prometheus:9090` as a Prometheus data source and import dashboard ID **4701** (JVM Micrometer) for HTTP and JVM metrics.

**Traefik** is optional, activated via the `traefik` profile:

| Command | What starts | App access |
|---|---|---|
| `docker compose up -d` | Infrastructure + app | `http://localhost:8080` |
| `docker compose --profile traefik up -d` | Infrastructure + app + Traefik | `http://localhost` (port 80) or `http://localhost:8080` (direct) |

Traefik dashboard: `http://localhost:9000` (only when running with the `traefik` profile). Traefik auto-discovers the `app` container via Docker labels; `exposedbydefault=false` ensures only labeled services are routed. Scale with `docker compose --profile traefik up -d --scale app=3` — Traefik load-balances across all instances automatically.

## Connecting to PostgreSQL

**Via Docker (always works, no local psql required):**

```bash
docker exec -it eventmanager-postgres psql -U postgres -d eventdb
```

**From the host with a local psql install:**

```bash
psql -h localhost -p 5432 -U postgres -d eventdb
```

If this connects to a local PostgreSQL instead of the Docker container (error: `role "postgres" does not exist`), another process is already bound to port 5432. Check with `lsof -i :5432`. Stop the local instance first:

```bash
brew services stop postgresql@16   # adjust version as needed
```

**Useful psql commands once connected:**

```sql
\dt                          -- list tables
\d users                     -- describe the users table
SELECT * FROM users;
SELECT * FROM events;
SELECT * FROM venues;
SELECT * FROM performers;
\q                           -- quit
```

## Development test users

The `POST /api/v1/auth/register` endpoint always creates users as `ROLE_USER`. There is no API path to create an admin — the role must be updated directly in the database after registration.

**Create both users via the register endpoint:**

```bash
# Regular user
curl -X POST http://localhost:8080/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username":"user1","email":"user1@example.com","password":"password123"}'

# Admin (registers as ROLE_USER; promoted in the next step)
curl -X POST http://localhost:8080/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","email":"admin@example.com","password":"password123"}'
```

**Promote the admin user:**

```bash
docker exec -it eventmanager-postgres psql -U postgres -d eventdb
```

```sql
UPDATE users SET role = 'ROLE_ADMIN' WHERE username = 'admin';

-- verify
SELECT id, username, email, role, created_at FROM users;
```

Passwords are BCrypt-encoded by the app. Inserting rows directly via SQL would require pre-computing a BCrypt hash; using the register endpoint and then flipping the role column is the correct approach.

**Login and use the token:**

```bash
# Login and capture token
TOKEN=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"password123"}' | jq -r '.token')

# Use token in subsequent requests
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/events
```

## Disabling authentication for development

`DevSecurityConfig` (`com.eventmanager.config`, `@Profile("dev")`) defines a single `@Order(0)` filter chain that matches `/**` and calls `permitAll()` with CSRF disabled. Because `@Order(0)` is lower than all production chains (orders 1–4), it intercepts every request before any auth logic runs. The production `SecurityConfig` and `AuthorizationServerConfig` beans still load — they just never see any traffic.

Activate with any of:

```bash
# Maven
JAVA_HOME=... $MVN spring-boot:run -Dspring-boot.run.profiles=dev

# Packaged jar
java -Dspring.profiles.active=dev -jar target/event-manager-1.0.0.jar

# Environment variable (shell or docker compose)
SPRING_PROFILES_ACTIVE=dev
```

`application-dev.yml` is also loaded when the profile is active. It enables SQL logging (`spring.jpa.show-sql: true`) and sets `DEBUG` level for `com.eventmanager` and `org.springframework.security`. To re-enable authentication, remove the profile flag — no code changes needed.
