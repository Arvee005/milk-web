# Testing

This document explains how tests are organized and executed for Milk Web.

Test frameworks and tools
- Maven (./mvnw) is used to run tests
- JUnit (via Spring Boot test starters)
- Spring Boot testing support (SpringBootTest, DataJpaTest)
- MockMvc for controller/integration tests
- H2 in-memory database for tests (configured in application-dev.properties)

Test types and purpose
- Repository tests (DataJpaTest): verify correct persistence mappings and repository queries.
- Service tests (SpringBootTest or unit tests): verify business logic and transactional behavior.
- Controller tests (SpringBootTest + AutoConfigureMockMvc): verify HTTP endpoints, validation, security, and DTO boundaries.

How to run tests
- In project root:
  cd backend && ./mvnw test

Test status (current)
- The verified backend suite contains 33 tests.
- Test result: 33 tests run, 0 failures, 0 errors, and 0 skipped.
- The suite was verified with `cd backend && ./mvnw clean test`.

New controller tests added for GET /api/owner/customers:
- ownerCanListCustomers: verifies OWNER can retrieve a JSON array of CustomerResponse
- ownerGetsEmptyListWhenNoCustomers: verifies empty array returned when no customers
- customerCannotGetOwnerCustomerEndpoint: verifies CUSTOMER receives 403 for list endpoint
- unauthenticatedCannotGetOwnerCustomer: verifies 401 for unauthenticated requests

What these tests protect:
- Role-based access control for owner-only endpoints
- DTO boundary to prevent leaking JPA entities or sensitive fields
- Correct empty-list behaviour (200 with [])

Test best practices
- Use H2 for fast, isolated tests and avoid depending on production DB state.
- Keep tests deterministic and transactional where appropriate.
- Test both positive and negative flows (duplicate phone, unauthorized access, validation failures).

What tests protect
- Authentication and authorization behavior (AuthControllerTest)
- Password hashing and user creation (UserServiceTest)
- JWT generation and validation (JwtServiceTest)
- Customer creation transactional behavior and duplicate handling (CustomerServiceTest)
- Controller authorization rules and error mapping (CustomerControllerTest)

When to add tests
- Add repository tests when adding entities or custom queries.
- Add service tests for business rules.
- Add controller tests for endpoint behavior and authorization.
