package com.easytask.backend.attachment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TaskAttachmentRepository extends JpaRepository<TaskAttachment, UUID> {

    List<TaskAttachment> findAllByTaskIdOrderByUploadedAtDesc(UUID taskId);
}
