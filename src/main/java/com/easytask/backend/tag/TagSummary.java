package com.easytask.backend.tag;

import java.util.UUID;

/** Compact tag shape embedded in task list items and task details. */
public record TagSummary(UUID id, String name, String color) {

    public static TagSummary from(Tag tag) {
        return new TagSummary(tag.getId(), tag.getName(), tag.getColor());
    }
}
