package com.radhikafarms.milkweb.ledger;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RefundRepository extends JpaRepository<Refund, UUID> {

    Optional<Refund> findByIdempotencyKey(String idempotencyKey);

    List<Refund> findByCustomerIdOrderByRefundedOnDescRecordedAtDesc(UUID customerId);

    @Query("select coalesce(sum(r.amount), 0) from Refund r where r.customerId = :customerId")
    BigDecimal sumForCustomer(@Param("customerId") UUID customerId);
}
