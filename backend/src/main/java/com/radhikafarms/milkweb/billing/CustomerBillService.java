package com.radhikafarms.milkweb.billing;

import com.radhikafarms.milkweb.customer.Customer;
import com.radhikafarms.milkweb.customer.CustomerRepository;
import com.radhikafarms.milkweb.customer.exception.CustomerNotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class CustomerBillService {

    private final BillRepository billRepository;
    private final CustomerRepository customerRepository;

    public CustomerBillService(BillRepository billRepository, CustomerRepository customerRepository) {
        this.billRepository = billRepository;
        this.customerRepository = customerRepository;
    }

    public CustomerBillPageResponse listIssuedBills(UUID userId, Pageable pageable) {
        Customer customer = customerRepository.findByUserId(userId)
                .orElseThrow(() -> new CustomerNotFoundException(userId));
        Page<Bill> billPage = billRepository.findCustomerVisibleBills(customer.getId(), pageable);
        if (billPage.isEmpty()) {
            return CustomerBillPageResponse.from(billPage.map(bill -> toResponse(bill, List.of())));
        }

        List<UUID> billIds = billPage.getContent().stream().map(Bill::getId).toList();
        Map<UUID, Bill> billsWithLines = billRepository
                .findCustomerVisibleBillsWithLines(customer.getId(), billIds)
                .stream()
                .collect(Collectors.toMap(Bill::getId, Function.identity()));

        Page<CustomerBillResponse> responsePage = billPage.map(bill -> {
            Bill billWithLines = billsWithLines.get(bill.getId());
            if (billWithLines == null) {
                throw new IllegalStateException("Issued bill disappeared while loading its line items");
            }
            return toResponse(billWithLines, billWithLines.getLines().stream()
                    .map(line -> new CustomerBillLineResponse(
                            line.getRecordDate(),
                            line.getSlot(),
                            line.getDescription(),
                            line.getQuantity(),
                            line.getUnitPrice(),
                            line.getAmount()))
                    .toList());
        });
        return CustomerBillPageResponse.from(responsePage);
    }

    private CustomerBillResponse toResponse(Bill bill, List<CustomerBillLineResponse> lines) {
        return new CustomerBillResponse(
                bill.getId(),
                YearMonth.from(bill.getPeriodStart()).toString(),
                bill.getPeriodStart(),
                bill.getPeriodEnd(),
                bill.getTotalAmount(),
                bill.getStatus(),
                bill.getIssuedAt(),
                lines
        );
    }
}
