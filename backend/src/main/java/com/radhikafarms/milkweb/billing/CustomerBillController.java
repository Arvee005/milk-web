package com.radhikafarms.milkweb.billing;

import com.radhikafarms.milkweb.auth.MilkWebUserDetails;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@Validated
public class CustomerBillController {

    private static final int MAX_PAGE_SIZE = 100;

    private final CustomerBillService customerBillService;

    public CustomerBillController(CustomerBillService customerBillService) {
        this.customerBillService = customerBillService;
    }

    @GetMapping("/api/customer/bills")
    public CustomerBillPageResponse listCustomerBills(
            @AuthenticationPrincipal MilkWebUserDetails userDetails,
            @RequestParam(required = false) String customerId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        if (customerId != null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Customer identity is derived from the authenticated account");
        }
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page or size is outside the allowed range");
        }

        return customerBillService.listIssuedBills(
                userDetails.getUserId(),
                PageRequest.of(page, size, Sort.by(
                        Sort.Order.desc("createdAt"),
                        Sort.Order.asc("id"))));
    }
}
