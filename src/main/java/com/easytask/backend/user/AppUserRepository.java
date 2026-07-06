package com.easytask.backend.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface AppUserRepository extends JpaRepository<AppUser, UUID>, JpaSpecificationExecutor<AppUser> {

    Optional<AppUser> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);

    Optional<AppUser> findByIdAndOrganizationId(UUID id, UUID organizationId);

    List<AppUser> findAllByIdInAndOrganizationId(Set<UUID> ids, UUID organizationId);

    long countByOrganizationId(UUID organizationId);

    long countByOrganizationIdAndActiveTrue(UUID organizationId);
}
