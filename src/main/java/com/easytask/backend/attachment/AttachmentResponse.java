package com.easytask.backend.attachment;

import com.easytask.backend.user.AvatarUrls;

import java.time.Instant;
import java.util.UUID;

public record AttachmentResponse(
        UUID id,
        UUID taskId,
        String originalFilename,
        String contentType,
        long fileSize,
        Uploader uploader,
        Instant uploadedAt
) {

    public record Uploader(UUID id, String fullName, String avatarUrl) {
    }

    public static AttachmentResponse from(TaskAttachment attachment) {
        return new AttachmentResponse(attachment.getId(), attachment.getTask().getId(),
                attachment.getOriginalFilename(), attachment.getContentType(), attachment.getFileSizeBytes(),
                new Uploader(attachment.getUploader().getId(), attachment.getUploader().getFullName(),
                        AvatarUrls.of(attachment.getUploader())),
                attachment.getUploadedAt());
    }
}
