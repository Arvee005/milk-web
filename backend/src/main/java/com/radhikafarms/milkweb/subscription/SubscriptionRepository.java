package com.radhikafarms.milkweb.subscription;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface SubscriptionRepository extends JpaRepository<Subscription, UUID> {

    @Query("""
            select s from Subscription s
            where s.status = com.radhikafarms.milkweb.subscription.SubscriptionStatus.ACTIVE
              and s.customer.id = :customerId
              and s.product.id = :productId
              and (:excludedId is null or s.id <> :excludedId)
              and (:candidateEndDate is null or s.startDate <= :candidateEndDate)
              and (s.endDate is null or :candidateStartDate <= s.endDate)
            """)
    List<Subscription> findOverlappingActiveSubscriptions(
            @Param("customerId") UUID customerId,
            @Param("productId") UUID productId,
            @Param("candidateStartDate") LocalDate candidateStartDate,
            @Param("candidateEndDate") LocalDate candidateEndDate,
            @Param("excludedId") UUID excludedId
    );

    @Query("""
            select s from Subscription s
            where (:customerId is null or s.customer.id = :customerId)
              and (:productId is null or s.product.id = :productId)
              and (:status is null or s.status = :status)
              and (:effectiveOn is null or (
                    s.status = com.radhikafarms.milkweb.subscription.SubscriptionStatus.ACTIVE
                    and s.startDate <= :effectiveOn
                    and (s.endDate is null or s.endDate >= :effectiveOn)
              ))
            order by s.startDate, s.id
            """)
    List<Subscription> findByFilters(
            @Param("customerId") UUID customerId,
            @Param("productId") UUID productId,
            @Param("status") SubscriptionStatus status,
            @Param("effectiveOn") LocalDate effectiveOn
    );
}
