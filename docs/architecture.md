# Architecture

This document describes the high-level architecture of Milk Web and the responsibilities of each layer.

Overview

Frontend (React + TypeScript + Vite)
- Purpose: user interface for customers and owners.
- Located in: frontend/
- Rationale: fast development with TypeScript safety and Vite for a modern dev server and build pipeline. (See ADR-008)

REST API (Spring Boot)
- Purpose: backend HTTP API consumed by frontend and future mobile clients.
- Located in: backend/src/main/java/com/radhikafarms/milkweb
- Rationale: Spring Boot provides mature infrastructure for REST, security, data access, and testing.

Security / JWT
- JWT tokens are used to authenticate API requests. JwtAuthenticationFilter validates the token and sets the SecurityContext.
- Passwords are hashed using BCrypt and stored in the User entity.
- The React owner workspace authenticates through the same API: it posts phone/password to `/api/auth/login`, holds the access token in tab-scoped `sessionStorage`, and validates restored sessions with `/api/auth/me` before rendering owner content.
- Authentication states are explicit (`checking`, `authenticated`, `anonymous`, and session-check `error`). The owner billing workspace is gated on a successfully validated `OWNER` role; API authorization remains authoritative.
- A shared TypeScript auth API module owns login/session response validation, bearer headers, and user-facing 401/403 errors. It has no runtime dependency beyond the browser platform.

Controller layer
- Purpose: HTTP endpoints, request/response mapping, validation, DTO translation.
- Controllers do not contain business logic.

Service layer
- Purpose: business logic, transactional boundaries, orchestration of repositories and other services.
- Examples: UserService, CustomerService (creates User + Customer transactionally).

Repository layer
- Purpose: data access using Spring Data JPA.
- Repositories are interfaces (JpaRepository) and used by services.

JPA / Hibernate
- Entities annotated with @Entity. UUID primary keys are used.
- H2 used for tests; PostgreSQL is the intended production DB (dependency in pom.xml).

Why separate layers?
- Separation of concerns: makes code easier to test, reason about, and change.
- Controllers handle HTTP and validation; services handle business rules; repositories handle persistence.

How this supports mobile apps
- Stateless JWT-based authentication allows mobile clients to authenticate once and use the token on subsequent requests.
- Resource-oriented REST endpoints (not coupled to React) are suitable for mobile APIs.

Current vs future
- Implemented backend modules include authentication, users, customers, products, owner-managed subscriptions, owner-managed DailyRecords, and BillingPeriod reconciliation/closing, following the layered pattern.
- Subscription supplies a recurring default; DailyRecord records the actual outcome for a customer/product/date/collection slot. A closed BillingPeriod prevents later DailyRecord writes and provides an informational reconciliation summary. Bill generation snapshots those stored chargeable records into a DRAFT; a separate owner action issues the Bill. Payments and adjustments remain future modules.

Read more: docs/decisions/ADR-008-react-vite-frontend.md, docs/decisions/ADR-001-jwt-authentication.md
Authentication flow details and alternatives: docs/decisions/ADR-009-frontend-authentication-flow.md
