package com.easytask.backend.savedfilter;

import com.easytask.backend.auth.AuthenticatedUser;
import com.easytask.backend.common.ItemsResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** P4-8 (D42): per-user saved task filters. No extra permission — every authenticated user manages their own. */
@RestController
@RequestMapping("/api/v1/me/saved-filters")
@RequiredArgsConstructor
public class SavedFilterController {

    private final SavedFilterService savedFilterService;

    @GetMapping
    public ItemsResponse<SavedFilterResponse> list(@AuthenticationPrincipal AuthenticatedUser principal) {
        return savedFilterService.list(principal);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SavedFilterResponse create(@AuthenticationPrincipal AuthenticatedUser principal,
                                      @Valid @RequestBody CreateSavedFilterRequest request) {
        return savedFilterService.create(principal, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthenticatedUser principal, @PathVariable UUID id) {
        savedFilterService.delete(principal, id);
    }
}
