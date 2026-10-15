package com.radhikafarms.milkweb.ledger;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface BillAllocationRepository extends JpaRepository<BillAllocation, UUID> {

    List<BillAllocation> findByCustomerIdOrderByCreatedAtAsc(UUID customerId);

    List<BillAllocation> findByBillIdInOrderByCreatedAtAsc(Collection<UUID> billIds);

    List<BillAllocation> findByTriggerIdOrderByCreatedAtAsc(UUID triggerId);

    /** Net credit applied to the customer's bills: allocations minus releases. */
    @Query("""
            select coalesce(sum(case when a.kind = com.radhikafarms.milkweb.ledger.AllocationKind.ALLOCATE
                                     then a.amount else -a.amount end), 0)
            from BillAllocation a where a.customerId = :customerId
            """)
    BigDecimal sumNetForCustomer(@Param("customerId") UUID customerId);
}
