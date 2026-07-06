package com.easytask.backend.team;

import com.easytask.backend.common.MembershipRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface TeamMemberRepository extends JpaRepository<TeamMember, UUID> {

    List<TeamMember> findAllByTeamId(UUID teamId);

    @Query("""
            select m.user.id from TeamMember m
            where m.team.id in (
                select mgr.team.id from TeamMember mgr
                where mgr.user.id = :managerId and mgr.teamRole = :role)
            """)
    Set<UUID> findUserIdsInTeamsWhereUserHasRole(@Param("managerId") UUID managerId,
                                                 @Param("role") MembershipRole role);

    @Query("select m.team.id, count(m) from TeamMember m where m.team.id in :teamIds group by m.team.id")
    List<Object[]> countMembersByTeamIds(@Param("teamIds") Collection<UUID> teamIds);

    List<TeamMember> findAllByUserId(UUID userId);

    Optional<TeamMember> findByTeamIdAndUserId(UUID teamId, UUID userId);

    boolean existsByTeamIdAndUserId(UUID teamId, UUID userId);

    long countByTeamId(UUID teamId);
}
