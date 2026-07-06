package com.easytask.backend.attachment;

import com.easytask.backend.activity.ActivityEventType;
import com.easytask.backend.activity.ActivityService;
import com.easytask.backend.auth.AuthenticatedUser;
import com.easytask.backend.common.ConflictException;
import com.easytask.backend.common.ForbiddenException;
import com.easytask.backend.common.ItemsResponse;
import com.easytask.backend.common.NotFoundException;
import com.easytask.backend.common.ValidationException;
import com.easytask.backend.notification.NotificationService;
import com.easytask.backend.notification.NotificationType;
import com.easytask.backend.task.Task;
import com.easytask.backend.task.TaskAccessService;
import com.easytask.backend.task.TaskStatus;
import com.easytask.backend.user.AppUser;
import com.easytask.backend.user.AppUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AttachmentService {

    /** Contract: images, PDF, Word, Excel, text, ZIP. */
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/zip",
            "application/x-zip-compressed");

    private final TaskAttachmentRepository attachmentRepository;
    private final TaskAccessService taskAccessService;
    private final AppUserRepository userRepository;
    private final StorageService storageService;
    private final ActivityService activityService;
    private final NotificationService notificationService;

    @Transactional(readOnly = true)
    public ItemsResponse<AttachmentResponse> list(AuthenticatedUser principal, UUID taskId) {
        taskAccessService.getVisibleTask(principal, taskId);
        return new ItemsResponse<>(attachmentRepository.findAllByTaskIdOrderByUploadedAtDesc(taskId).stream()
                .map(AttachmentResponse::from)
                .toList());
    }

    @Transactional
    public AttachmentResponse upload(AuthenticatedUser principal, UUID taskId, MultipartFile file) {
        Task task = taskAccessService.getVisibleTask(principal, taskId);
        validateFile(file);

        AppUser uploader = userRepository.getReferenceById(principal.id());
        TaskAttachment attachment = attachmentRepository.save(TaskAttachment.builder()
                .task(task)
                .uploader(uploader)
                .originalFilename(sanitizeFilename(file.getOriginalFilename()))
                .storedFilename("pending")
                .contentType(file.getContentType())
                .fileSizeBytes(file.getSize())
                .build());
        attachment.setStoredFilename(attachment.getId().toString());
        storageService.store(file, attachment.getStoredFilename());

        activityService.log(task, uploader, ActivityEventType.ATTACHMENT_UPLOADED, null,
                attachment.getOriginalFilename());
        notificationService.notifyAllExceptActor(taskAccessService.interestedUsers(task), principal.id(), task,
                NotificationType.ATTACHMENT_ADDED,
                "New attachment on task '%s'".formatted(task.getTitle()));
        return AttachmentResponse.from(attachment);
    }

    public record Download(Resource resource, String originalFilename, String contentType) {
    }

    @Transactional(readOnly = true)
    public Download download(AuthenticatedUser principal, UUID attachmentId) {
        TaskAttachment attachment = requireVisibleAttachment(principal, attachmentId);
        Resource resource = storageService.load(attachment.getStoredFilename());
        if (!resource.exists()) {
            throw new NotFoundException("Attachment file missing");
        }
        return new Download(resource, attachment.getOriginalFilename(), attachment.getContentType());
    }

    @Transactional
    public void delete(AuthenticatedUser principal, UUID attachmentId) {
        TaskAttachment attachment = requireVisibleAttachment(principal, attachmentId);
        if (!attachment.getUploader().getId().equals(principal.id())) {
            throw new ForbiddenException("Only the uploader can delete an attachment");
        }
        if (attachment.getTask().getStatus() == TaskStatus.APPROVED) {
            throw new ConflictException("Attachments cannot be deleted after the task is approved");
        }
        attachmentRepository.delete(attachment);
        storageService.delete(attachment.getStoredFilename());
    }

    private TaskAttachment requireVisibleAttachment(AuthenticatedUser principal, UUID attachmentId) {
        TaskAttachment attachment = attachmentRepository.findById(attachmentId)
                .orElseThrow(() -> new NotFoundException("Attachment not found"));
        taskAccessService.getVisibleTask(principal, attachment.getTask().getId());
        return attachment;
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ValidationException("file", "File is required and must not be empty");
        }
        String contentType = file.getContentType();
        boolean allowed = contentType != null
                && (contentType.startsWith("image/") || contentType.startsWith("text/")
                        || ALLOWED_CONTENT_TYPES.contains(contentType));
        if (!allowed) {
            throw new ValidationException("file",
                    "Unsupported file type; allowed: images, PDF, Word, Excel, text, ZIP");
        }
    }

    private static String sanitizeFilename(String filename) {
        String name = filename == null ? "file" : filename;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        return name.isBlank() ? "file" : (name.length() > 255 ? name.substring(name.length() - 255) : name);
    }
}
