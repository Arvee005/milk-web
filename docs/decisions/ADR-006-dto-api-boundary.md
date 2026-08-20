# ADR-006: DTO API Boundary

## Decision
Use request/response DTOs for all controller endpoints and do not return JPA entities directly.

## Status
Accepted (implemented)

## Context
Exposing JPA entities directly from controllers can leak persistence details, lazy-loading proxies, and sensitive fields like passwordHash.

## Decision
Controllers accept and return DTOs (records) defined separately from entities. Mapping between entities and DTOs happens in controllers or services.

## Why We Chose It
- Provides a stable API surface
- Controls what fields are exposed
- Prevents accidental serialization of sensitive fields

## Alternatives Considered
- Returning entities directly: quicker but unsafe and brittle

## Advantages
- Safer APIs, clearer boundaries

## Disadvantages / Trade-offs
- Requires additional DTO mapping code

## Interview Takeaway
Always prefer DTOs at API boundaries to keep domain models internal and prevent leaking implementation details.
