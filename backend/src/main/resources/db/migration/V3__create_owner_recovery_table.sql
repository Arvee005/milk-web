-- Durable one-time marker for the production OWNER password recovery flow.
--
-- The startup recovery runner resets the existing OWNER's password hash only while
-- no row exists for that owner's phone, and it inserts exactly one row AFTER the
-- password_hash UPDATE in the SAME transaction. A committed row therefore proves the
-- reset completed, so restarts/redeployments skip recovery and can never repeat it.
--
-- The foreign key guarantees the marker can only reference an existing user.
CREATE TABLE owner_recovery (
    phone VARCHAR(15) NOT NULL,
    recovered_at TIMESTAMPTZ NOT NULL,
    recovered_by VARCHAR(255) NOT NULL,
    CONSTRAINT pk_owner_recovery PRIMARY KEY (phone),
    CONSTRAINT fk_owner_recovery_user FOREIGN KEY (phone) REFERENCES users (phone)
);
