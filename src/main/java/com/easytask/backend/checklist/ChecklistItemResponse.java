package com.easytask.backend.checklist;

import java.util.UUID;

public record ChecklistItemResponse(
        UUID id,
        UUID taskId,
        String title,
        boolean done,
        int position
) {

    public static ChecklistItemResponse from(TaskChecklistItem item) {
        return new ChecklistItemResponse(item.getId(), item.getTask().getId(), item.getTitle(),
                item.isDone(), item.getPosition());
    }
}
