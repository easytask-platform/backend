package com.easytask.backend.savedfilter;

import com.easytask.backend.auth.AuthenticatedUser;
import com.easytask.backend.common.ConflictException;
import com.easytask.backend.common.ItemsResponse;
import com.easytask.backend.common.NotFoundException;
import com.easytask.backend.common.ValidationException;
import com.easytask.backend.user.AppUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

/**
 * Per-user saved task filters (P4-8, D42). The filter payload is opaque JSON:
 * stored serialized, capped at 2000 chars, and parsed back to a JSON object on
 * read so responses nest it rather than returning an escaped string.
 */
@Service
@RequiredArgsConstructor
public class SavedFilterService {

    static final int MAX_FILTERS_PER_USER = 20;
    static final int MAX_SERIALIZED_LENGTH = 2000;

    private final SavedFilterRepository savedFilterRepository;
    private final AppUserRepository userRepository;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public ItemsResponse<SavedFilterResponse> list(AuthenticatedUser principal) {
        return new ItemsResponse<>(savedFilterRepository.findAllByUserIdOrderByCreatedAtDesc(principal.id()).stream()
                .map(this::toResponse)
                .toList());
    }

    @Transactional
    public SavedFilterResponse create(AuthenticatedUser principal, CreateSavedFilterRequest request) {
        String serialized = objectMapper.writeValueAsString(request.filters());
        if (serialized.length() > MAX_SERIALIZED_LENGTH) {
            throw new ValidationException("filters", "Filter payload is too large (max %d characters)"
                    .formatted(MAX_SERIALIZED_LENGTH));
        }
        if (savedFilterRepository.existsByUserIdAndName(principal.id(), request.name())) {
            throw new ConflictException("A saved filter with this name already exists");
        }
        if (savedFilterRepository.countByUserId(principal.id()) >= MAX_FILTERS_PER_USER) {
            throw new ConflictException("You can save at most %d filters".formatted(MAX_FILTERS_PER_USER));
        }
        // Flush so @CreationTimestamp is populated before we build the response
        // (the assigned UUID id lets Hibernate defer the INSERT otherwise).
        SavedFilter saved = savedFilterRepository.saveAndFlush(SavedFilter.builder()
                .user(userRepository.getReferenceById(principal.id()))
                .name(request.name())
                .filters(serialized)
                .build());
        return toResponse(saved);
    }

    @Transactional
    public void delete(AuthenticatedUser principal, UUID id) {
        SavedFilter saved = savedFilterRepository.findByIdAndUserId(id, principal.id())
                .orElseThrow(() -> new NotFoundException("Saved filter not found"));
        savedFilterRepository.delete(saved);
    }

    private SavedFilterResponse toResponse(SavedFilter saved) {
        return new SavedFilterResponse(saved.getId(), saved.getName(), parse(saved.getFilters()),
                saved.getCreatedAt());
    }

    private JsonNode parse(String serialized) {
        try {
            return objectMapper.readTree(serialized);
        } catch (JacksonException e) {
            // Stored value is always something we serialized; treat corruption as an empty object.
            return objectMapper.createObjectNode();
        }
    }
}
