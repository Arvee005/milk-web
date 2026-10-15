package com.radhikafarms.milkweb.ledger;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    Optional<Payment> findByIdempotencyKey(String idempotencyKey);

    List<Payment> findByCustomerIdOrderByReceivedOnDescRecordedAtDesc(UUID customerId);

    /** Total of the customer's payments that have not been reversed. */
    @Query("""
            select coalesce(sum(p.amount), 0) from Payment p
            where p.customerId = :customerId
              and not exists (select 1 from PaymentReversal r where r.paymentId = p.id)
            """)
    BigDecimal sumActive(@Param("customerId") UUID customerId);
}
