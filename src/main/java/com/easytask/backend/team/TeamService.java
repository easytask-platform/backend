package com.easytask.backend.team;

import com.easytask.backend.auth.AuthenticatedUser;
import com.easytask.backend.common.ConflictException;
import com.easytask.backend.role.DataScope;
import com.easytask.backend.common.ItemsResponse;
import com.easytask.backend.common.MembershipRole;
import com.easytask.backend.common.NotFoundException;
import com.easytask.backend.common.PageResponse;
import com.easytask.backend.organization.OrganizationRepository;
import com.easytask.backend.user.AppUser;
import com.easytask.backend.user.AppUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TeamService {

    private final TeamRepository teamRepository;
    private final TeamMemberRepository teamMemberRepository;
    private final AppUserRepository userRepository;
    private final OrganizationRepository organizationRepository;

    @Transactional(readOnly = true)
    public PageResponse<TeamResponse> list(AuthenticatedUser principal, String search, Pageable pageable) {
        String effectiveSearch = search == null ? "" : search;
        Page<Team> page = principal.scope() == DataScope.ORGANIZATION
                ? teamRepository.findAllByOrganizationIdAndNameContainingIgnoreCase(
                        principal.organizationId(), effectiveSearch, pageable)
                : teamRepository.findAllVisibleToMember(principal.organizationId(), principal.id(),
                        effectiveSearch, pageable);
        Map<UUID, Long> counts = memberCounts(page.getContent());
        return PageResponse.from(page.map(team -> TeamResponse.from(team, counts.getOrDefault(team.getId(), 0L))));
    }

    @Transactional
    public TeamResponse create(AuthenticatedUser principal, CreateTeamRequest request) {
        if (teamRepository.existsByOrganizationIdAndNameIgnoreCase(principal.organizationId(), request.name())) {
            throw new ConflictException("Team name is already used");
        }
        Team team = teamRepository.save(Team.builder()
                .organization(organizationRepository.getReferenceById(principal.organizationId()))
                .name(request.name())
                .description(request.description())
                .build());
        return TeamResponse.from(team, 0);
    }

    @Transactional
    public TeamResponse update(AuthenticatedUser principal, UUID teamId, UpdateTeamRequest request) {
        Team team = requireTeam(principal, teamId);
        if (request.name() != null && !request.name().equalsIgnoreCase(team.getName())
                && teamRepository.existsByOrganizationIdAndNameIgnoreCase(principal.organizationId(), request.name())) {
            throw new ConflictException("Team name is already used");
        }
        if (request.name() != null) {
            team.setName(request.name());
        }
        if (request.description() != null) {
            team.setDescription(request.description());
        }
        return TeamResponse.from(team, teamMemberRepository.countByTeamId(teamId));
    }

    @Transactional(readOnly = true)
    public ItemsResponse<TeamMemberResponse> members(AuthenticatedUser principal, UUID teamId) {
        Team team = requireTeam(principal, teamId);
        if (principal.scope() != DataScope.ORGANIZATION
                && !teamMemberRepository.existsByTeamIdAndUserId(team.getId(), principal.id())) {
            throw new NotFoundException("Team not found");
        }
        List<TeamMemberResponse> items = teamMemberRepository.findAllByTeamId(teamId).stream()
                .map(member -> TeamMemberResponse.from(member.getUser()))
                .toList();
        return new ItemsResponse<>(items);
    }

    @Transactional
    public TeamMemberResponse addMember(AuthenticatedUser principal, UUID teamId, AddTeamMemberRequest request) {
        Team team = requireTeam(principal, teamId);
        AppUser user = userRepository.findByIdAndOrganizationId(request.userId(), principal.organizationId())
                .orElseThrow(() -> new NotFoundException("User not found"));
        if (teamMemberRepository.existsByTeamIdAndUserId(team.getId(), user.getId())) {
            throw new ConflictException("User is already a member of this team");
        }
        // org-level managers manage the teams they join; everyone else is a plain member
        MembershipRole teamRole = user.getRole().getDataScope() == DataScope.MANAGED
                ? MembershipRole.MANAGER : MembershipRole.MEMBER;
        teamMemberRepository.save(TeamMember.builder()
                .team(team)
                .user(user)
                .teamRole(teamRole)
                .build());
        return TeamMemberResponse.from(user);
    }

    @Transactional
    public void removeMember(AuthenticatedUser principal, UUID teamId, UUID userId) {
        Team team = requireTeam(principal, teamId);
        TeamMember member = teamMemberRepository.findByTeamIdAndUserId(team.getId(), userId)
                .orElseThrow(() -> new NotFoundException("Team member not found"));
        teamMemberRepository.delete(member);
    }

    private Team requireTeam(AuthenticatedUser principal, UUID teamId) {
        return teamRepository.findByIdAndOrganizationId(teamId, principal.organizationId())
                .orElseThrow(() -> new NotFoundException("Team not found"));
    }

    private Map<UUID, Long> memberCounts(List<Team> teams) {
        if (teams.isEmpty()) {
            return Map.of();
        }
        List<UUID> ids = teams.stream().map(Team::getId).toList();
        Map<UUID, Long> counts = new HashMap<>();
        for (Object[] row : teamMemberRepository.countMembersByTeamIds(ids)) {
            counts.put((UUID) row[0], (Long) row[1]);
        }
        return counts;
    }
}
