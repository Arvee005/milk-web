# Learning Notes

This document explains core technical concepts used in Milk Web, why they were chosen, and how they apply to the project. When a concept is not yet used, it is put in "Future / when introduced".

## UUID
What is it: Universally unique identifier used as entity primary keys.
Why we use it: Decouples ID generation from DB autoincrement, stable across services and suitable for distributed systems.
Milk Web example: User.id and Customer.id are UUIDs.
Interview explanation: UUIDs avoid revealing row counts and are convenient in microservices.

## JWT
What is it: JSON Web Token, a signed token holding claims.
Why we use it: Stateless authentication for REST APIs and mobile clients.
Milk Web example: JwtService generates tokens with userId, phone, and role.

## BCrypt
What is it: Password hashing algorithm with built-in work factor.
Why we use it: Secure password storage; intentionally slow to mitigate brute force.
Milk Web example: PasswordEncoder bean uses BCryptPasswordEncoder.

## DTOs
What is it: Data Transfer Objects, request/response types separate from entities.
Why we use it: Avoid exposing JPA entities directly and control API surface.
Milk Web example: OwnerCreateCustomerRequest and CustomerResponse.

## Transaction
What is it: Database transaction that groups multiple DB operations.
Why we use it: Ensure atomicity when creating User + Customer.
Milk Web example: CustomerService.createCustomer is @Transactional.

## Optional
What is it: java.util.Optional for nullable returns.
Why: Makes absence explicit and encourages handling of missing values.
Milk Web example: userRepository.findByPhone returns Optional<User>.

## JPA relationship (ManyToOne)
What is it: JPA mapping for relations between entities.
Why: Model ownership/foreign keys in the DB.
Milk Web example: Customer.user is ManyToOne to User with a unique constraint on user_id.

## H2 (test DB)
What is it: In-memory relational DB used for tests.
Why: Fast tests and isolation; no dependence on external DB.
Milk Web example: tests run using H2 configured in application-dev.properties.

## Repository lookup by primary key
What is it: A repository method that queries a record by its unique identifier, usually `findById(...)`.
Why we use it: It is the direct and efficient way to fetch a single entity when the consumer already has a stable ID.
Why not the obvious alternative: We do not fetch all customers and filter in memory; the DB handles the lookup using the primary key index.
Milk Web example: CustomerService.getById(customerId) delegates to `customerRepository.findById(customerId)` for the owner-read endpoint.
Time complexity: O(1) average-case for key lookup in a structured DB index.
Space complexity: O(1) for the in-memory result object, excluding framework overhead.
Trade-offs: Very fast, but requires a meaningful identifier and correct authorization checks in the service layer.
Interview explanation: We use primary-key lookups when the API already knows the record ID; this is lower-cost and simpler than list-and-filter patterns.

## Repository findAll and in-memory listing
What is it: `JpaRepository.findAll()` returns all entities of a type from the database.
Why we use it (first version): Simple, minimal API to return all records for small datasets and rapid development.
Milk Web example: CustomerService.listAll() delegates to `customerRepository.findAll()` to back GET /api/owner/customers.
Time complexity: O(n) where n is the number of customers (DB read + mapping).
Space complexity: O(n) memory in the application for the list of entities and DTOs.
Trade-offs:
- Pros: Simple to implement, matches existing DTO boundaries, minimal frontend changes.
- Cons: Not suitable for large n; may cause high memory usage and longer response times.

## N+1 and fetch strategies
Problem: Customer.user is LAZY. Mapping `customer.getUser().getId()` for each customer can trigger an extra SELECT per customer (N+1).
Mitigations:
- For small datasets accept the simple approach.
- If N+1 becomes a problem, use a fetch join or @EntityGraph to retrieve Customer with User in one query, e.g.:
  - @Query("select c from Customer c join fetch c.user")
  - or define a repository method with @EntityGraph(attributePaths = {"user"})
- Another option: use a projection or DTO fetch in the repository to avoid entity materialization.

Why we deferred pagination/fetch-join now: incremental change, keep API small and easy to test. Add pagination and fetch-join in a later milestone when load/UX warrants.

## PUT update semantics
What is it: PUT replaces the editable representation of a resource at a known URL.
Why we use it: The OWNER update request contains the complete editable customer profile.
Milk Web example: PUT /api/owner/customers/{customerId} updates name, address, billingType, status, and joinDate.
Trade-offs: Full representations are straightforward to validate, but clients must send all required editable fields. PATCH may be more convenient for partial changes but requires clearer omission/null semantics.

## Request DTO validation and entity boundaries
What is it: A request DTO defines and validates the fields an API operation accepts without binding directly to a JPA entity.
Why we use it: OwnerUpdateCustomerRequest excludes IDs, authentication fields, role, password data, and nested relationships.
Milk Web example: The update endpoint accepts only editable customer profile fields and returns CustomerResponse.
Security implication: Excluding password, passwordHash, role, and phone prevents this profile endpoint from becoming an authentication-account mutation flow.

## Transactional customer updates
What is it: A transaction groups the database work for one logical operation.
Why we use it: CustomerService.updateCustomer is transactional and provides a consistent boundary even though this milestone changes only Customer fields.
Milk Web example: The update loads one Customer, changes its editable fields, and returns it within one service transaction.
Time complexity: O(1) application-side work for one customer, excluding database internals.
Space complexity: O(1) application-side working memory, excluding persistence-context overhead.

## Deferring phone modification
What is it: Phone is both Customer profile data and the User authentication/login identifier.
Why we defer it: Updating it safely would require coordinated account-identifier handling across both entities and additional security considerations.
Milk Web example: OwnerUpdateCustomerRequest intentionally has no phone field, and the update endpoint leaves User and phone data unchanged.
INFERENCE: A future dedicated account-identifier flow may require re-authentication, duplicate checks, and carefully coordinated updates.

## BigDecimal for product prices
What is it: An arbitrary-precision decimal type suitable for exact decimal arithmetic.
Why we use it: Product prices are monetary values; binary floating-point types can introduce representation and rounding errors.
Milk Web example: Product.price uses BigDecimal and is persisted as NUMERIC(12,2).
Trade-offs: Precision and scale must be chosen, and rounding behavior must be explicit in future calculations. This API rejects prices with more than two fractional digits rather than silently rounding them.

## Database uniqueness constraints
What is it: A database-enforced rule that prevents duplicate values even when multiple requests race.
Why we use it: Product names are unique in the database; the service pre-check provides a clear 409 for ordinary duplicate requests, while the constraint remains authoritative for concurrent writes.
Milk Web example: `uk_products_name` enforces uniqueness on products.name.
Trade-offs: Name matching follows the database collation; case-insensitive normalization is not implemented.

## Product status and historical price
What is it: ProductStatus represents whether a product is currently offered; Product.price is the current catalog price.
Why we use it: INACTIVE products remain available to owners for management without deleting product identity.
Milk Web example: OWNER can update Product.status as part of the complete PUT request.
PLANNED / NOT YET IMPLEMENTED: DailyRecord must snapshot unit price at supply time; historical bills must not use a later Product.price.
Trade-offs: An enum is more descriptive and extensible than a boolean but adding values requires code support. Product names use a direct unique constraint rather than a normalized name column; matching follows the database collation.

## Product operation complexity
- Get by UUID: O(1) application work; a database primary-key index lookup is typically O(log n).
- List: O(n) application mapping and O(n) response memory for n products; database reads all matching rows.
- Create: O(1) application work; uniqueness/index checks are typically O(log n).
- Update: O(1) application work; indexed lookup, uniqueness check, and update are typically O(log n) database operations.
These database complexities are typical index behavior, not a guarantee for every database query plan.

INFERENCE: The project initially assumes one currency, likely INR based on existing examples. The Product API therefore has no currency field; revisit if multi-currency support is required.

## Future / when introduced
- Caching strategies (if performance needs grow)
- Pagination and filtering for list endpoints
- Index tuning and query optimization when adding larger modules

For each concept added later, add an entry in this document describing trade-offs, complexity, and example usage in Milk Web.
