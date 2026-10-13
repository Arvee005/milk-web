# Milestone 2 - Migration Verification Results

## Summary
- Created Flyway migration `V1__baseline_schema.sql` and verified it against the application entity mappings.
- Fixed Testcontainers dependency configuration, including the BOM version and Spring Boot Testcontainers artifact coordinates.
- Set production `spring.flyway.baseline-on-migrate=false` so an unexpected existing schema without Flyway history fails fast.
- Fixed the `CustomerControllerTest` isolation issue with `@DirtiesContext`.
- Configured `SecurityErrorDispatchIntegrationTest` to disable Flyway in its isolated test context and use `ddl-auto=create-drop`.
- Verified the production migration and Hibernate schema validation against a disposable PostgreSQL database.

## Test Results
- Backend full test suite: 176 tests passed, 0 failures, 0 errors.
- `CustomerControllerTest`: 18 tests passed.
- `CustomerServiceTest`: 4 tests passed.
- Frontend checks also passed, including the component and API tests.

## PostgreSQL Verification
- Applied Flyway migration V1 successfully to a disposable PostgreSQL database.
- Confirmed Flyway recorded V1 in its schema history.
- Confirmed the expected eight application tables were created.
- Hibernate schema validation against PostgreSQL completed successfully.
- These checks verify the migration and entity schema compatibility; they do not replace production smoke tests after deployment.

## Configuration Changes
- `backend/pom.xml`: configured Testcontainers dependencies.
- `backend/src/main/resources/application-prod.properties`: configured Flyway with `baseline-on-migrate=false`.
- `backend/src/test/java/com/radhikafarms/milkweb/customer/CustomerControllerTest.java`: added `@DirtiesContext` for test isolation.
- `backend/src/test/java/com/radhikafarms/milkweb/config/SecurityErrorDispatchIntegrationTest.java`: isolated its test schema configuration.

## Remaining Limitations
- Live production deployment and end-to-end smoke tests remain to be completed.
- Payment processing, password reset, and public customer registration are not implemented.
