package com.radhikafarms.milkweb.billing;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

@Component
public class BillingPeriodInitializer {

    private final BillingPeriodRepository billingPeriodRepository;

    public BillingPeriodInitializer(BillingPeriodRepository billingPeriodRepository) {
        this.billingPeriodRepository = billingPeriodRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void createIfMissing(LocalDate periodStart, LocalDate periodEnd) {
        if (!billingPeriodRepository.existsByPeriodStart(periodStart)) {
            billingPeriodRepository.saveAndFlush(new BillingPeriod(periodStart, periodEnd));
        }
    }
}
