# Milk Web — AI Development Instructions

## 1. PROJECT OVERVIEW

Milk Web is a full-stack dairy management platform being built for a real family dairy business.

The business sells:
- Cow milk
- Buffalo milk
- Cow ghee
- Buffalo ghee
- Future dairy products

The current goal is to build a production-quality web application first.

The architecture must remain suitable for a future iOS and Android application.

Do NOT treat this as a toy/demo CRUD project.

---

## 2. CURRENT DEVELOPMENT PHILOSOPHY

We have spent significant time planning the domain model.

The planning phase is now over.

PRIORITY:
1. Build working functionality.
2. Test it.
3. Commit it.
4. Move to the next feature.

Do not repeatedly redesign already-approved architecture.

Do not introduce unnecessary abstractions or enterprise complexity.

When a genuinely important architectural decision is required:
- Explain the problem.
- Give the recommended solution.
- Explain the consequence.
- Then implement it.

Do not stop development for minor theoretical decisions.

---

## 3. TECH STACK

### Frontend

- React
- Vite
- TypeScript
- Tailwind CSS
- Modern component-based architecture

The frontend should eventually support:
- Customer dashboard
- Owner dashboard
- Authentication
- Daily milk records
- Calendar
- Billing
- Payments
- Notifications
- Analytics

### Backend

- Java
- Spring Boot 4.x
- Spring Data JPA
- Hibernate
- Spring Security
- Maven
- PostgreSQL for production
- H2 for tests

### Authentication

Planned authentication:
- Phone number + password
- BCrypt password hashing
- JWT authentication
- Roles:
  - CUSTOMER
  - OWNER

---

# 4. DOMAIN MODEL

The core domain model is:

User
Customer
Product
Subscription
DailyRecord
BillingPeriod
Bill
Payment
BalanceAdjustment

The following were intentionally deferred:
- ProductAvailability
- DailyInventory

They may be added later if the business requires them.

---

# 5. USER

User is the authentication root.

Fields:

- id: UUID
- phone: unique
- passwordHash
- role: CUSTOMER / OWNER
- createdAt

Rules:

- Phone number is the login identifier.
- Passwords must NEVER be stored in plaintext.
- Passwords must be hashed using BCrypt.
- User IDs use UUID.
- Role is stored as STRING, never ordinal.

An OWNER has a User record but does not need a Customer record.

A CUSTOMER has:
- User
- Customer profile

---

# 6. CUSTOMER

Customer represents the dairy customer/business relationship.

Conceptually:

Customer
- id
- user_id
- name
- phone/address information
- billing_type
- status
- join_date

Customer is separate from User because authentication data and business data should not be mixed.

---

# 7. PRODUCT

Product is a first-class entity.

Do NOT create columns such as:

cow_quantity
buffalo_quantity
ghee_quantity

The system must remain extensible.

Product concept:

- id
- name
- unit
- pricePerUnit
- active

Examples:

Cow Milk
Buffalo Milk
Cow Ghee
Buffalo Ghee

Future products should be addable without changing the DailyRecord schema.

---

# 8. SUBSCRIPTION

Subscription represents what a customer normally wants.

Concept:

- id
- customer_id
- product_id
- default_quantity
- status
- start_date

Statuses:

ACTIVE
PAUSED
CANCELLED

Important distinction:

Subscription = expected/normal purchase.

DailyRecord = what actually happened.

Do NOT merge these concepts.

---

# 9. DAILY RECORD

DailyRecord is the source of truth for actual dairy transactions.

Concept:

- id
- customer_id
- product_id
- date
- session
- quantity
- unit_price_snapshot
- amount
- status

Session:

MORNING
EVENING

Status:

COLLECTED
SKIPPED
PAUSED
EXTRA

Important:

A customer can purchase:
- Cow milk
- Buffalo milk
- Both
- Different quantities on different days
- Different products in different sessions

Do not hard-code product types into DailyRecord.

---

# 10. PRICE SNAPSHOT

DailyRecord must store:

unit_price_snapshot

and:

amount

Do NOT calculate historical bills using the current Product price.

Reason:

If milk costs ₹60 today and ₹65 next month, historical transactions must continue to represent the price that existed when the transaction occurred.

Historical financial records must remain immutable.

---

# 11. SKIP VS PAUSE

These are different concepts.

SKIP:
- One-day exception.
- Subscription remains ACTIVE.
- Customer resumes normally afterward.

PAUSE:
- Subscription becomes PAUSED.
- Customer should not be expected to receive normal daily supply.

Do not merge them.

---

# 12. BILLING

## BillingPeriod (implemented)
- A period is one canonical calendar month, identified by `YYYY-MM`, with inclusive first/last dates.
- Periods start OPEN and an OWNER explicitly closes them after reconciliation. Closing is idempotent and cannot be reversed in v1.
- A closed period blocks DailyRecord creation (including backdated records) and updates. Same-day correction remains allowed while OPEN.
- Close returns an informational count and sum of COLLECTED/EXTRA records; this does not prove no physical collection was missed. Closing does not generate or issue a Bill.
- Period close and DailyRecord writes coordinate using the same pessimistic database row lock. H2 tests do not prove PostgreSQL concurrency behavior.

## Bill (planned)
- A Bill is an immutable snapshot generated only from actual chargeable DailyRecords in a closed month.
- Monthly billing applies to MONTHLY customers; PREPAID customers are excluded for v1.
- Do NOT calculate monthly bills from Subscription defaults.
- Duplicate generation retries return the existing Bill.
- Do not store independently editable `paid_amount` or payment status on Bill; Payment is a separate future module.
- Bill generation, BillLineItem, Payment, and BalanceAdjustment are PLANNED / NOT YET IMPLEMENTED.

---

# 13. PAYMENTS

Payment:

- id
- customer_id
- bill_id (nullable)
- amount
- date
- method

A payment may be linked to a bill.

Payment history must remain auditable.

---

# 14. BALANCE ADJUSTMENTS

BalanceAdjustment represents small customer credits/debits.

Examples:

+₹30 credit
-₹20 adjustment

Do NOT store this as an unexplained balance field on Customer.

The adjustment should have:

- id
- customer_id
- amount
- reason
- date

This creates an auditable financial history.

---

# 15. INVENTORY / SHORTAGE RULE

There is NO automatic allocation/rationing engine.

If milk supply is short:

The OWNER decides which customers receive less or are skipped.

The system simply records the result using DailyRecord.

Do NOT build:
- automatic rationing
- allocation algorithms
- complex inventory enforcement

unless explicitly requested later.

---

# 16. PRODUCT AVAILABILITY

ProductAvailability is currently intentionally deferred.

If eventually implemented, it is INFORMATIONAL.

It should NOT automatically block purchases or modify records.

The owner remains in control.

---

# 17. DATABASE DESIGN

Use UUID primary keys.

Use proper foreign keys.

Use database constraints for important invariants.

Use indexes where queries will frequently filter by:
- customer
- product
- date
- phone
- billing period

Avoid unnecessary indexes.

Avoid bidirectional JPA relationships unless there is a concrete reason.

Prefer child entities owning their relationships.

Example:

DailyRecord -> Customer

rather than:

Customer -> List<DailyRecord>

This prevents accidental loading of large collections.

---

# 18. MONEY AND QUANTITIES

Use BigDecimal.

Never use:
- double
- float

for:
- money
- quantities
- prices

Define appropriate precision and scale at the database level.

---

# 19. ENUMS

Persist enums as STRING.

Never use:

@Enumerated(EnumType.ORDINAL)

Use:

@Enumerated(EnumType.STRING)

---

# 20. JPA ENTITIES

Entities should have:

- UUID IDs
- explicit table names where appropriate
- meaningful column names
- database constraints
- timestamps where appropriate

Avoid Lombok unless there is a clear benefit.

Do not generate huge bidirectional entity graphs.

Be careful with equals/hashCode in JPA entities.

---

# 21. TESTING

Every meaningful backend feature should have tests.

At minimum test:

- repository behavior
- service/business logic
- authentication
- important controller behavior

Current test database:

H2

Production database:

PostgreSQL

Never make tests depend on production data.

---

# 22. MIGRATIONS

Production database changes must eventually use migrations.

Preferred:

Flyway

Do not rely on:

spring.jpa.hibernate.ddl-auto=create

for production.

For tests, H2/create-drop is acceptable.

---

# 23. API DESIGN

The backend is an API consumed by the React frontend.

Keep APIs suitable for future mobile clients.

Do not design APIs that are tightly coupled to React.

Prefer resource-oriented REST endpoints.

Examples:

POST /api/auth/login

GET /api/customers

GET /api/products

GET /api/daily-records

POST /api/daily-records

GET /api/bills

POST /api/payments

Exact endpoint design can evolve as implementation proceeds.

---

# 24. SECURITY

Security is important because this is a real business application.

Never:
- expose password hashes
- return sensitive authentication information
- hard-code secrets
- commit API keys
- commit database passwords
- commit JWT secrets

Use environment variables/configuration for secrets.

Customer users must only access their own customer data.

Owners have administrative access.

Authorization must be enforced server-side.

Do not rely on frontend route protection as the security mechanism.

---

# 25. FRONTEND PRINCIPLES

The frontend should feel like a real modern product.

Design goals:

- modern
- clean
- interactive
- approachable
- not overly corporate
- easy for older customers to understand
- still visually appealing to younger users

The product should balance:
- usability
- visual personality
- simplicity

Do not sacrifice usability for animations.

---

# 26. MOBILE FUTURE

The web application will eventually have iOS and Android clients.

Therefore:

- Keep business logic in backend APIs.
- Do not put critical business rules only in React.
- Keep API responses clean and predictable.
- Avoid web-specific assumptions in backend architecture.
- Use stable IDs.
- Keep authentication API-based.

---

# 27. GIT WORKFLOW

Git repository is the source of truth.

Before modifying significant functionality:

1. Inspect existing code.
2. Understand current state.
3. Make the smallest coherent change.
4. Run tests.
5. Fix failures.
6. Show what changed.
7. Commit when the feature is working.

Do not make unrelated changes.

Do not rewrite working code unnecessarily.

Do not delete existing functionality without explaining why.

---

# 28. CURRENT PROJECT STATE

As of August 2026:

### Repository

Milk Web Git repository exists.

### Frontend

React + Vite frontend exists and runs successfully.

Expected local frontend:

http://localhost:5173/

### Backend

Spring Boot backend exists.

Backend package:

com.radhikafarms.milkweb

### Backend structure

Current intended packages:

com.radhikafarms.milkweb
├── billing
├── config
├── customer
├── dailyrecord
├── payment
├── product
├── subscription
└── user

### User module

Currently implemented:

- UserRole
- User entity
- UserRepository
- UserRepository test

User repository test currently verifies finding a User by phone.

### Test infrastructure

Working:

- Maven
- JUnit
- Spring Boot test infrastructure
- Spring Data JPA test
- H2 test database

---

# 29. CURRENT DEVELOPMENT POSITION

The project is now officially in BUILD MODE.

Do NOT restart architecture planning.

The next development priority is:

AUTHENTICATION

Implementation order:

1. User service
2. BCrypt password hashing
3. Spring Security configuration
4. JWT implementation
5. Login endpoint
6. Authentication tests
7. Authorization by role
8. Customer module

After authentication works, continue through the domain modules.

---

# 30. HOW THE AI SHOULD WORK

Before changing code:

- Inspect relevant existing files.
- Do not assume files exist.
- Do not recreate files that already exist.
- Do not change package names casually.
- Do not change the database model without a reason.

When something fails:

1. Read the actual error.
2. Identify the root cause.
3. Fix the root cause.
4. Run the relevant test again.
5. Do not repeatedly suggest the same command if it already failed.

When a command succeeds:

- Treat that step as completed.
- Move forward.

When unsure:

- Ask one focused question.
- Do not stop the entire project for minor uncertainty.

---

# 31. COMMUNICATION STYLE

The developer is learning while building.

Explain important architectural decisions briefly.

Do not overwhelm with theory.

Prefer:

"Do this → here's why → here's what success looks like."

Avoid long theoretical lectures unless specifically requested.

The developer wants to understand the project, not blindly copy code.

---

# 32. DEFINITION OF DONE

A feature is not considered complete merely because code compiles.

A feature is complete when:

- code is implemented
- tests pass
- application starts
- API behavior works
- security/business rules are enforced
- changes are committed to Git

Then move to the next feature.

---

# FINAL RULE

BUILD THE PRODUCT.

Do not spend another month planning the product.

When a reasonable decision has already been made, implement it and keep moving.

---

# Documentation Requirements

Every meaningful implementation milestone MUST update the documentation in docs/. Each documentation update should include, where applicable:

1. What was implemented (files, APIs, database changes)
2. Why it was implemented (context and rationale)
3. Alternatives considered
4. Trade-offs and disadvantages
5. Data structures and algorithms used
6. Time/space complexity where relevant
7. Security implications
8. Tests added or changed
9. Problems encountered and how they were solved
10. Lessons learned
11. Interview takeaway
12. Associated Git commit hash

Development workflow that includes documentation:

Plan → Explain → Implement → Test → Document → Review → Commit

Documentation must clearly label items as:
- IMPLEMENTED (code exists)
- PLANNED / NOT YET IMPLEMENTED
- INFERENCE (reason inferred; not explicitly recorded)

Do NOT modify other parts of this file when updating this section.

