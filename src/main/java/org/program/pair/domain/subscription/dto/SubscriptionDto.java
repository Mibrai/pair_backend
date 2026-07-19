package org.program.pair.domain.subscription.dto;

import java.time.Instant;
import java.util.UUID;

public record SubscriptionDto(
    UUID id,
    String type,
    UUID targetAuthorId,
    String targetAuthorName,
    UUID targetUserActivityId,
    String targetActivityName,
    UUID targetCategoryId,
    String targetCategoryName,
    Instant createdAt
) {}
