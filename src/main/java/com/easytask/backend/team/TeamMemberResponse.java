package com.easytask.backend.team;

import com.easytask.backend.user.AppUser;

import java.util.UUID;

/** Contract member shape: the user's fields, id = user id. */
public record TeamMemberResponse(
        UUID id,
        String fullName,
        String email,
        String role
) {

    public static TeamMemberResponse from(AppUser user) {
        return new TeamMemberResponse(user.getId(), user.getFullName(), user.getEmail(), user.getRole().getName());
    }
}
