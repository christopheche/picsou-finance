# Convention: REST API

## Endpoints

All REST controllers live in `com.picsou.controller` and are mapped under `/api/`.

- **Base URL:** `/api` (nginx proxies `/api/` to the backend)
- **No URL versioning** — all endpoints sit directly under `/api/` without a version segment
- **Naming:** plural resource nouns, e.g. `/api/accounts`, `/api/goals`, `/api/sync`
- **Standard HTTP verbs:** GET (read), POST (create or action), PUT (replace), PATCH (partial update), DELETE (remove)

### Auth

JWT authentication via **HttpOnly cookies with `SameSite=Lax`** — no Authorization header.

| Cookie              | TTL                       | Purpose                                                                       |
| ------------------- | ------------------------- | ----------------------------------------------------------------------------- |
| `access_token`      | 15 minutes                | Authenticates requests; carries `uid`, `role`, `tv` (token-version) claims    |
| `refresh_token`     | 7 days                    | Rotated on every use via `POST /api/auth/refresh`                             |
| `mfa_challenge_token` | 5 minutes               | Issued after correct password when 2FA is required; consumed by `POST /api/auth/mfa/verify` |
| `persistent_token`  | 90 days (configurable)    | Opaque rotating "Remember Me" token; silent re-login via `PersistentTokenAuthFilter` |

- `JwtAuthenticationFilter` reads `access_token` and populates the `SecurityContext`. It also verifies the `tv` claim against `AppUser.tokenVersion` so a password change immediately invalidates outstanding tokens.
- `PersistentTokenAuthFilter` re-issues a fresh access token from a valid `persistent_token` and rotates the persistent token on every use (theft detection: a re-used old token revokes the family).
- **Member-scoped authorization.** Every controller resolves `UserContext.currentMemberId()` and every service/repository scopes queries by `member_id`. Family-shared resources are gated by `SharingSettings` + `SharedResource` (see [`docs/features/multi-account-family.md`](../features/multi-account-family.md)). Never expose data from another member without going through this layer.
- CSRF is disabled — `SameSite=Lax` cookies + the JSON-only API surface provide equivalent protection for same-origin cookie-based auth. `Lax` (not `Strict`) is required for Safari iOS, which dropped Strict cookies on certain navigations (see [`docs/features/security-cors-cookies.md`](../features/security-cors-cookies.md)).
- The `Secure` flag is set by `SecureCookieProvider` from `app.secure-cookies` (default `true`; set to `false` only for local HTTP dev).

### Rate limiting

Bucket4j (`io.github.bucket4j`) enforces rate limits, keyed by IP for anonymous endpoints and by user/member id for authenticated ones. Buckets are created in `RateLimitConfig` and consumed in controller methods.

| Endpoint group                                | Limit                                       |
| --------------------------------------------- | ------------------------------------------- |
| `POST /api/auth/login`                        | 5 requests / IP / 15 min                    |
| `POST /api/auth/mfa/verify`                   | 5 / 15 min per account (the challenge's `uid`) — anti-bruteforce on the 6-digit code |
| `POST /api/auth/mfa/enroll/init`              | 10 / IP / 1 h                               |
| `POST /api/auth/change-password`, `/api/auth/mfa/disable`, `/api/auth/mfa/recovery-codes/regenerate` | 5 / user / 15 min (step-up password checks) |
| `POST /api/sync/initiate`                     | Throttled                                   |
| `GET /api/sync/institutions`                  | 60 / IP / min — typeahead budget on its own `ip:institutions` bucket (uncached provider fetch, but the search box fires per keystroke) |
| `POST /api/tr/auth/initiate`                  | 3 / IP / 10 min (each attempt sends an SMS) |
| `POST /api/tr/auth/complete`                  | 5 / IP / 15 min on its own bucket (anti-bruteforce on the 4-digit TAN; sharing the SMS budget would lock a member out mid-login) |
| `POST /api/bourse-direct/auth/initiate`, `/complete` | Throttled                            |
| `POST /api/degiro/auth/initiate`, `/complete` | Throttled (anti-bruteforce on 6-digit code) |
| `POST /api/amundi/auth/initiate`, `/complete` | Throttled                                   |
| `POST /api/finary/check-totp`, `/api-sync/preview`, `/api-sync/auto` | 5 / IP / 15 min — each runs a full Clerk sign-in with the stored credentials |
| Sync and upload entry points (`/api/tr/sync`, `/api/tr/import`, `/api/degiro/sync`, `/api/bourse-direct/sync`, `/api/amundi/sync`, `/api/bourso/sync`, `/api/finary/preview`, `/api/accounts/{id}/transactions/import`) | 10 / IP / min, shared `syncBuckets` |
| `POST /api/ibkr/sync`                         | 6 / IP / min                                |
| `POST /api/me/export`                         | 5 / user / h (GDPR export)                  |

When a limit is exceeded, the controller returns a 429 ProblemDetail directly (not via the exception handler).

### Success responses

| Status | Usage |
|--------|-------|
| `200 OK` | GET, PUT, POST (non-creation) |
| `201 Created` | POST that creates a resource (annotated `@ResponseStatus(HttpStatus.CREATED)`) |
| `202 Accepted` | POST that queues asynchronous work |
| `204 No Content` | DELETE, logout |

## Error format

All errors use [RFC 7807 ProblemDetail](https://datatracker.ietf.org/doc/html/rfc7807).

```json
{
  "type": "about:blank",
  "title": "Unauthorized",
  "status": 401,
  "detail": "Invalid credentials"
}
```

Validation errors (422) include an `errors` map with field-level messages:

```json
{
  "type": "about:blank",
  "title": "Validation failed",
  "status": 422,
  "detail": null,
  "errors": {
    "name": "must not be blank",
    "targetAmount": "must be greater than 0.01"
  }
}
```

Stack traces are never exposed (`server.error.include-stacktrace: never`).

Domain-specific failures may add a stable machine-readable `code` property.
Clients translate this code and must not infer a cause from localized or
human-readable `detail` text.

## Validation

- Jakarta Validation annotations on DTO records (`@NotBlank`, `@NotNull`, `@Size`, `@DecimalMin`, `@Future`, etc.)
- `@Valid` on controller method parameters triggers automatic validation
- Validation failures produce 422 via `GlobalExceptionHandler.handleMethodArgumentNotValid()`
- No manual validation in services unless it is business logic (e.g., `IllegalArgumentException`)

## Pagination

Not currently used. Each member has limited data volumes (one household-scale dataset), so all list endpoints return full arrays.

## JSON configuration

```yaml
spring.jackson:
  write-dates-as-timestamps: false          # ISO-8601 dates
  default-property-inclusion: non_null      # omit null fields
  deserialization.fail-on-unknown-properties: false
```

## Reference

The complete endpoint reference is in [`backend/docs/API.md`](../../backend/docs/API.md). When adding or changing an endpoint, update that file.

## Don'ts

- **Never add try/catch in controllers** — `GlobalExceptionHandler` handles everything.
- **Never put business logic in controllers** — controllers delegate to services.
- **Never use `@Autowired` field injection** — use constructor injection (Lombok `@RequiredArgsConstructor`).
- **Never return raw exceptions or stack traces** — always `ProblemDetail` via the handler.
- **Never bypass member scoping** — never query a repository without filtering by `UserContext.currentMemberId()` (or the explicit override on family-shared resources).
