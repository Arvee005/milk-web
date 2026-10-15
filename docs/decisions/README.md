# Architecture Decision Records (ADRs)

This folder contains ADRs that document important architectural decisions for Milk Web. Each ADR follows a consistent template and captures the context, alternatives, trade-offs, and consequences of the decision.

Naming: ADR-<NNN>-short-title.md

Format:
- Decision
- Status
- Context
- Decision
- Why We Chose It
- Alternatives Considered
- Advantages
- Disadvantages / Trade-offs
- Security Implications
- Performance Implications
- Future Implications
- Interview Takeaway

Keep ADRs factual. If the true reason is unknown, mark it as an inference with the label:

"Reason not explicitly recorded; inferred rationale: ..."

Current authentication and frontend decisions:
- ADR-001 — backend JWT authentication
- ADR-009 — browser login, tab-scoped token session, and owner workspace protection
- ADR-012 — public customer self-registration with owner approval (PENDING status)
- ADR-013 — payment ledger with automatic oldest-first allocation
