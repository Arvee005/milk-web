package com.radhikafarms.milkweb.billing;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

public interface BillRepository extends JpaRepository<Bill, UUID> {

    Optional<Bill> findByBillingPeriod_IdAndCustomer_Id(UUID billingPeriodId, UUID customerId);

    @EntityGraph(attributePaths = "lines")
    @Query("select b from Bill b where b.id = :billId")
    Optional<Bill> findByIdWithLines(@Param("billId") UUID billId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Bill b where b.id = :billId")
    Optional<Bill> findByIdForUpdate(@Param("billId") UUID billId);

    @Query("""
            select b from Bill b
            where (:customerId is null or b.customer.id = :customerId)
              and (:periodStart is null or b.periodStart = :periodStart)
            """)
    Page<Bill> findFiltered(
            @Param("customerId") UUID customerId,
            @Param("periodStart") LocalDate periodStart,
            Pageable pageable
    );
}
