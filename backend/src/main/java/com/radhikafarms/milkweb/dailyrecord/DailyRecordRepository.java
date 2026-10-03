package com.radhikafarms.milkweb.dailyrecord;

import com.radhikafarms.milkweb.billing.DailyRecordPeriodSummary;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface DailyRecordRepository extends JpaRepository<DailyRecord, UUID> {

    @Query("""
            select d from DailyRecord d
            join fetch d.product p
            where d.customer.id = :customerId
              and d.recordDate >= :periodStart
              and d.recordDate <= :periodEnd
              and d.status in (
                com.radhikafarms.milkweb.dailyrecord.DailyRecordStatus.COLLECTED,
                com.radhikafarms.milkweb.dailyrecord.DailyRecordStatus.EXTRA)
            order by d.recordDate, d.slot, d.id
            """)
    List<DailyRecord> findChargeableRecords(
            @Param("customerId") UUID customerId,
            @Param("periodStart") LocalDate periodStart,
            @Param("periodEnd") LocalDate periodEnd
    );

    @Query("""
            select new com.radhikafarms.milkweb.billing.DailyRecordPeriodSummary(
                count(d), coalesce(sum(d.amount), 0))
            from DailyRecord d
            where d.recordDate >= :periodStart
              and d.recordDate <= :periodEnd
              and d.status in (
                com.radhikafarms.milkweb.dailyrecord.DailyRecordStatus.COLLECTED,
                com.radhikafarms.milkweb.dailyrecord.DailyRecordStatus.EXTRA)
            """)
    DailyRecordPeriodSummary summarizeChargeableRecords(
            @Param("periodStart") LocalDate periodStart,
            @Param("periodEnd") LocalDate periodEnd
    );

    boolean existsByCustomer_IdAndProduct_IdAndRecordDateAndSlot(
            UUID customerId, UUID productId, LocalDate recordDate, DailyRecordSlot slot);

    @Query("""
            select d from DailyRecord d
            where (:customerId is null or d.customer.id = :customerId)
              and (:productId is null or d.product.id = :productId)
              and (:recordDate is null or d.recordDate = :recordDate)
              and (:fromDate is null or d.recordDate >= :fromDate)
              and (:toDate is null or d.recordDate <= :toDate)
              and (:slot is null or d.slot = :slot)
              and (:status is null or d.status = :status)
            """)
    Page<DailyRecord> findFiltered(
            @Param("customerId") UUID customerId,
            @Param("productId") UUID productId,
            @Param("recordDate") LocalDate recordDate,
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate") LocalDate toDate,
            @Param("slot") DailyRecordSlot slot,
            @Param("status") DailyRecordStatus status,
            Pageable pageable
    );

    @EntityGraph(attributePaths = "product")
    @Query("""
            select d from DailyRecord d
            where d.customer.id = :customerId
              and (:recordDate is null or d.recordDate = :recordDate)
              and (:fromDate is null or d.recordDate >= :fromDate)
              and (:toDate is null or d.recordDate <= :toDate)
              and (:slot is null or d.slot = :slot)
              and (:status is null or d.status = :status)
            """)
    Page<DailyRecord> findCustomerRecords(
            @Param("customerId") UUID customerId,
            @Param("recordDate") LocalDate recordDate,
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate") LocalDate toDate,
            @Param("slot") DailyRecordSlot slot,
            @Param("status") DailyRecordStatus status,
            Pageable pageable
    );
}
