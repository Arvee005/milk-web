# ADR-002: BCrypt Password Hashing

## Decision
Use BCrypt for hashing user passwords.

## Status
Accepted (implemented)

## Context
Passwords must be stored securely to protect user credentials.

## Decision
Use Spring's BCryptPasswordEncoder bean to hash passwords when creating Users.

## Why We Chose It
BCrypt is well-tested, includes a configurable work factor, and is designed to slow down brute-force attacks.

## Alternatives Considered
- Argon2: modern alternative with memory-hard properties (not currently chosen).
- PBKDF2: older standard.

## Advantages
- Mature, widely supported, secure.

## Disadvantages / Trade-offs
- CPU cost for hashing; choose work factor appropriate for environment.

## Security Implications
- Use a sufficiently high cost factor in production; monitor login throughput.

## Future Implications
- Evaluate Argon2 in the future for improved resistance to specialized hardware attacks.

## Interview Takeaway
BCrypt balances security and implementation simplicity; use a work factor appropriate for your environment.
