package com.radhikafarms.milkweb.billing;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

public interface BillingPeriodRepository extends JpaRepository<BillingPeriod, UUID> {

    Optional<BillingPeriod> findByPeriodStart(LocalDate periodStart);

    boolean existsByPeriodStart(LocalDate periodStart);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from BillingPeriod p where p.periodStart = :periodStart")
    Optional<BillingPeriod> findByPeriodStartForUpdate(@Param("periodStart") LocalDate periodStart);
}
