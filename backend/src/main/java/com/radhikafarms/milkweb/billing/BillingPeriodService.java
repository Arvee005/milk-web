package com.radhikafarms.milkweb.billing;

import com.radhikafarms.milkweb.billing.exception.BillingPeriodConflictException;
import com.radhikafarms.milkweb.billing.exception.PeriodClosedException;
import com.radhikafarms.milkweb.dailyrecord.DailyRecordRepository;
import com.radhikafarms.milkweb.user.User;
import com.radhikafarms.milkweb.user.UserRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class BillingPeriodService {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);
    private static final String PERIOD_UNIQUE_CONSTRAINT = "uk_billing_periods_period_start";

    private final BillingPeriodRepository billingPeriodRepository;
    private final BillingPeriodInitializer billingPeriodInitializer;
    private final DailyRecordRepository dailyRecordRepository;
    private final UserRepository userRepository;
    private final Clock businessClock;

    public BillingPeriodService(
            BillingPeriodRepository billingPeriodRepository,
            BillingPeriodInitializer billingPeriodInitializer,
            DailyRecordRepository dailyRecordRepository,
            UserRepository userRepository,
            Clock businessClock
    ) {
        this.billingPeriodRepository = billingPeriodRepository;
        this.billingPeriodInitializer = billingPeriodInitializer;
        this.dailyRecordRepository = dailyRecordRepository;
        this.userRepository = userRepository;
        this.businessClock = businessClock;
    }

    public BillingPeriodResponse getPeriod(String periodIdentifier) {
        YearMonth month = parsePeriod(periodIdentifier);
        LocalDate periodStart = month.atDay(1);
        LocalDate periodEnd = month.atEndOfMonth();
        Optional<BillingPeriod> persisted = billingPeriodRepository.findByPeriodStart(periodStart);
        DailyRecordPeriodSummary summary = getChargeableSummary(periodStart, periodEnd);
        return toResponse(
                periodIdentifier,
                persisted.orElse(null),
                periodStart,
                periodEnd,
                summary);
    }

    @Transactional
    public BillingPeriodResponse closePeriod(String periodIdentifier, UUID ownerUserId) {
        YearMonth month = parsePeriod(periodIdentifier);
        if (!month.isBefore(YearMonth.now(businessClock))) {
            throw new BillingPeriodConflictException("Only completed calendar months can be closed");
        }
        LocalDate periodStart = month.atDay(1);
        LocalDate periodEnd = month.atEndOfMonth();
        BillingPeriod period = getOrCreateAndLock(periodStart, periodEnd);
        DailyRecordPeriodSummary summary = getChargeableSummary(periodStart, periodEnd);

        if (period.getStatus() == BillingPeriodStatus.OPEN) {
            User owner = userRepository.getReferenceById(ownerUserId);
            period.close(owner, Instant.now(businessClock));
            billingPeriodRepository.saveAndFlush(period);
        }

        return toResponse(periodIdentifier, period, periodStart, periodEnd, summary);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void lockOpenPeriodForDailyRecord(LocalDate recordDate) {
        LocalDate periodStart = recordDate.withDayOfMonth(1);
        LocalDate periodEnd = recordDate.withDayOfMonth(recordDate.lengthOfMonth());
        BillingPeriod period = getOrCreateAndLock(periodStart, periodEnd);
        if (period.getStatus() == BillingPeriodStatus.CLOSED) {
            throw new PeriodClosedException(periodStart);
        }
    }

    private BillingPeriod getOrCreateAndLock(LocalDate periodStart, LocalDate periodEnd) {
        try {
            billingPeriodInitializer.createIfMissing(periodStart, periodEnd);
        } catch (DataIntegrityViolationException ex) {
            if (!isPeriodUniqueConstraintViolation(ex)) {
                throw ex;
            }
        }
        return billingPeriodRepository.findByPeriodStartForUpdate(periodStart)
                .orElseThrow(() -> new IllegalStateException(
                        "Billing period initialization completed without a persisted period"));
    }

    private DailyRecordPeriodSummary getChargeableSummary(LocalDate periodStart, LocalDate periodEnd) {
        return dailyRecordRepository.summarizeChargeableRecords(periodStart, periodEnd);
    }

    private BillingPeriodResponse toResponse(
            String periodIdentifier,
            BillingPeriod period,
            LocalDate periodStart,
            LocalDate periodEnd,
            DailyRecordPeriodSummary summary
    ) {
        return new BillingPeriodResponse(
                periodIdentifier,
                periodStart,
                periodEnd,
                period == null ? BillingPeriodStatus.OPEN : period.getStatus(),
                period == null ? null : period.getCreatedAt(),
                period == null ? null : period.getClosedAt(),
                period == null || period.getClosedBy() == null
                        ? null
                        : period.getClosedBy().getId(),
                summary.recordCount(),
                summary.amount() == null ? ZERO : summary.amount());
    }

    private YearMonth parsePeriod(String periodIdentifier) {
        if (periodIdentifier == null || !periodIdentifier.matches("[0-9]{4}-(0[1-9]|1[0-2])")) {
            throw new IllegalArgumentException("Period must use YYYY-MM format");
        }
        try {
            YearMonth month = YearMonth.parse(periodIdentifier);
            if (month.getYear() < 1) {
                throw new IllegalArgumentException("Period year must be greater than zero");
            }
            return month;
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("Period must be a valid calendar month", ex);
        }
    }

    private boolean isPeriodUniqueConstraintViolation(Throwable error) {
        Throwable cause = error;
        while (cause != null) {
            if (cause instanceof ConstraintViolationException violation
                    && isNamedPeriodConstraint(violation.getConstraintName())) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    private boolean isNamedPeriodConstraint(String constraintName) {
        if (constraintName == null) {
            return false;
        }
        String databaseName = constraintName.strip().toUpperCase(Locale.ROOT);
        int descriptionStart = databaseName.indexOf(' ');
        if (descriptionStart >= 0) {
            databaseName = databaseName.substring(0, descriptionStart);
        }
        int schemaSeparator = databaseName.lastIndexOf('.');
        if (schemaSeparator >= 0) {
            databaseName = databaseName.substring(schemaSeparator + 1);
        }
        return PERIOD_UNIQUE_CONSTRAINT.equalsIgnoreCase(databaseName);
    }
}
