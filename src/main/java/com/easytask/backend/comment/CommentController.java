package com.easytask.backend.comment;

import com.easytask.backend.auth.AuthenticatedUser;
import com.easytask.backend.common.ItemsResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class CommentController {

    private final CommentService commentService;

    @GetMapping("/tasks/{taskId}/comments")
    public ItemsResponse<CommentResponse> list(@AuthenticationPrincipal AuthenticatedUser principal,
                                               @PathVariable UUID taskId) {
        return commentService.list(principal, taskId);
    }

    @PostMapping("/tasks/{taskId}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    public CommentResponse create(@AuthenticationPrincipal AuthenticatedUser principal,
                                  @PathVariable UUID taskId,
                                  @Valid @RequestBody CommentTextRequest request) {
        return commentService.create(principal, taskId, request);
    }

    @PatchMapping("/comments/{commentId}")
    public CommentResponse update(@AuthenticationPrincipal AuthenticatedUser principal,
                                  @PathVariable UUID commentId,
                                  @Valid @RequestBody CommentTextRequest request) {
        return commentService.update(principal, commentId, request);
    }

    @DeleteMapping("/comments/{commentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthenticatedUser principal,
                       @PathVariable UUID commentId) {
        commentService.delete(principal, commentId);
    }
}
