package com.radhikafarms.milkweb.product;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ProductResponse(
        UUID id,
        String name,
        String description,
        ProductUnit unit,
        BigDecimal price,
        ProductStatus status,
        Instant createdAt,
        Instant updatedAt
) {
}
