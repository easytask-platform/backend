package com.easytask.backend.tag;

import com.easytask.backend.auth.AuthenticatedUser;
import com.easytask.backend.common.ConflictException;
import com.easytask.backend.common.ItemsResponse;
import com.easytask.backend.common.NotFoundException;
import com.easytask.backend.project.Project;
import com.easytask.backend.project.ProjectAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TagService {

    private final TagRepository tagRepository;
    private final ProjectAccessService projectAccessService;

    @Transactional(readOnly = true)
    public ItemsResponse<TagResponse> list(AuthenticatedUser principal, UUID projectId) {
        projectAccessService.getVisibleProject(principal, projectId);
        return new ItemsResponse<>(tagRepository.findAllByProjectIdOrderByNameAsc(projectId).stream()
                .map(TagResponse::from)
                .toList());
    }

    @Transactional
    public TagResponse create(AuthenticatedUser principal, UUID projectId, CreateTagRequest request) {
        Project project = projectAccessService.getVisibleProject(principal, projectId);
        projectAccessService.requireManagementRights(principal, projectId);
        requireUniqueName(projectId, request.name());
        Tag tag = tagRepository.save(Tag.builder()
                .project(project)
                .name(request.name())
                .color(request.color())
                .build());
        return TagResponse.from(tag);
    }

    @Transactional
    public TagResponse update(AuthenticatedUser principal, UUID tagId, UpdateTagRequest request) {
        Tag tag = requireManagedTag(principal, tagId);
        if (request.name() != null && !request.name().equals(tag.getName())) {
            requireUniqueName(tag.getProject().getId(), request.name());
            tag.setName(request.name());
        }
        if (request.color() != null) {
            tag.setColor(request.color());
        }
        return TagResponse.from(tag);
    }

    @Transactional
    public void delete(AuthenticatedUser principal, UUID tagId) {
        // task_tags join rows cascade at the database level
        tagRepository.delete(requireManagedTag(principal, tagId));
    }

    private Tag requireManagedTag(AuthenticatedUser principal, UUID tagId) {
        Tag tag = tagRepository.findById(tagId)
                .orElseThrow(() -> new NotFoundException("Tag not found"));
        // out-of-scope project (other org / not visible) must read as 404, not as a permission hint
        projectAccessService.getVisibleProject(principal, tag.getProject().getId());
        projectAccessService.requireManagementRights(principal, tag.getProject().getId());
        return tag;
    }

    private void requireUniqueName(UUID projectId, String name) {
        if (tagRepository.existsByProjectIdAndName(projectId, name)) {
            throw new ConflictException("A tag with this name already exists in the project");
        }
    }
}
