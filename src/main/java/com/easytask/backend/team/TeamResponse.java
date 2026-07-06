package com.easytask.backend.team;

import java.util.UUID;

public record TeamResponse(
        UUID id,
        String name,
        String description,
        long memberCount
) {

    public static TeamResponse from(Team team, long memberCount) {
        return new TeamResponse(team.getId(), team.getName(), team.getDescription(), memberCount);
    }
}
