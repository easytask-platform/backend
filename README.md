# EasyTask Backend

Spring Boot 4.1 / Java 21 REST API for the EasyTask multi-organization task management platform.
Implements the full contract in [`../Initial_API_Contract.md`](../Initial_API_Contract.md) under base path `/api/v1`.

## Tech stack

- Spring Boot 4.1 (WebMVC, Data JPA, Security, Validation), Java 21
- PostgreSQL + Flyway migrations (`src/main/resources/db/migration`)
- JWT auth (jjwt): ~15 min HMAC access tokens + opaque rotating refresh tokens (SHA-256-hashed at rest)
- Package-by-feature: `auth`, `user`, `team`, `project`, `task`, `recurring`, `timeentry`, `comment`, `attachment`, `activity`, `notification`, `dashboard`, `common`, `config`

## Prerequisites

- Java 21+ (the Maven wrapper handles Maven itself)
- Local PostgreSQL with the dev and test databases:

```bash
sudo -u postgres psql <<'SQL'
CREATE ROLE easytask LOGIN PASSWORD 'easytask';
CREATE DATABASE easytask OWNER easytask;
CREATE DATABASE easytask_test OWNER easytask;   -- used by the integration tests
SQL
```

## Run

```bash
./mvnw spring-boot:run
```

Flyway applies the schema on first boot. The API is then at `http://localhost:8080/api/v1`.

Configuration (env vars, all optional in dev):

| Variable | Default | Purpose |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/easytask` | Datasource |
| `DB_USERNAME` / `DB_PASSWORD` | `easytask` / `easytask` | DB credentials |
| `JWT_SECRET` | dev-only key | Base64 HMAC key — **override in prod** |
| `JWT_ACCESS_TTL` | `15m` | Access token lifetime |
| `REFRESH_TTL` | `14d` | Refresh token lifetime |
| `STORAGE_DIR` | `./data/attachments` | Attachment file storage |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:5173,http://localhost:3000` | Dashboard dev origins |
| `FIREBASE_CREDENTIALS` | *(unset — push disabled)* | Path to the Firebase service-account JSON; when set, FCM push notifications are sent for every created notification |

To run with push enabled (the key file is gitignored, never committed):

```bash
FIREBASE_CREDENTIALS=$PWD/firebase-service-account.json ./mvnw spring-boot:run
```

## Tests

```bash
./mvnw test
```

Integration tests run against the local `easytask_test` database (profile `test`); the schema is
dropped and re-migrated per test context, so the dev database is never touched.

## End-to-end demo

With the app running (and `jq` installed):

```bash
scripts/e2e-demo.sh
```

Walks the full §12 scenario from `../EasyTask_Project_Explanation.md`: org registration →
users/team → project → task assignment → notification → status flow → time/comment/attachment →
approval → dashboard progress → activity trail. Re-runnable (unique emails per run).

## API quick reference

- Auth: `POST /auth/register-organization`, `/auth/login`, `/auth/refresh` (rotation), `/auth/logout`, `GET /me`, `PATCH /me/password`
- Users: `GET/POST /users`, `GET/PATCH /users/{id}`, `PATCH /users/{id}/deactivate`
- Teams: `GET/POST /teams`, `PATCH /teams/{id}`, `GET/POST /teams/{id}/members`, `DELETE /teams/{id}/members/{userId}`
- Projects: `GET/POST /projects`, `GET/PATCH /projects/{id}`, member endpoints
- Tasks: `GET/POST /tasks`, `GET/PATCH /tasks/{id}`, `PATCH /tasks/{id}/status` (contract transition matrix)
- Collaboration: `/tasks/{id}/comments|attachments|time-entries|activity` (+ item-level `PATCH/DELETE`)
- Recurring: `GET/POST /recurring-task-rules`, `GET /recurring-task-rules/{id}/tasks` (daily generation job)
- Notifications: `GET /notifications`, `/notifications/unread-count`, `PATCH .../read`, `PATCH /notifications/read-all`
- Dashboards & reports: `GET /dashboard/admin`, `/dashboard/manager`, `/reports/workload`, `/reports/project-progress`

Errors use `{status, code, message, fields}`; lists use `{items, page, size, totalItems, totalPages}`.
Org isolation: everything is scoped to the caller's organization; out-of-scope resources return 404.
