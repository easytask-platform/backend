package com.easytask.backend.savedfilter;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code filters} is nested back as a JSON object (parsed from the stored text),
 * never returned as an escaped string.
 */
public record SavedFilterResponse(
        UUID id,
        String name,
        JsonNode filters,
        Instant createdAt
) {
}
