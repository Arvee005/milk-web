# Production Database and Migration Strategy

## Status and scope

This is a planning document based on repository inspection. It does not configure Flyway, create migrations, inspect/connect to a production database, or prove a PostgreSQL deployment. Recommendations are not implemented.

Labels:
- **Verified in repository:** directly visible in checked-in configuration or JPA mappings.
- **Recommendation:** proposed future operating practice.
- **Unresolved / prerequisite:** cannot safely be decided from this repository alone.

## Executive recommendation

Adopt Flyway with reviewed, PostgreSQL-specific SQL migrations as the sole production schema authority. For a fresh database, apply a complete initial versioned migration representing the verified current schema, then subsequent forward migrations. For any already-populated database, first inspect and reconcile its actual schema and data against that initial version; only then baseline it at the corresponding version. Never enable automatic baselining or assume production is empty.

Use Hibernate `validate` in application environments that use the managed schema. Hibernate must not create, update, or drop production schema. Keep H2 `create-drop` only for fast isolated tests that intentionally test application logic, and add PostgreSQL-backed migration/schema/concurrency verification before relying on PostgreSQL.

Before implementation: inspect all existing target databases; resolve the initial baseline; review and checkpoint the currently uncommitted owner DailyRecord frontend work; choose credentials/hosting/backup operations; and get owner approvals for any financial policies that affect schema.

## 1. Current repository database architecture

### Verified configuration

- Spring Boot 4.1.0, Java 21, Spring Data JPA/Hibernate are declared in `backend/pom.xml`.
- Runtime dependencies include H2 and the PostgreSQL JDBC driver. The H2 dependency is not restricted to test scope.
- `application-dev.properties` points to `jdbc:h2:mem:milkdev`, uses `sa` with an empty password, sets H2 dialect, and explicitly sets `spring.jpa.hibernate.ddl-auto=create-drop`.
- `backend/src/test/resources/application.properties` points to `jdbc:h2:mem:milktest`, uses an empty-password H2 user, and also explicitly uses `create-drop`.
- `backend/src/main/resources/application.properties` sets the application name and JWT properties only. It does not declare a PostgreSQL URL, driver, database name, database username/password, active production profile, pool settings, migration tool, or `ddl-auto`.
- `MILKWEB_JWT_SECRET` is the only environment variable explicitly configured in the inspected application properties. PostgreSQL credentials and connection configuration are not wired in repository configuration.
- No SQL schema/data scripts, Flyway or Liquibase dependency/configuration, migration directory, Docker Compose database service, or `.env` file was found in the inspected repository paths.
- The repository has no known production schema snapshot. JPA annotations are the current entity mapping, not proof of the schema deployed anywhere.
- Tests use isolated in-memory H2. Many integration tests are transaction-scoped; the billing-period concurrency test uses JDBC and independent transactions. There are no migration tests, PostgreSQL integration profile, or committed DB lifecycle scripts.

### Startup implications and risk

The normal runtime properties do not require PostgreSQL. Because embedded H2 is on the runtime classpath, a launch without externally supplied datasource settings can select an embedded database rather than fail clearly as a production misconfiguration. The exact Spring Boot default behavior depends on active profile and external configuration; it must be verified during deployment setup. Do not rely on implicit datasource or Hibernate defaults in production.

**Recommendation:** Introduce explicit profiles and required environment-backed PostgreSQL datasource settings. Production startup must fail closed when a PostgreSQL URL/credentials are absent; it must never silently run against H2. Keep database passwords out of source, command history, logs, and committed files. Use the deployment platform's secret manager or protected environment injection.

## 2. Schema inventory and documentation comparison

Eight entity tables are represented in JPA mappings:

| Table | Primary relationships and notable constraints/indexes |
|---|---|
| `users` | UUID primary key; unique `uk_users_phone`; role string; BCrypt hash; creation instant. |
| `customers` | UUID; required FK to `users`; unique `uk_customers_user_id`; billing type/status strings; join date. |
| `products` | UUID; unique `uk_products_name`; unit/status strings; price `NUMERIC(12,2)`; creation/update instants. |
| `subscriptions` | UUID; required customer/product FKs; quantity `NUMERIC(8,3)`; dates/status; positive-quantity and ordered-date checks; customer/status/date and product/status/date indexes. No DB exclusion/unique rule prevents overlapping active periods. |
| `daily_records` | UUID; required customer/product FKs and optional subscription FK; unique customer/product/date/slot; customer/date and product/date indexes; quantity `NUMERIC(8,3)`, price snapshot `NUMERIC(12,2)`, amount `NUMERIC(18,2)`; check constraints for nonnegative values and status/quantity/snapshot shape. |
| `billing_periods` | UUID; unique period start; optional closer User FK; status/closure metadata check. Canonical month bounds and chronological order are currently application rules. |
| `bills` | UUID; required period/customer FKs, optional issuer User FK; unique period/customer; customer/period index; total `NUMERIC(18,2)`; issue metadata/status check and positive-total check; immutable period/customer snapshots. |
| `bill_lines` | UUID; required Bill and DailyRecord FKs; unique source DailyRecord; Bill index; quantity `NUMERIC(8,3)`, unit price `NUMERIC(12,2)`, amount `NUMERIC(18,2)`; positive/nonnegative value check and snapshot fields. |

The database design document correctly describes the broad entity set, decimal scales, names, intended relationships, and most named constraints/indexes. It is not executable DDL and several details require correction or explicit migration-time verification:

- `User.phone` is mapped with `length = 15`; `docs/database-design.md` describes it as length 10. Current request validation constrains login phone to ten digits, but DB schema capacity and input validation are distinct. Decide whether to retain width 15 for international/future format or alter to 10 only after inspecting stored values and dependent clients.
- Customer's `user_id` join column is unique/required, but unlike several other FKs it has no explicit constraint name in the mapping. Generated constraint names can differ. The database design should distinguish named mappings from provider-generated names.
- `DailyRecord` checks do not enforce that `amount = quantity * unit_price_snapshot` (with the application's rounding rule). They require chargeable quantity and a non-null snapshot and enforce zero amount for no-supply records. The relationship/formula is currently enforced by service logic; documentation that implies DB enforcement of exact arithmetic should be read as application validation, not a database check.
- Subscription overlap, BillingPeriod canonical month boundaries, and some enum value validity are enforced in services or Java enum mapping, not by portable explicit database constraints. PostgreSQL-specific constraint design may be considered later, but must be intentional and tested there.
- `@Column(updatable=false)`, service rules, and aggregate methods limit normal application writes but do not make rows immutable to direct SQL or guarantee a database-level append-only rule.
- FK delete behavior is not explicitly declared. The schema must not be assumed to cascade deletes; verify actual constraints and adopt explicit restrictive delete behavior for financial/history records. Any retention/deletion policy is still to be decided.
- Hibernate naming strategy, UUID SQL mapping, timestamp precision/with-time-zone mapping, generated constraint names, and implicit defaults can vary with provider/dialect/version. The current docs do not provide a vendor-specific DDL snapshot.

The project currently has no Payment, PaymentAllocation, BalanceAdjustment, currency, or account-summary table/column. Any such design remains proposed and must not be included in an initial baseline unless it is independently approved and implemented in a later migration.

## 3. Migration tool evaluation

| Criterion | Flyway | Liquibase |
|---|---|---|
| Spring Boot integration | Mature Boot integration; migration execution/validation can participate in startup. Ensure the selected Flyway version and PostgreSQL database support module are included and version-aligned with the Boot BOM. | Mature Boot integration; changelog validation and migration can participate in startup. |
| Small-project workflow | Simple ordered SQL files such as `V1__baseline_schema.sql`, `V2__...sql`; easy for engineers to review as database-native PostgreSQL DDL. | XML/YAML/JSON changelogs or formatted SQL; richer abstraction, but adds changelog conventions and more concepts. |
| Versioning/repeatability | Versioned migrations run once in order and are recorded in `flyway_schema_history`; repeatable migrations rerun on checksum change and should be reserved for suitable objects such as views/functions. | Changesets are tracked in `DATABASECHANGELOG` and checksummed; supports repeatable/run-on-change patterns with more configuration. |
| Rollback/recovery | Community workflow is forward-fix; transactional PostgreSQL DDL is often rollback-capable, but not every operation is transactional or safely reversible. Do not assume `undo` is available or use down scripts as recovery. | Rollback blocks can be supplied and generated for some changes, but not every data transformation can be safely reversed. A rollback definition is not a substitute for backup/restore. |
| PostgreSQL | SQL can express PostgreSQL types, partial indexes, constraints, and online index operations directly. Keep PostgreSQL-specific behavior explicit and tested. | Strong PostgreSQL support; database abstraction can help if multiple vendors are a real requirement, but generated SQL must still be reviewed. |
| Tests/operations | Straightforward to run against disposable PostgreSQL databases and inspect the SQL/history table. Minimal operational vocabulary. | Good test integration; adds changelog parsing, logical changeset identifiers, and authoring conventions. |
| Maintenance overhead | Low for a single PostgreSQL production database and a small engineering team. | Higher flexibility and more abstraction than currently needed; beneficial if cross-database portability or complex conditional changes become requirements. |

### Recommendation (not installed/configured)

Choose **Flyway with PostgreSQL-specific versioned SQL migrations**. Milk Web targets one production database platform and the schema is small enough for explicit SQL to remain reviewable. SQL makes constraints, locks, indexes, and data transformations visible rather than hiding vendor details. Liquibase remains a reasonable alternative if the product later requires many database engines or sophisticated conditional changelogs.

Before adding it, verify the Spring Boot 4.1-managed Flyway version and whether the PostgreSQL support module is a separate required dependency for that release. Pin no unrelated versions without checking the Boot dependency management. Keep only one tool responsible for schema changes; never mix Hibernate `update`, Flyway, and hand-run untracked DDL.

## 4. Initial version and existing-schema baseline

### What is known and unknown

The current eight-table design is inferable from entity mappings, and the local `dev` and `test` configurations are in-memory H2 databases generated with Hibernate `create-drop`. Their schema is disposable by configuration. This does **not** establish the exact schema or data in a developer's separate PostgreSQL instance, a shared environment, staging, or production. No production connection or schema inspection was performed.

Before selecting a baseline, inspect every target database read-only and under authorized access: server/database identity, schema and search path, table/column types, nullability/defaults, PK/FK/unique/check constraints, indexes, extensions, enum/storage types, row counts, duplicate/orphan data, maximum lengths, nulls, sequence/generator behavior, and representative sanitized data-quality results. Preserve an encrypted backup/snapshot before any write. Compare actual metadata and data against a reviewed canonical PostgreSQL schema generated from current mappings plus intentionally chosen constraints.

### Approach A: create from an empty database using versioned history

- Create `V1__baseline_schema.sql` representing the reviewed current schema; test it on an empty disposable PostgreSQL database.
- Subsequent changes are `V2`, `V3`, etc.
- Fresh local/staging/production databases apply V1 forward; migration history records what ran.
- Existing data is not copied or erased; this approach alone cannot adopt an already-populated database. That database requires the reconciled baseline process below.
- Best for reproducibility and schema drift detection. V1 becomes a maintained historical artifact; do not rewrite it after it has been applied anywhere.

### Approach B: baseline an existing schema without applying V1

- Inspect and compare the existing database to the exact schema represented by V1.
- Reconcile any differences with a separately reviewed, data-preserving procedure before marking the database at V1. Preserve the exact reconciliation SQL/steps and verification results as an audit artifact; do not make undocumented manual edits.
- Use an explicit Flyway `baseline` operation at the approved version only after backup and verification. Baseline records a starting point; it does not create, validate, or repair tables.
- Future migrations V2+ run against that database; a fresh database still must run V1, not skip it.
- Never use `baseline-on-migrate=true` as a shortcut: it can declare an unknown non-empty database current without proving it matches the expected schema.

### Recommended decision

Use Approach A for every genuinely empty database. If a target database already contains application data, use Approach B only after inspection proves it is compatible with V1 or a reviewed reconciliation makes it compatible. Do not choose a single global baseline strategy based solely on H2 configuration.

If the schema cannot be established confidently from repository and database inspection, stop: capture actual PostgreSQL DDL and data-quality evidence, reconcile it with the owner, and only then create V1. Never create V1 by running Hibernate `create` against H2 and assuming PostgreSQL equivalence.

**Data preservation:** Keep the original database unchanged during audit; take and verify a restorable backup; do not truncate, drop, rename, or bulk-convert data in a baseline operation. Use staged, reviewed data migrations for transformations. A production baseline is a controlled metadata operation, not a data migration.

## 5. Recommended migration lifecycle

### Local development

1. Keep fast unit/service tests isolated on H2 if useful.
2. Provide a disposable PostgreSQL database for schema development and migration validation; use the same PostgreSQL major version as production where practical.
3. Create one forward-only versioned SQL migration for each reviewed schema change. Include rationale, preconditions, expected row impact, operational duration/locking assessment, and rollback/recovery notes in the pull request.
4. Run migrations from an empty PostgreSQL database and from a copy of the preceding version with representative synthetic data.
5. Run Hibernate `validate`, persistence tests, and data-integrity queries. Inspect resulting DDL and query plans for important indexes.
6. Never point local destructive workflows at a shared or production connection.

### CI and automated testing

- On every backend change, run existing H2 tests for fast domain regression feedback.
- Run migration integration jobs against fresh PostgreSQL and prior-version PostgreSQL databases; verify ordered execution, Flyway history/checksums, Hibernate validation, and preserved data/invariants.
- Run PostgreSQL-specific constraint, FK, transaction, and concurrency tests against PostgreSQL rather than treating H2 success as evidence.
- Use disposable databases with synthetic data only. Do not load a production dump into broadly accessible CI.

### Staging

1. Restore a recent, access-controlled production backup into staging or a protected rehearsal environment when policy permits; otherwise use sanitized representative data with equivalent schema/volume characteristics.
2. Apply exactly the candidate migration command/artifact and app version intended for production.
3. Measure lock duration, migration runtime, row counts, data-validation results, application startup, health, and core owner/customer workflows.
4. Test the documented restore/forward-fix decision. A rehearsal against a clean empty database alone does not validate existing-data safety.

### Production deployment

1. Review the exact commit, migration checksum, expected schema version, expected data changes, disk/lock requirements, maintenance plan, and tested recovery procedure.
2. Confirm a recent backup exists and that restore is possible; take a pre-deploy snapshot/backup appropriate to the provider and recovery-point objective.
3. For the early single-instance deployment, stop or quiesce writes if the migration is not demonstrably online. Run the migration once using a dedicated deployment step/identity; do not let every application replica race to migrate.
4. Require Flyway validation and Hibernate schema validation before serving traffic. On migration/validation failure, fail deployment/startup visibly, keep the previous compatible app if safe, and do not continue startup against an unknown schema.
5. Verify schema history, migration version, application health, row counts, critical invariants, and representative workflows. Observe logs and database health after traffic resumes.
6. Keep a clear pause/restore/forward-fix decision owner and escalation contact. Do not auto-run destructive cleanup.

For a very small single-instance installation, Flyway may run from the application at startup only if deployment guarantees one instance, the exact schema migration has been rehearsed, and failure prevents serving. A separate single migration job is preferred as soon as multiple instances or rolling deployment is used. Make the chosen mode explicit; do not rely on Boot defaults.

### Application startup and Hibernate

- Local disposable schema-generation may remain explicitly configured as `create-drop` for isolated H2 tests only.
- All migration-managed PostgreSQL profiles should set `spring.jpa.hibernate.ddl-auto=validate`; set the property explicitly, including production.
- Disable Hibernate DDL generation in production. Do not use `update`, `create`, or `create-drop` on durable data.
- Configure Flyway validation and failure to prevent application readiness on failed migration/checksum. Do not silently repair the history table.
- On failure, preserve logs and database state, stop rollout, inspect the failed SQL and Flyway history, then either restore a verified backup or apply a reviewed forward fix. `flyway repair` only repairs metadata/checksums and must not be used to conceal a partially applied schema.
- Keep schema-owner/migration credentials separate from the least-privileged runtime application credentials when the hosting platform permits. Runtime requires only the DML and sequence/identity privileges it needs, not arbitrary schema creation.

## 6. Backward-compatible changes and recovery

Prefer **expand-and-contract** when application instances may overlap or rollback to the prior binary is required:

1. **Expand:** Add nullable columns, new tables/indexes, or compatible constraints without removing/renaming fields the old app needs. For large PostgreSQL indexes, assess `CREATE INDEX CONCURRENTLY` and its transaction restrictions.
2. **Deploy compatible application:** New code tolerates old and new schema and may dual-read/write during transition. Keep old code compatible with the expanded schema.
3. **Backfill:** Update in bounded, restartable batches with progress, rate/lock controls, row-count checks, and idempotent logic. Avoid a single large transaction for large tables.
4. **Validate/contract later:** Verify no null/old-format rows remain, add constraints in a suitable staged/validated manner, switch reads, then remove old columns or indexes in a later release after rollback window closes.

Before any destructive contract migration, confirm no old application process, job, client, or restore plan requires the old shape. Destructive changes and non-backward-compatible data transformations may need a maintenance window, write pause, coordinated application release, and tested restore. PostgreSQL transactional DDL helps but does not guarantee every command can be rolled back; concurrent index operations and external side effects require special handling.

Database recovery is not the same as application rollback:
- Rolling back an app binary is safe only while the expanded schema remains backward-compatible.
- If a migration is additive and the old app works, prefer app rollback while leaving the harmless expanded schema in place.
- If data has been transformed or dropped, restoring a backup loses writes after the backup; decide whether that loss is acceptable before deployment.
- For irreversible migration/data errors, stop writes and restore to a separately verified target or apply a forward repair with an auditable reconciliation. Do not run destructive “down” SQL by default.

## 7. PostgreSQL production readiness

### Types, precision, UUIDs, and time

- Existing product/unit prices use `NUMERIC(12,2)`; quantities use `NUMERIC(8,3)`; amounts/totals use `NUMERIC(18,2)`. PostgreSQL `NUMERIC` provides exact decimal storage, but API/service rounding and precision validation remain essential. Financial currency/scale decisions are unresolved; do not alter or label historical amounts as part of the initial baseline.
- Entities use Java UUID and `GenerationType.UUID`. Verify the Hibernate/PostgreSQL mapping and any pre-existing UUID representation during baseline; PostgreSQL native `uuid` is recommended for new schema.
- `LocalDate` values represent dates, not instants: `record_date`, subscription dates, join dates, and billing-period bounds must not shift with timezone conversions. Use PostgreSQL `date`.
- `Instant` values represent points in time: creation, issue, and closure timestamps should be stored/handled as instants (PostgreSQL `timestamp with time zone` semantics), with UTC at persistence/API boundaries. PostgreSQL `timestamptz` preserves an instant, not the original timezone label.
- Business-day rules use the explicit `Asia/Kolkata` zone and an injected business `Clock`. Keep deriving the business `LocalDate` from that zone; do not rely on PostgreSQL session timezone or machine-local time for a collection date. Configure sessions/servers consistently (UTC is a suitable operational default) and test boundary cases at India midnight.
- Current entity callbacks call `Instant.now()` directly for entity timestamps, while business services use an India-aware clock for business dates/selected event times. A future timestamp/audit policy should clarify whether callbacks should use an injected clock; no code changes are made here.

### Constraints, indexes, transactions

- Preserve primary/unique constraints and named FK/check/index definitions from reviewed DDL. Verify exact names against PostgreSQL metadata; generated names are not stable contracts.
- Review explicit `ON DELETE` behavior. For customers, bills, records, and financial/audit history, restrictive deletion or soft-deactivation is safer than cascades. The JPA model does not establish a deliberate delete policy.
- H2 is not evidence of PostgreSQL behavior for constraint validation, partial indexes, UUID mapping, transaction isolation, locking, deadlocks, timestamp semantics, query planner/index use, or transactional DDL. Subscription overlap currently has only an application-level check and may race on PostgreSQL; billing-period lock behavior likewise must be exercised against PostgreSQL.
- Use standard PostgreSQL transactions and consistent lock ordering for financial aggregate updates; choose the isolation/locking approach from concurrency tests, not assumptions. Retry only well-understood transient serialization/deadlock failures, with idempotent requests.
- Spring Boot's JPA starter normally supplies HikariCP when on the classpath, but the repository has no explicit pool sizing/timeouts/lifetime settings. Configure modest, measured pool limits based on database connection capacity and instance count. Set connection/acquisition timeouts and monitor active/idle/pending counts; do not increase pool size as a substitute for query/lock diagnosis.

### Credentials, backups, health, and operations

- No PostgreSQL credentials, host configuration, or secret-store integration is committed. Inject required URL/user/password through protected environment or managed secret storage. Fail startup if required production variables are absent; do not log them.
- Keep the existing development-only JWT secret isolated; production JWT and database secrets are independent secrets and must be rotated/managed separately.
- Define backup frequency, retention, encryption, access, and recovery-point/recovery-time objectives with the database host/provider. Use automated PostgreSQL backups plus point-in-time recovery/WAL archiving if supported and proportionate; at minimum take verified pre-migration backups and periodic restorable backups.
- Schedule and document restore drills to an isolated database. A successful backup job is not proof of recoverability. Validate row counts, constraints, and application operation after restore.
- Spring Boot Actuator is present. Expose database health only to operationally authorized health checks; do not expose database metadata publicly. Add monitoring for connection exhaustion, disk growth, migration version/failures, slow queries, lock waits, backup age, and restore readiness at a level proportionate to hosting.
- Define data retention and deletion access rules before financial history is built. Restrict direct DB access, audit privileged access where the provider supports it, encrypt transport and storage through platform settings, and avoid copying full customer financial data into logs or CI artifacts.

## 8. Financial-domain compatibility

The ledger proposal implies schema work, but the migration strategy must not turn proposals into requirements:

- **Receipts:** positive exact-currency amount, customer FK, received/recorded instants, actor FK, method/reference, idempotency key, and correction linkage. Constraints and uniqueness must match approved correction semantics.
- **Allocations:** append-only events/compensations, same-customer/same-currency relation to an issued bill, with locking that prevents total allocation exceeding receipt or bill obligation. Simple row checks cannot alone enforce sums across rows; transactional locking and PostgreSQL integration tests are required.
- **Adjustments:** actor, reason, signed direction/positive magnitude, currency, optional bill scope, and correction link. Approval workflow may require additional states/actors, but remains unresolved.
- **Balances:** prefer derived queries initially. If a projection is added later, make it rebuildable from immutable events with reconciliation; do not make a mutable balance the sole source of truth.
- **Currency snapshots:** future new bills/receipts should carry an explicit approved currency. Historical bill backfill must use reviewed evidence; unresolved rows must stay distinguishable and must not accept allocation.
- **Idempotency:** unique scoped idempotency key plus canonical request fingerprint; retention must be long enough to cover client retry behavior and must not accidentally permit duplicate recording after key expiry.
- **Audit attribution:** FK to authenticated OWNER and server-recorded timestamps. Retention/deactivation of User accounts must preserve actor history; do not cascade-delete financial records.
- **Transaction boundaries/concurrency:** receipt plus initial allocations should commit atomically; lock Customer/account and target Bills/receipt in consistent order; test competing updates under PostgreSQL. Avoid assuming H2 tests prove safety.

Required product decisions in [Financial Policy Decisions](./financial-policy-decisions.md) include operating currency and historical-bill meaning, PREPAID/credit behavior, allocation rules, advance/excess handling, receipt correction/refund rules, issued-bill correction/void behavior, adjustment reasons/approvals, and payment methods. Do not create a financial migration until the applicable decisions are approved. A baseline for the current schema should not include speculative financial tables or policy-dependent constraints.

## 9. Migration testing and release gates

### CI-required once migrations are introduced

- Fresh PostgreSQL database: apply all migrations in order; assert Flyway history/checksums and Hibernate `validate`.
- Upgrade path: create the previous version, insert synthetic representative data, migrate forward, and verify values, relations, indexes, and invariants remain.
- Baseline path: use an isolated PostgreSQL database with a reconstructed pre-existing schema; verify the explicit baseline procedure and that baseline itself changes no business rows. Do not call production.
- Failure path: introduce a deliberately failing migration in a disposable database; confirm migration records failure appropriately, startup/readiness stops, and recovery instructions work. Do not corrupt shared environments.
- PostgreSQL persistence suite: native UUID, decimal, date/instant, FK/delete, check/unique constraints, pagination/index queries, transaction rollback, locks, deadlocks, and concurrent allocation/overlap tests.
- Drift check: Hibernate `validate` catches entity/migration mismatch. It does not prove data quality, correct indexes, query performance, or unmodelled operational objects.

### Pre-production deployment gate

- Restore and rehearse the production backup in an isolated environment.
- Apply migrations from the exact prior schema version using the exact release artifact.
- Verify data preservation/transform counts, unresolved rows, constraints, app startup, read/write workflows, and migration duration/locks.
- Exercise app rollback while the expanded schema is present, and restore/forward-fix decision where migration is irreversible.
- Confirm owner-facing financial reports do not treat unresolved currency, refunds, or policy-dependent records as settled.

No migration, baseline, restore, or PostgreSQL validation was run for this planning phase.

## 10. Prerequisites and recommended next milestone

1. Review and checkpoint the uncommitted Owner DailyRecord entry/review work in the worktree (frontend-only slice; no schema, migration, or backend change) before introducing an unrelated migration foundation. Earlier milestones through commit `e45cfd0` ("feat: add customer dashboard and domain planning") are already committed. This task intentionally did not commit, discard, or checkpoint the new work.
2. Authorize a read-only inventory of any real PostgreSQL instances and identify their owners, backups, current schema version, and data sensitivity. Do not assume empty.
3. Resolve existing schema drift and the User phone-width documentation mismatch; choose canonical V1 DDL and whether application-enforced overlap/period/arithmetic rules need DB-level constraints.
4. Confirm Flyway/version/module compatibility with Spring Boot 4.1 and choose the production PostgreSQL provider, explicit profiles, secrets, pool settings, and backup/restore operations.
5. Obtain owner approval for relevant financial policy before adding currency or ledger schema.
6. Only then implement Flyway configuration, a reviewed baseline migration, migration verification, and PostgreSQL integration coverage in a separate approved implementation milestone.
