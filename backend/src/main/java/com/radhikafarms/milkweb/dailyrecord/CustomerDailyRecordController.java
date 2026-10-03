package com.radhikafarms.milkweb.dailyrecord;

import com.radhikafarms.milkweb.auth.MilkWebUserDetails;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@RestController
@Validated
public class CustomerDailyRecordController {

    private static final int MAX_PAGE_SIZE = 100;
    private static final long MAX_RANGE_DAYS = 366;

    private final DailyRecordService dailyRecordService;

    public CustomerDailyRecordController(DailyRecordService dailyRecordService) {
        this.dailyRecordService = dailyRecordService;
    }

    @GetMapping("/api/customer/daily-records")
    public CustomerDailyRecordPageResponse listCustomerDailyRecords(
            @AuthenticationPrincipal MilkWebUserDetails userDetails,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate recordDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) DailyRecordSlot slot,
            @RequestParam(required = false) DailyRecordStatus status,
            @RequestParam(required = false) UUID customerId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size
    ) {
        if (customerId != null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Customer identity is derived from the authenticated account");
        }
        validateFilters(recordDate, fromDate, toDate, page, size);
        PageRequest pageable = PageRequest.of(page, size, Sort.by(
                Sort.Order.desc("recordDate"),
                Sort.Order.asc("slot"),
                Sort.Order.asc("id")));
        Page<CustomerDailyRecordResponse> records = dailyRecordService.listCustomerDailyRecords(
                        userDetails.getUserId(), recordDate, fromDate, toDate, slot, status, pageable)
                .map(this::toResponse);
        return CustomerDailyRecordPageResponse.from(records);
    }

    private void validateFilters(
            LocalDate recordDate,
            LocalDate fromDate,
            LocalDate toDate,
            int page,
            int size
    ) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page or size is outside the allowed range");
        }
        if (recordDate != null && (fromDate != null || toDate != null)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Use recordDate or a date range, not both");
        }
        if ((fromDate == null) != (toDate == null)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Both fromDate and toDate are required for a date range");
        }
        if (fromDate != null && fromDate.isAfter(toDate)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "fromDate must be on or before toDate");
        }
        if (fromDate != null && ChronoUnit.DAYS.between(fromDate, toDate) >= MAX_RANGE_DAYS) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Date ranges cannot exceed 366 calendar days");
        }
    }

    private CustomerDailyRecordResponse toResponse(DailyRecord record) {
        return new CustomerDailyRecordResponse(
                record.getId(),
                record.getRecordDate(),
                record.getSlot(),
                record.getProduct().getName(),
                record.getProduct().getUnit(),
                record.getQuantity(),
                record.getUnitPriceSnapshot(),
                record.getAmount(),
                record.getStatus()
        );
    }
}
