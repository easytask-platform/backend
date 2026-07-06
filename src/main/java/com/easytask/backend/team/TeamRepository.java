package com.easytask.backend.team;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface TeamRepository extends JpaRepository<Team, UUID> {

    @Query("""
            select t from Team t
            where t.organization.id = :organizationId
              and lower(t.name) like lower(concat('%', :search, '%'))
              and exists (select 1 from TeamMember m where m.team = t and m.user.id = :userId)
            """)
    Page<Team> findAllVisibleToMember(@Param("organizationId") UUID organizationId,
                                      @Param("userId") UUID userId,
                                      @Param("search") String search,
                                      Pageable pageable);

    Optional<Team> findByIdAndOrganizationId(UUID id, UUID organizationId);

    boolean existsByOrganizationIdAndNameIgnoreCase(UUID organizationId, String name);

    Page<Team> findAllByOrganizationId(UUID organizationId, Pageable pageable);

    Page<Team> findAllByOrganizationIdAndNameContainingIgnoreCase(UUID organizationId, String search,
                                                                  Pageable pageable);

    long countByOrganizationId(UUID organizationId);
}
