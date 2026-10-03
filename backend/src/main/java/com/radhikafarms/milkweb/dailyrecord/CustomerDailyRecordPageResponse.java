package com.radhikafarms.milkweb.dailyrecord;

import org.springframework.data.domain.Page;

import java.util.List;

public record CustomerDailyRecordPageResponse(
        List<CustomerDailyRecordResponse> content,
        int number,
        int size,
        long totalElements,
        int totalPages
) {
    public static CustomerDailyRecordPageResponse from(Page<CustomerDailyRecordResponse> page) {
        return new CustomerDailyRecordPageResponse(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages()
        );
    }
}
