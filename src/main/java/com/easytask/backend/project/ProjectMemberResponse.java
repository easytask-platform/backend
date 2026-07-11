package com.easytask.backend.project;

import com.easytask.backend.user.AppUser;

import java.util.UUID;

/** Contract member shape: the user's fields, id = user id. */
public record ProjectMemberResponse(
        UUID id,
        String fullName,
        String email,
        String role
) {

    public static ProjectMemberResponse from(AppUser user) {
        return new ProjectMemberResponse(user.getId(), user.getFullName(), user.getEmail(), user.getRole().getName());
    }
}
