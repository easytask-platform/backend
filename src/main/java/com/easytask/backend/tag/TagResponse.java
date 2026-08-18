package com.easytask.backend.tag;

import java.util.UUID;

public record TagResponse(
        UUID id,
        UUID projectId,
        String name,
        String color
) {

    public static TagResponse from(Tag tag) {
        return new TagResponse(tag.getId(), tag.getProject().getId(), tag.getName(), tag.getColor());
    }
}
