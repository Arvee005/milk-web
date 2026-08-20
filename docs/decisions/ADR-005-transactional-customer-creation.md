# ADR-005: Transactional User + Customer Creation

## Decision
Create User and Customer within a single transaction when Owner creates a Customer.

## Status
Accepted (implemented)

## Context
Customer creation requires creating authentication credentials (User) and business profile (Customer). Both must succeed or fail together to preserve data integrity.

## Decision
CustomerService.createCustomer is marked @Transactional and performs user creation (via UserService) and customer.save() within the same transaction.

## Why We Chose It
- Ensures atomicity: prevents orphaned User or Customer records

## Alternatives Considered
- Two-step process with compensating actions: more complex and error-prone

## Advantages
- Consistency and simplicity

## Disadvantages / Trade-offs
- Slightly larger transaction scope

## Security Implications
- When user creation fails, transaction rollbacks ensure no partial credentials leak into the system.

## Interview Takeaway
Wrap related DB operations in a transaction to guarantee atomicity and maintain invariants.
