package com.radhikafarms.milkweb.dailyrecord;

import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.time.LocalDate;
import java.util.UUID;

@RestController
@Validated
public class DailyRecordController {

    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 100;

    private final DailyRecordService dailyRecordService;

    public DailyRecordController(DailyRecordService dailyRecordService) {
        this.dailyRecordService = dailyRecordService;
    }

    @PostMapping("/api/owner/daily-records")
    public ResponseEntity<DailyRecordResponse> createDailyRecord(
            @Valid @RequestBody OwnerCreateDailyRecordRequest request
    ) {
        DailyRecordResponse response = toResponse(dailyRecordService.createDailyRecord(request));
        return ResponseEntity.created(URI.create("/api/owner/daily-records/" + response.id())).body(response);
    }

    @GetMapping("/api/owner/daily-records")
    public Page<DailyRecordResponse> listDailyRecords(
            @RequestParam(required = false) UUID customerId,
            @RequestParam(required = false) UUID productId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate recordDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) DailyRecordSlot slot,
            @RequestParam(required = false) DailyRecordStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size
    ) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page or size is outside the allowed range");
        }
        if (recordDate != null && (fromDate != null || toDate != null)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Use recordDate or a date range, not both");
        }
        if (fromDate != null && toDate != null && fromDate.isAfter(toDate)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "fromDate must be on or before toDate");
        }
        PageRequest pageable = PageRequest.of(page, size, Sort.by(
                Sort.Order.desc("recordDate"),
                Sort.Order.asc("slot"),
                Sort.Order.asc("id")));
        Page<DailyRecord> records = dailyRecordService.listDailyRecords(
                customerId, productId, recordDate, fromDate, toDate, slot, status, pageable);
        return records.map(this::toResponse);
    }

    @GetMapping("/api/owner/daily-records/{dailyRecordId}")
    public DailyRecordResponse getDailyRecord(@PathVariable UUID dailyRecordId) {
        return toResponse(dailyRecordService.getDailyRecordById(dailyRecordId));
    }

    @PutMapping("/api/owner/daily-records/{dailyRecordId}")
    public DailyRecordResponse updateDailyRecord(
            @PathVariable UUID dailyRecordId,
            @Valid @RequestBody OwnerUpdateDailyRecordRequest request
    ) {
        return toResponse(dailyRecordService.updateDailyRecord(dailyRecordId, request));
    }

    private DailyRecordResponse toResponse(DailyRecord record) {
        return new DailyRecordResponse(
                record.getId(),
                record.getCustomer().getId(),
                record.getProduct().getId(),
                record.getSubscription() == null ? null : record.getSubscription().getId(),
                record.getRecordDate(),
                record.getSlot(),
                record.getQuantity(),
                record.getUnitPriceSnapshot(),
                record.getAmount(),
                record.getStatus(),
                record.getCreatedAt(),
                record.getUpdatedAt()
        );
    }
}
