# Development Log

This development log is a chronological record of meaningful milestones in the Milk Web project. Each entry is based on Git history and verified repository contents. Facts are drawn from commit metadata and code; where reasons are not recorded, they are marked as INFERENCE.

# Milestone 1 — Initialize project

## Date
2026-08-13 18:43:24 +0530

## Git Commit
3d0f3e9 — chore: initialize Milk Web project

## What We Did
Created the repository and initial skeleton.

## Why We Did It
Start a codebase for Milk Web.

## Technical Concepts
Repository initialization, basic project layout.

## Important Files
- .gitignore (later)
- initial scaffolding (project root)

## Tests
None

## Notes
FACT: initial commit exists with message above.
INFERENCE: likely created using project scaffolding tools or manually.

# Milestone 2 — Add .gitignore

## Date
2026-08-13 18:53:17 +0530

## Git Commit
6a3e6a1 — chore: add project gitignore

## What We Did
Added a .gitignore to exclude build artifacts and IDE files.

## Why We Did It
Standard maintenance to avoid committing transient files.

## Important Files
- .gitignore

# Milestone 3 — Initialize Spring Boot backend

## Date
2026-08-13 19:48:42 +0530

## Git Commit
79c80dc — feat: initialize Spring Boot backend

## What We Did
Added the backend Spring Boot project with packaging, pom.xml, and initial packages.

## Why We Did It
To implement server-side API and business logic using a mature Java web framework.

## Technical Concepts
- Spring Boot application structure
- Maven build
- Java 21 (pom set to 21)

## Important Files
- backend/pom.xml
- backend/src/main/java/com/radhikafarms/milkweb/MilkWebBackendApplication.java

# Milestone 4 — Initialize React frontend

## Date
2026-08-13 19:53:31 +0530

## Git Commit
221f07f — feat: initialize React frontend

## What We Did
Added a Vite + React + TypeScript frontend skeleton.

## Why We Did It
User interface and future mobile-friendly front-end layer.

## Important Files
- frontend/package.json
- frontend/src/App.tsx

# Milestone 5 — Refactor backend package structure

## Date
2026-08-14 17:33:54 +0530

## Git Commit
06c23af — refactor: organize backend package structure

## What We Did
Reorganized backend Java packages into modules (auth, user, customer...).

## Why We Did It
Better separation of concerns and clearer module boundaries.

# Milestone 6 — Add AI development instructions

## Date
2026-08-17 00:06:48 +0530

## Git Commit
a0f7ef5 — docs: add AI development instructions

## What We Did
Added AGENTS.md containing project rules, architecture notes, domain model guidance, and development policies for AI agents.

## Why We Did It
To capture project conventions, domain model, and provide governance for automated agents.

# Milestone 7 — User domain and tests

## Date
2026-08-17 00:10:33 +0530

## Git Commit
b29cb63 — feat: add user domain and repository test

## What We Did
Implemented User entity, UserRepository, and tests for repository and service.

## Important Files
- backend/src/main/java/com/radhikafarms/milkweb/user/User.java
- backend/src/main/java/com/radhikafarms/milkweb/user/UserRepository.java
- backend/src/test/java/com/radhikafarms/milkweb/user/UserRepositoryTest.java
- backend/src/test/java/com/radhikafarms/milkweb/user/UserServiceTest.java

## Tests
Repository and service tests added, relying on H2 test DB.

# Milestone 8 — Authentication and Customer module

## Date
2026-08-17 00:37:57 +0530

## Git Commit
d85341b — feat: implement authentication and customer module

## What We Did
Implemented JWT authentication, JwtService, JwtAuthenticationFilter, AuthController, and Customer entity + repository. Password encoder and security config were added.

## Important Files
- backend/src/main/java/com/radhikafarms/milkweb/auth/*
- backend/src/main/java/com/radhikafarms/milkweb/config/*
- backend/src/main/java/com/radhikafarms/milkweb/customer/Customer.java
- backend/src/test/java/.../AuthControllerTest.java
- backend/src/test/java/.../JwtServiceTest.java

## Technical Concepts
- JWT tokens
- BCrypt password hashing
- Spring Security configuration

# Milestone 9 — Customer creation & profile API

## Date
2026-08-20 06:26:52 +0530

## Git Commit
5b3c11e — feat: add customer creation and profile API

## What We Did
Added CustomerService#createCustomer (transactional), CustomerController with POST /api/owner/customers and GET /api/customer/me, DTOs (OwnerCreateCustomerRequest, CustomerResponse), domain exceptions and tests (service + controller).

## Important Files
- backend/src/main/java/com/radhikafarms/milkweb/customer/CustomerService.java
- backend/src/main/java/com/radhikafarms/milkweb/customer/CustomerController.java
- backend/src/main/java/com/radhikafarms/milkweb/customer/OwnerCreateCustomerRequest.java
- backend/src/main/java/com/radhikafarms/milkweb/customer/CustomerResponse.java
- backend/src/main/java/com/radhikafarms/milkweb/customer/exception/*
- backend/src/test/java/com/radhikafarms/milkweb/customer/*Test.java

## Tests
After this milestone, backend tests reported:
- 28 tests total
- 28 passed
- 0 failures/errors/skipped

## Notes
- Creation enforces unique phone using UserService and throws 409 on duplicates.
- Customer creation creates a User (role=CUSTOMER) and Customer atomically in a transaction.
- Customer endpoints use role-based access and @AuthenticationPrincipal to identify the caller.

# Milestone 10 — OWNER customer lookup by ID

## Date
2026-08-21 (local milestone work)

## Git Commit
Not yet committed; local implementation only.

## What We Did
Added a read-by-ID endpoint for authenticated owners: GET /api/owner/customers/{customerId}. The controller delegates to CustomerService, which calls CustomerRepository.findById(customerId) and returns a CustomerResponse DTO.

## Why We Did It
The project already supported owner-created customer records and customer self-profile access. The next smallest milestone was to let an OWNER fetch any existing customer profile by its ID without exposing JPA entities or allowing CUSTOMER users to access owner-only routes.

## Technical Concepts
- Repository primary-key lookup
- DTO return contract
- URL-based role authorization
- 404 handling via CustomerNotFoundException

## Important Files
- backend/src/main/java/com/radhikafarms/milkweb/customer/CustomerService.java
- backend/src/main/java/com/radhikafarms/milkweb/customer/CustomerController.java
- backend/src/test/java/com/radhikafarms/milkweb/customer/CustomerControllerTest.java

## Design Decisions
FACT: The project already uses controller/service/repository layering and DTO-based API responses.
INFERENCE: This endpoint follows the same pattern to keep HTTP, business logic, and persistence concerns separate.

## Problems Encountered
No production issue encountered in inspection. The main concern was correctness of authorization and not exposing entity internals.

## How We Solved Them
By using the existing security rules in SecurityConfig, reusing CustomerResponse, and mapping missing customer IDs to CustomerNotFoundException.

## Tests
Added controller tests covering:
- OWNER success
- OWNER 404 for unknown customer
- CUSTOMER forbidden access
- unauthenticated access denied

## Lessons Learned
- Repository primary-key lookups are the simplest way to support read-by-ID in this layered architecture.
- DTOs remain essential for keeping API contracts stable and avoiding entity leakage.

## Interview Takeaway
The owner lookup is intentionally narrow: it allows an OWNER to fetch a specific customer record by ID while preserving the same security and DTO boundaries already used elsewhere in the API.

# Milestone 11 — OWNER customer list

## Date
2026-09-02 (local milestone work)

## Git Commit
Not yet committed; placeholder for new commit.

## What We Did
Implemented GET /api/owner/customers which returns a JSON array of CustomerResponse DTOs for authenticated OWNERs. The controller delegates to CustomerService.listAll(), which calls CustomerRepository.findAll().

## Why We Did It
This milestone provides owners a simple way to retrieve all customer profiles. Pagination and fetch optimizations were intentionally deferred to keep the change minimal and consistent with prior milestones.

## Technical Concepts
- JpaRepository.findAll()
- DTO mapping from entity to API response
- Role-based URL authorization
- Empty list handling (200 OK with [])

## Important Files
- backend/src/main/java/com/radhikafarms/milkweb/customer/CustomerService.java
- backend/src/main/java/com/radhikafarms/milkweb/customer/CustomerController.java
- backend/src/test/java/com/radhikafarms/milkweb/customer/CustomerControllerTest.java

## Design Decisions
FACT: Reuse existing controller/service/repository pattern and CustomerResponse DTO.
INFERENCE: The system currently expects OWNER to access all customers; per-owner scoping is not implemented in the data model.

## Tests
Added controller tests covering:
- OWNER can list customers
- OWNER gets empty list when none exist
- CUSTOMER is forbidden from listing
- Unauthenticated requests get 401

## Notes
- Pagination was deliberately deferred. If performance issues or UI needs arise, switch to Page/Pageable and add fetch-joins to avoid N+1 queries.

## Lessons Learned
- Small, incremental APIs are easiest to maintain and test.
- Always consider lazy relationships when mapping entities to DTOs (possible N+1).

## Interview Takeaway
We added a simple owner listing endpoint that adheres to the existing layered architecture and respects DTO boundaries; future scaling will introduce pagination and fetch-join optimizations.

# Milestone 4C — OWNER customer profile update

## Date
2026-09-15 (local milestone work)

## Git Commit
Not yet committed; placeholder for new commit.

## What We Did
Implemented PUT /api/owner/customers/{customerId} for OWNER profile updates using OwnerUpdateCustomerRequest and the existing CustomerResponse DTO.

## Design Decision
FACT: The update accepts only name, address, billingType, status, and joinDate.
FACT: Phone is excluded because it is also the User authentication/login identifier.
INFERENCE: A separate phone/account-identifier flow can be introduced later with stronger account-security handling.

## Tests
Added controller and service coverage for successful updates, unchanged phone behavior, missing customers, validation, OWNER authorization, CUSTOMER rejection, unauthenticated rejection, DTO boundaries, and User/Customer consistency.
Verification: `./mvnw clean test` completed with 41 tests, 0 failures, 0 errors, and 0 skipped (BUILD SUCCESS).

## Notes
- Password, passwordHash, role, IDs, createdAt, and nested User data are not accepted or returned.
- The update is transactional, although it changes only Customer fields in this milestone.

# Java 25 -> Java 21 incident

## Date
2026-08-20 (local)

## Facts
- The committed project POM targeted Java 21.
- Locally, an unstaged edit to backend/pom.xml changed <java.version>21 to 25 and added a PostgreSQL version line.
- The developer's machine and Maven were running Java 21.0.12.
- Running mvn test produced a class-file-version mismatch error.
- The local pom change was discarded (restored to HEAD) and backend tests were re-run and passed.

## Conclusion
FACT: Java 21 is the project baseline. Java 25 was not introduced in history; it was a local unstaged change and was reverted.


# End of development log (most recent milestones recorded)
