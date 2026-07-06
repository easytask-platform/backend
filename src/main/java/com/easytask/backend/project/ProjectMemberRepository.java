package com.easytask.backend.project;

import com.easytask.backend.common.MembershipRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface ProjectMemberRepository extends JpaRepository<ProjectMember, UUID> {

    List<ProjectMember> findAllByProjectId(UUID projectId);

    @Query("""
            select m.user.id from ProjectMember m
            where m.project.id in (
                select mgr.project.id from ProjectMember mgr
                where mgr.user.id = :managerId and mgr.projectRole = :role)
            """)
    Set<UUID> findUserIdsInProjectsWhereUserHasRole(@Param("managerId") UUID managerId,
                                                    @Param("role") MembershipRole role);

    List<ProjectMember> findAllByUserId(UUID userId);

    Optional<ProjectMember> findByProjectIdAndUserId(UUID projectId, UUID userId);

    boolean existsByProjectIdAndUserId(UUID projectId, UUID userId);

    boolean existsByProjectIdAndUserIdAndProjectRole(UUID projectId, UUID userId, MembershipRole projectRole);

    long countByProjectId(UUID projectId);

    @Query("select m.project.id, count(m) from ProjectMember m where m.project.id in :projectIds group by m.project.id")
    List<Object[]> countMembersByProjectIds(@Param("projectIds") java.util.Collection<UUID> projectIds);

    @Query("select m.project.id from ProjectMember m where m.user.id = :userId and m.projectRole = :role")
    List<UUID> findProjectIdsByUserAndRole(@Param("userId") UUID userId, @Param("role") MembershipRole role);
}
