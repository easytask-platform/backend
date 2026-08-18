package com.easytask.backend.user;

import com.easytask.backend.attachment.StorageService;
import com.easytask.backend.auth.AuthenticatedUser;
import com.easytask.backend.common.NotFoundException;
import com.easytask.backend.common.ValidationException;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;
import java.util.UUID;

/** Profile pictures (P4-1/D30): stored beside attachments under avatars/, org-scoped serving. */
@Service
@RequiredArgsConstructor
public class AvatarService {

    static final long MAX_AVATAR_BYTES = 2 * 1024 * 1024;

    /** Extension doubles as the content type on serving, so only well-known image types. */
    private static final Map<String, String> EXTENSION_BY_CONTENT_TYPE = Map.of(
            "image/png", "png",
            "image/jpeg", "jpg",
            "image/webp", "webp",
            "image/gif", "gif");

    private static final Map<String, String> CONTENT_TYPE_BY_EXTENSION = Map.of(
            "png", "image/png",
            "jpg", "image/jpeg",
            "webp", "image/webp",
            "gif", "image/gif");

    private final AppUserRepository userRepository;
    private final StorageService storageService;

    public record AvatarUrlResponse(String avatarUrl) {
    }

    public record AvatarImage(Resource resource, String contentType) {
    }

    @Transactional
    public AvatarUrlResponse upload(AuthenticatedUser principal, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ValidationException("file", "File is required and must not be empty");
        }
        String extension = EXTENSION_BY_CONTENT_TYPE.get(file.getContentType());
        if (extension == null) {
            throw new ValidationException("file", "Unsupported image type; allowed: PNG, JPEG, WebP, GIF");
        }
        if (file.getSize() > MAX_AVATAR_BYTES) {
            throw new ValidationException("file", "Avatar must be 2 MB or smaller");
        }
        AppUser user = userRepository.findById(principal.id())
                .orElseThrow(() -> new NotFoundException("User not found"));
        String previous = user.getAvatarFilename();
        user.setAvatarFilename("avatars/" + UUID.randomUUID() + "." + extension);
        storageService.store(file, user.getAvatarFilename());
        if (previous != null) {
            storageService.delete(previous);
        }
        return new AvatarUrlResponse(AvatarUrls.of(user));
    }

    @Transactional
    public void remove(AuthenticatedUser principal) {
        AppUser user = userRepository.findById(principal.id())
                .orElseThrow(() -> new NotFoundException("User not found"));
        String previous = user.getAvatarFilename();
        user.setAvatarFilename(null);
        if (previous != null) {
            storageService.delete(previous);
        }
    }

    /** Any authenticated org member may view (avatars render in comments/activity); cross-org = 404. */
    @Transactional(readOnly = true)
    public AvatarImage serve(AuthenticatedUser principal, UUID userId) {
        AppUser user = userRepository.findById(userId)
                .filter(u -> u.getOrganization().getId().equals(principal.organizationId()))
                .orElseThrow(() -> new NotFoundException("User not found"));
        String filename = user.getAvatarFilename();
        if (filename == null) {
            throw new NotFoundException("User has no avatar");
        }
        Resource resource = storageService.load(filename);
        if (!resource.exists()) {
            throw new NotFoundException("Avatar file missing");
        }
        String extension = filename.substring(filename.lastIndexOf('.') + 1);
        return new AvatarImage(resource, CONTENT_TYPE_BY_EXTENSION.get(extension));
    }
}
