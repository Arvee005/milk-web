# ADR-004: User and Customer Separation

## Decision
Keep authentication identity (User) separate from business profile (Customer).

## Status
Accepted (implemented)

## Context
Authentication data (phone, password hash, role) should not be mixed with business/customer profile data (name, address, billing preferences).

## Decision
Model User as the authentication entity and Customer as business data linked to User via user_id.

## Why We Chose It
- Clear separation of concerns
- Ability to have OWNER users without Customer records
- Avoid accidental exposure of authentication data

## Alternatives Considered
- Single combined table: simpler but mixes concerns and risks exposing sensitive fields in business queries

## Advantages
- Better security boundaries
- Clearer domain model

## Disadvantages / Trade-offs
- Requires joins to fetch combined data

## Security Implications
- Reduces chance of accidentally exposing passwordHash in business DTOs

## Interview Takeaway
Separate auth data from business data to minimize exposure of sensitive information and keep domain models focused.
