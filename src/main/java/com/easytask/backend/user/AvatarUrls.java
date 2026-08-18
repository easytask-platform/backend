package com.easytask.backend.user;

/**
 * Builds the relative avatar URL exposed on every user summary payload (D30).
 * The {@code v} query param is the stored file name so clients cache-bust
 * automatically when the picture changes; the server ignores it.
 */
public final class AvatarUrls {

    private AvatarUrls() {
    }

    public static String of(AppUser user) {
        if (user.getAvatarFilename() == null) {
            return null;
        }
        return "/api/v1/users/%s/avatar?v=%s".formatted(user.getId(), user.getAvatarFilename());
    }
}
