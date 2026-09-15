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

## Future / when introduced
- Caching strategies (if performance needs grow)
- Pagination and filtering for list endpoints
- Index tuning and query optimization when adding larger modules

For each concept added later, add an entry in this document describing trade-offs, complexity, and example usage in Milk Web.
