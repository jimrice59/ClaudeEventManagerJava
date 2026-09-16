---
paths:
  - "src/main/java/com/eventmanager/**/*.java"
---

# Dependency Injection

Three DI forms are used, each for a different reason:

**Constructor injection via `@RequiredArgsConstructor` (Lombok) — primary pattern**
Every service, controller, and security class declares dependencies as `private final` fields. Lombok generates the constructor; Spring injects the beans at startup. Dependencies are immutable and the class is testable without a Spring context (just call `new` with mocks).

| Class | Injected dependencies |
|---|---|
| `AuthController` | `AuthService` |
| `EventController` | `EventService`, `TicketService` |
| `VenueController` | `VenueService` |
| `PerformerController` | `PerformerService` |
| `TicketController` | `TicketService` |
| `AuthService` | `AuthenticationManager`, `UserRepository`, `PasswordEncoder`, `JwtTokenProvider` |
| `EventService` | `EventRepository`, `VenueRepository`, `PerformerRepository`, `VenueService`, `PerformerService`, `CassandraAsyncWriter`, `TicketService` |
| `VenueService` | `VenueRepository` |
| `PerformerService` | `PerformerRepository`, `VideoRepository`, `CassandraAsyncWriter`, `PerformerVideoEventPublisher` |
| `TicketService` | `TicketRepository`, `EventRepository`, `UserRepository`, `TicketOperationRepository`, `CassandraAsyncWriter` |
| `UserDetailsServiceImpl` | `UserRepository` |
| `JwtAuthenticationFilter` | `JwtTokenProvider`, `UserDetailsServiceImpl` |
| `SecurityConfig` | `UserDetailsServiceImpl`, `JwtAuthenticationFilter` |

**Field injection via `@Autowired(required = false)` — optional infrastructure beans**
`CassandraAsyncWriter` holds three optional repositories (`PerformerCassandraRepository`, `EventCassandraRepository`, `TicketOperationCassandraRepository`), all field-injected with `required = false`. `PerformerVideoEventPublisher` holds an optional `KafkaTemplate<String, VideoEvent>`, also field-injected with `required = false`. `TicketService` holds an optional `TicketCassandraRepository` the same way — field-injected directly rather than routed through `CassandraAsyncWriter`, since the ticket backup write has to be synchronous (see cassandra-dual-write.md). Spring skips injection when the beans are absent (test profile excludes Cassandra and Kafka autoconfiguration). Each method short-circuits with a null check. Constructor injection cannot express optionality without an `Optional<>` wrapper.

**`@Bean` factory methods in `@Configuration` classes — explicit bean registration**
`SecurityConfig` registers `PasswordEncoder`, `DaoAuthenticationProvider`, `AuthenticationManager`, and `CorsConfigurationSource` manually because they require configuration logic Spring cannot infer. `RedisConfig` registers a custom `RedisCacheManager` with JSON serialization and a 1-hour TTL, overriding Spring's default Java-serialization cache manager. `AuthorizationServerConfig` registers `RegisteredClientRepository`, `JWKSource`, `JwtDecoder`, `AuthorizationServerSettings`, and `OAuth2TokenCustomizer` — all of these have `@ConditionalOnMissingBean` in the Spring Boot auto-configuration so providing them explicitly prevents any auto-config from firing.

Spring Data JPA repositories (`EventRepository`, `VenueRepository`, etc.) are registered automatically by the `spring-boot-starter-data-jpa` infrastructure — no annotation is needed on them beyond `extends JpaRepository`. `@EnableJpaRepositories(basePackages = "com.eventmanager.repository")` on `EventManagerApplication` scopes this scan to exclude the Cassandra repository package.
