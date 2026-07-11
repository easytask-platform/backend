package com.easytask.backend.role;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RoleRepository extends JpaRepository<Role, UUID> {

    List<Role> findByOrganizationIdOrderByNameAsc(UUID organizationId);

    Optional<Role> findByIdAndOrganizationId(UUID id, UUID organizationId);

    Optional<Role> findByOrganizationIdAndName(UUID organizationId, String name);

    boolean existsByOrganizationIdAndNameIgnoreCase(UUID organizationId, String name);
}
