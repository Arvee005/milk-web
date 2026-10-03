# Milk Web Documentation

This documentation records the design, implementation, and rationale for the Milk Web project. It is intended to be the single source of truth for project history, architecture, API design, testing, security, learning notes, interview-ready explanations, and decision records (ADRs).

Structure
- development-log.md — chronological milestones and narrative (commits, reasons, lessons)
- architecture.md — high-level architecture and layer responsibilities
- database-design.md — current database entities and constraints (implemented only)
- database-migration-strategy.md — proposed production schema/migration strategy (not implemented)
- api-design.md — documented API endpoints, DTOs, validation, and status codes
- testing.md — testing strategy, how tests are organized and run
- security.md — authentication/authorization and security considerations
- customer-dashboard-plan.md — customer/owner dashboard coverage and remaining gaps
- financial-policy-decisions.md and payment-domain-design.md — proposed payment/balance domain (not implemented)
- learning-notes.md — educational notes for concepts used in the project
- interview-notes.md — concise interview-ready answers about major decisions
- decisions/ — architecture decision records (ADRs)

How to update
- After completing any meaningful implementation milestone, update development-log.md and add/adjust ADRs in docs/decisions/. Document facts and clearly label inferences.
- Do not modify production code in the docs update process.

Implemented vs planned
- Files in this folder document only implemented features unless explicitly marked "PLANNED / NOT YET IMPLEMENTED".
- Future features and domain concepts are recorded as planned with clear labels.

Automation
- The docs are stored in the repository. They may be exported or packaged for publication, but that generation step is outside the scope of these files.

Maintainers
- Keep documentation concise and factual. Where a reason is not explicitly recorded, annotate it as an inferred rationale.
