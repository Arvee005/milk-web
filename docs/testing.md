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
- After the OWNER customer lookup milestone, the backend suite is expected to include the previous tests plus the new owner-read checks.
- The current milestone should be validated with the full backend suite via `cd backend && ./mvnw test`.

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
