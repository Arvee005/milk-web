# ADR-011: Flyway and PostgreSQL Schema Migrations

## Status
Proposed - recommended for owner/maintainer approval. No dependency, configuration, migration, baseline, or database operation has been added or executed.

## Context
Milk Web uses Spring Data JPA/Hibernate mappings for eight tables. Development and test properties explicitly use in-memory H2 with `spring.jpa.hibernate.ddl-auto=create-drop`. The PostgreSQL JDBC dependency is present, but application properties do not configure a PostgreSQL URL or credentials. No Flyway/Liquibase dependency, SQL migration, schema snapshot, or production database inspection was found.

The mappings do not prove that a real PostgreSQL schema matches the repository. Production data is unknown and must not be assumed absent.

## Decision proposal

1. Adopt Flyway with reviewed, PostgreSQL-specific, versioned SQL as the single schema-change authority.
2. For an empty database, apply a complete initial `V1__baseline_schema.sql` representing the verified current schema, then add forward-only versions.
3. For an existing populated database, inspect and reconcile schema/data against V1 first. Only then perform an explicit Flyway baseline at V1. Baseline records history; it does not create, repair, or validate schema/data. Never use automatic baseline-on-migrate.
4. Explicitly set Hibernate `ddl-auto=validate` for migration-managed application profiles; reserve `create-drop` for disposable isolated tests.
5. Run migrations once in a deployment step before the application starts, especially before multiple instances/rolling deployments. Fail deployment/readiness on migration or validation errors.
6. Use backups, staging rehearsals, expand-and-contract changes, and restore/forward-fix procedures. Do not rely on down migrations for data recovery.

The baseline strategy for any real database remains conditional on authorized inspection. This ADR does not claim a live schema was inspected or baselined.

## Alternatives considered

### Liquibase
Liquibase provides SQL and declarative changelogs, changeset checksums, and broader database abstraction. It may be preferable for multiple database vendors or complex conditional changelog needs. For Milk Web's single intended PostgreSQL platform and small schema, its additional changelog formats and operational vocabulary are not currently justified.

### Hibernate schema update/generation
Hibernate can create/update a schema from entity metadata, but automatic updates are not a reviewed migration history and can silently make production changes. It cannot safely encode all data transformations or deployment compatibility steps. Rejected for durable environments.

### Manual untracked SQL
Can make a one-time change quickly but does not reliably record ordering, checksums, or which environments received it. Rejected as the normal schema lifecycle.

## Consequences

### Positive
- Schema changes are reviewable, ordered, checksummed, repeatable on fresh databases, and traceable by environment.
- PostgreSQL-specific constraints, data conversions, indexes, and operational behavior remain explicit.
- Hibernate validation detects many mapping/schema mismatches without mutating production.
- A disciplined baseline can adopt an existing database without recreating it.

### Costs and risks
- Requires a reviewed initial DDL snapshot and careful reconciliation for each existing database.
- Requires PostgreSQL migration/integration tests; H2 remains useful for fast tests but is not equivalent.
- Flyway version and PostgreSQL support-module compatibility must be checked against Spring Boot 4.1 dependency management before setup.
- Recovery still requires verified backups and operational rehearsal; versioned migration history is not a backup.

## Preconditions

- Inspect every target database read-only before choosing or applying a baseline.
- Confirm actual PostgreSQL DDL, data quality, drift, extensions, and ownership; no production database is presumed empty.
- Reconcile the entity/database documentation mismatch for `users.phone` (JPA width 15; API validation currently expects ten digits) and decide canonical V1 constraints.
- Review/checkpoint the combined uncommitted application and documentation work before implementing migration infrastructure. Current worktree is dirty; no commit was made for this planning phase.
- Confirm hosting, credentials/secrets, backup/restore responsibilities, explicit profile behavior, pool sizing, and financial policy before financial schema work.

## Follow-up

See [Database Migration Strategy](../database-migration-strategy.md) for inventory, Flyway/Liquibase comparison, baseline alternatives, lifecycle, recovery, PostgreSQL readiness, financial dependencies, and migration test/release gates.
