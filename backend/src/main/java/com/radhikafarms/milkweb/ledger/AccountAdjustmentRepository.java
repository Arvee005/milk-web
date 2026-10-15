package com.radhikafarms.milkweb.ledger;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccountAdjustmentRepository extends JpaRepository<AccountAdjustment, UUID> {

    Optional<AccountAdjustment> findByIdempotencyKey(String idempotencyKey);

    List<AccountAdjustment> findByCustomerIdOrderByRecordedAtDesc(UUID customerId);

    /** Account-level credits (not tied to a bill), which add to the customer's available credit. */
    @Query("""
            select coalesce(sum(a.amount), 0) from AccountAdjustment a
            where a.customerId = :customerId and a.billId is null
              and a.direction = com.radhikafarms.milkweb.ledger.AdjustmentDirection.CREDIT
            """)
    BigDecimal sumAccountCredits(@Param("customerId") UUID customerId);
}
