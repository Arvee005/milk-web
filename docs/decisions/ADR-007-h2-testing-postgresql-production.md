# ADR-007: H2 for Testing / PostgreSQL for Production

## Decision
Use H2 in-memory for tests and PostgreSQL for production.

## Status
Accepted (implemented)

## Context
Tests should run fast and be isolated; production must use a reliable RDBMS.

## Decision
Configure H2 for tests (in-memory) and declare PostgreSQL JDBC dependency for production in pom.xml.

## Why We Chose It
- H2 enables fast, isolated tests without external dependencies
- PostgreSQL is a production-grade RDBMS with features we may need later

## Alternatives Considered
- Use PostgreSQL for tests: more accurate but requires CI/DB setup

## Advantages
- Fast tests, developer convenience

## Disadvantages / Trade-offs
- H2 and PostgreSQL dialect/behavior differences can cause subtle issues; ensure tests don't rely on DB-specific behavior

## Interview Takeaway
H2 is a pragmatic choice for tests; keep integration tests against PostgreSQL in CI if you rely on DB-specific features.
