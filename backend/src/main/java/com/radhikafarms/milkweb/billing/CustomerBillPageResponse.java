package com.radhikafarms.milkweb.billing;

import org.springframework.data.domain.Page;

import java.util.List;

public record CustomerBillPageResponse(
        List<CustomerBillResponse> content,
        int number,
        int size,
        long totalElements,
        int totalPages
) {
    public static CustomerBillPageResponse from(Page<CustomerBillResponse> page) {
        return new CustomerBillPageResponse(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages()
        );
    }
}
