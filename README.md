# Milk Web

Full-stack dairy management platform for a real family dairy business. Customers collect milk and ghee from the farm; the owner records actual daily supply, reconciles billing periods, and issues monthly bills.

## Stack

- Backend: Java 21, Spring Boot, Spring Data JPA/Hibernate, Spring Security with JWT, Maven
- Frontend: React, TypeScript, Vite
- Databases: H2 for development and tests; PostgreSQL is the intended production database (no production profile or migrations exist yet)

## Repository layout

- `backend/` — Spring Boot API under `com.radhikafarms.milkweb` (`auth`, `user`, `customer`, `product`, `subscription`, `dailyrecord`, `billing`, `config`)
- `frontend/` — React + TypeScript single-page app
- `docs/` — design, API, testing, security, decision records, and development log

## Implemented functionality

- JWT authentication with OWNER and CUSTOMER roles
- Owner customer and product management APIs
- Subscriptions (recurring default plan) and DailyRecords (actual daily supply), kept as separate concepts
- BillingPeriod inspection and closing, and Bill generation/issuing from actual chargeable records
- Owner web workspace: Billing, and DailyRecord entry and review
- Customer workspace: profile, today's collection, record history, subscriptions, issued bills

**PLANNED / NOT YET IMPLEMENTED:** payments, balances, financial ledger, refunds, Flyway/PostgreSQL migrations, owner customer management UI, notifications, and analytics.

## Running locally

Backend:

```
cd backend && ./mvnw spring-boot:run
```

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