package com.easytask.backend.savedfilter;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SavedFilterRepository extends JpaRepository<SavedFilter, UUID> {

    List<SavedFilter> findAllByUserIdOrderByCreatedAtDesc(UUID userId);

    boolean existsByUserIdAndName(UUID userId, String name);

    long countByUserId(UUID userId);

    Optional<SavedFilter> findByIdAndUserId(UUID id, UUID userId);
}
