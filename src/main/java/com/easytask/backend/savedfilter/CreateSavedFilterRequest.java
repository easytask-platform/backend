package com.easytask.backend.savedfilter;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

/**
 * {@code filters} is an arbitrary JSON object (the client's task-filter payload);
 * the server serializes it verbatim and caps the serialized size at 2000 chars.
 */
public record CreateSavedFilterRequest(
        @NotBlank @Size(max = 50) String name,
        @NotNull JsonNode filters
) {
}
