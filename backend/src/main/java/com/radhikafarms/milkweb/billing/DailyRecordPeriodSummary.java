package com.radhikafarms.milkweb.billing;

import java.math.BigDecimal;

public record DailyRecordPeriodSummary(long recordCount, BigDecimal amount) {
}
