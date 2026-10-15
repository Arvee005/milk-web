package com.radhikafarms.milkweb.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "owner_recovery")
public class OwnerRecovery {

    @Id
    @Column(length = 15)
    private String phone;

    @Column(name = "recovered_at", nullable = false)
    private Instant recoveredAt;

    @Column(name = "recovered_by", nullable = false, length = 255)
    private String recoveredBy;

    protected OwnerRecovery() {
    }

    public OwnerRecovery(String phone, Instant recoveredAt, String recoveredBy) {
        this.phone = phone;
        this.recoveredAt = recoveredAt;
        this.recoveredBy = recoveredBy;
    }

    public String getPhone() {
        return phone;
    }

    public Instant getRecoveredAt() {
        return recoveredAt;
    }

    public String getRecoveredBy() {
        return recoveredBy;
    }
}
