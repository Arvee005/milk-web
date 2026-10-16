# Milk Web

Full-stack dairy management platform for a real family dairy business. Customers collect milk and ghee from the farm; the owner records actual daily supply, reconciles billing periods, and issues monthly bills.

## Stack

- Backend: Java 21, Spring Boot, Spring Data JPA/Hibernate, Spring Security with JWT, Maven
- Frontend: React, TypeScript, Vite
- Databases: H2 for development and tests; PostgreSQL in production (`prod` profile, Flyway migrations in `backend/src/main/resources/db/migration`, Hibernate `ddl-auto=validate`)

## Repository layout

- `backend/` — Spring Boot API under `com.radhikafarms.milkweb` (`auth`, `user`, `customer`, `product`, `subscription`, `dailyrecord`, `billing`, `config`)
- `frontend/` — React + TypeScript single-page app
- `docs/` — design, API, testing, security, decision records, and development log

## Implemented functionality

- JWT authentication with OWNER and CUSTOMER roles
- Customer self-registration (`POST /api/auth/register`); new accounts are PENDING until the owner approves them
- Login and registration rate limiting (429 with `Retry-After`; see ADR-012)
- Owner customer and product management APIs (create, read, update, activate/deactivate)
- Subscriptions (recurring default plan) and DailyRecords (actual daily supply), kept as separate concepts
- BillingPeriod inspection and closing, and Bill generation/issuing from actual chargeable records
- Owner web workspace: Billing, DailyRecord entry and review, customer setup, and product setup
- Customer workspace: profile, today's collection, record history, subscriptions, issued bills

**PLANNED / NOT YET IMPLEMENTED:** payments, balances, financial ledger, refunds, password reset, SMS phone verification, server-side customer search or pagination, owner subscription management UI, notifications, and analytics.

## Running locally

Backend:

```
cd backend && ./mvnw spring-boot:run
```

The backend starts with no accounts, so a fresh database has no way to sign in as OWNER. To create the first
development owner, export the values and start the `dev` profile:

```
cd backend
export MILKWEB_DEV_OWNER_PHONE=9000000001
export MILKWEB_DEV_OWNER_PASSWORD='pick-a-local-password'
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

The `dev` profile uses H2 with a Hibernate-created schema and disables Flyway (the migrations are for
PostgreSQL in production).

`DevOwnerSeeder` creates that OWNER with the password hashed by the existing BCrypt encoder. The `dev` profile
uses an in-memory H2 database, so that database and the owner are recreated on every fresh start. If the phone
is already registered — for example when `dev` is pointed at a persistent database — the existing account is
left exactly as it is, and a non-owner account is never promoted to OWNER. Seeding runs only when `dev` is the
only active profile, and creates nothing when either variable is missing or invalid — the log then explains
what to set. Never reuse these values outside local development.

Frontend:

```
cd frontend && npm install && npm run dev
```

The frontend expects the API under `/api` by default; override with `VITE_API_BASE_URL`.

## Tests and checks

```
cd backend && ./mvnw test
cd frontend && npm test
cd frontend && npm run build   # includes TypeScript type-check
cd frontend && npm run lint
```

Backend tests use isolated H2 databases. Frontend API client tests use injected fetch functions and React component tests use jsdom; neither claims live browser-to-backend verification.

## Documentation

Start with [docs/README.md](./docs/README.md) for the documentation index, [docs/api-design.md](./docs/api-design.md) for the API surface, [docs/security.md](./docs/security.md) for authorization, [docs/testing.md](./docs/testing.md) for test coverage, and [docs/decisions/](./docs/decisions/README.md) for architecture decision records.

Development instructions and domain rules for AI tooling are in [AGENTS.md](./AGENTS.md).