package com.easytask.backend.user;

import com.easytask.backend.auth.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class AvatarController {

    private final AvatarService avatarService;

    @PutMapping(value = "/me/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AvatarService.AvatarUrlResponse upload(@AuthenticationPrincipal AuthenticatedUser principal,
                                                  @RequestParam("file") MultipartFile file) {
        return avatarService.upload(principal, file);
    }

    @DeleteMapping("/me/avatar")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@AuthenticationPrincipal AuthenticatedUser principal) {
        avatarService.remove(principal);
    }

    /** No permission gate: avatars render for every org member (comments, activity). */
    @GetMapping("/users/{userId}/avatar")
    public ResponseEntity<Resource> serve(@AuthenticationPrincipal AuthenticatedUser principal,
                                          @PathVariable UUID userId) {
        AvatarService.AvatarImage image = avatarService.serve(principal, userId);
        // The v= query param changes on upload, so long-lived caching is safe.
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(image.contentType()))
                .cacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePrivate())
                .body(image.resource());
    }
}
