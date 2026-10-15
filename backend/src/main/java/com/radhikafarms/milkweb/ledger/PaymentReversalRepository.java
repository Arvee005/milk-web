package com.radhikafarms.milkweb.ledger;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentReversalRepository extends JpaRepository<PaymentReversal, UUID> {

    Optional<PaymentReversal> findByPaymentId(UUID paymentId);

    List<PaymentReversal> findByPaymentIdIn(Collection<UUID> paymentIds);
}
