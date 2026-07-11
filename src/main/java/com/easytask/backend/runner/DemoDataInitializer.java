package com.easytask.backend.runner;

import com.easytask.backend.attachment.AttachmentService;
import com.easytask.backend.auth.AuthService;
import com.easytask.backend.auth.AuthenticatedUser;
import com.easytask.backend.auth.RegisterOrganizationRequest;
import com.easytask.backend.comment.CommentResponse;
import com.easytask.backend.comment.CommentService;
import com.easytask.backend.comment.CommentTextRequest;
import com.easytask.backend.notification.NotificationService;
import com.easytask.backend.project.AddProjectMemberRequest;
import com.easytask.backend.project.CreateProjectRequest;
import com.easytask.backend.project.ProjectService;
import com.easytask.backend.project.ProjectStatus;
import com.easytask.backend.recurring.CreateRecurringTaskRuleRequest;
import com.easytask.backend.recurring.RecurrenceFrequency;
import com.easytask.backend.recurring.RecurringTaskGenerationService;
import com.easytask.backend.recurring.RecurringTaskRuleService;
import com.easytask.backend.role.CreateRoleRequest;
import com.easytask.backend.role.DataScope;
import com.easytask.backend.role.RoleService;
import com.easytask.backend.task.CreateTaskRequest;
import com.easytask.backend.task.TaskPriority;
import com.easytask.backend.task.TaskService;
import com.easytask.backend.task.TaskStatus;
import com.easytask.backend.task.UpdateTaskStatusRequest;
import com.easytask.backend.team.AddTeamMemberRequest;
import com.easytask.backend.team.CreateTeamRequest;
import com.easytask.backend.team.TeamService;
import com.easytask.backend.timeentry.TimeEntryRequest;
import com.easytask.backend.timeentry.TimeEntryService;
import com.easytask.backend.user.AppUser;
import com.easytask.backend.user.AppUserRepository;
import com.easytask.backend.user.CreateUserRequest;
import com.easytask.backend.user.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Builds a complete demo organization THROUGH THE SERVICE LAYER so activity
 * logs and notifications are generated exactly like real usage (D14).
 * Covers every entity and state the frontends can display: all task
 * statuses/priorities, overdue and un-dated tasks, comments (incl. edited),
 * attachments, time entries, a recurring rule with generated instances, a
 * custom role, and a deactivated user.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DemoDataInitializer {

    public static final String ADMIN_EMAIL = "admin@demo.easytask";
    public static final String PASSWORD = "password123";

    private final AuthService authService;
    private final UserService userService;
    private final RoleService roleService;
    private final TeamService teamService;
    private final ProjectService projectService;
    private final TaskService taskService;
    private final CommentService commentService;
    private final AttachmentService attachmentService;
    private final TimeEntryService timeEntryService;
    private final RecurringTaskRuleService recurringTaskRuleService;
    private final RecurringTaskGenerationService recurringTaskGenerationService;
    private final NotificationService notificationService;
    private final AppUserRepository userRepository;

    @Transactional
    public void initialize() {
        if (userRepository.existsByEmailIgnoreCase(ADMIN_EMAIL)) {
            log.info("DemoDataInitializer: demo organization already present — skipping");
            return;
        }
        LocalDate today = LocalDate.now();

        // --- Organization + people -------------------------------------
        authService.registerOrganization(new RegisterOrganizationRequest(
                "Demo Organization", "Aya Admin", ADMIN_EMAIL, PASSWORD));
        AuthenticatedUser admin = principal(ADMIN_EMAIL);

        var reviewerRole = roleService.create(admin, new CreateRoleRequest(
                "Reviewer", DataScope.MANAGED, Set.of("task:review", "user:read")));

        AuthenticatedUser sara = createUser(admin, "Sara Manager", "sara@demo.easytask", "MANAGER", null);
        AuthenticatedUser omar = createUser(admin, "Omar Manager", "omar@demo.easytask", "MANAGER", null);
        AuthenticatedUser ahmad = createUser(admin, "Ahmad Employee", "ahmad@demo.easytask", "EMPLOYEE", null);
        AuthenticatedUser lina = createUser(admin, "Lina Employee", "lina@demo.easytask", "EMPLOYEE", null);
        AuthenticatedUser khaled = createUser(admin, "Khaled Employee", "khaled@demo.easytask", "EMPLOYEE", null);
        AuthenticatedUser rami = createUser(admin, "Rami Reviewer", "rami@demo.easytask", null, reviewerRole.id());
        AuthenticatedUser nour = createUser(admin, "Nour Former", "nour@demo.easytask", "EMPLOYEE", null);
        userService.deactivate(admin, nour.id());

        // --- Teams ------------------------------------------------------
        var development = teamService.create(admin, new CreateTeamRequest(
                "Development", "Builds and ships the product"));
        for (AuthenticatedUser member : List.of(sara, ahmad, lina, rami)) {
            teamService.addMember(admin, development.id(), new AddTeamMemberRequest(member.id()));
        }
        var design = teamService.create(admin, new CreateTeamRequest(
                "Design", "Owns UX and visual language"));
        for (AuthenticatedUser member : List.of(omar, khaled)) {
            teamService.addMember(admin, design.id(), new AddTeamMemberRequest(member.id()));
        }

        // --- Projects (one per status; one overdue) ----------------------
        var website = projectService.create(sara, new CreateProjectRequest(
                "Website Redesign", "Public site refresh with the new brand",
                ProjectStatus.ACTIVE, today.minusDays(20), today.plusDays(25)));
        for (AuthenticatedUser member : List.of(ahmad, lina, rami)) {
            projectService.addMember(sara, website.id(), new AddProjectMemberRequest(member.id()));
        }

        var mobile = projectService.create(sara, new CreateProjectRequest(
                "Mobile App Launch", "Take the task app to the stores",
                ProjectStatus.PLANNED, today.plusDays(7), today.plusDays(60)));
        projectService.addMember(sara, mobile.id(), new AddProjectMemberRequest(ahmad.id()));
        projectService.addMember(sara, mobile.id(), new AddProjectMemberRequest(khaled.id()));

        var internalTools = projectService.create(omar, new CreateProjectRequest(
                "Internal Tools", "Small automations for the operations team",
                ProjectStatus.COMPLETED, today.minusDays(90), today.minusDays(10)));
        projectService.addMember(omar, internalTools.id(), new AddProjectMemberRequest(khaled.id()));

        var legacy = projectService.create(omar, new CreateProjectRequest(
                "Legacy Migration", "Abandoned attempt to port the old CRM",
                ProjectStatus.CANCELLED, today.minusDays(120), today.minusDays(30)));

        // --- Tasks: every status, priority, overdue, multi/unassigned ----
        // Website Redesign — the "busy" project.
        UUID heroTask = task(sara, website.id(), "Design new hero section",
                "Figma exploration for the landing hero", TaskPriority.HIGH,
                today.minusDays(15), today.plusDays(2), "6", List.of(ahmad.id(), lina.id()));
        UUID navTask = task(sara, website.id(), "Rebuild navigation component",
                "Accessible dropdown navigation", TaskPriority.MEDIUM,
                today.minusDays(12), today.plusDays(5), "8", List.of(ahmad.id()));
        UUID seoTask = task(sara, website.id(), "SEO audit fixes",
                "Apply the findings from the March audit", TaskPriority.LOW,
                today.minusDays(10), today.minusDays(1), "4", List.of(lina.id())); // overdue
        UUID copyTask = task(sara, website.id(), "Rewrite pricing page copy",
                "Tone-of-voice pass on pricing", TaskPriority.CRITICAL,
                today.minusDays(8), today.plusDays(1), "3", List.of(lina.id()));
        UUID blogTask = task(sara, website.id(), "Blog templates", null,
                TaskPriority.MEDIUM, today.minusDays(5), null, null, List.of(ahmad.id())); // no due date
        UUID backlogTask = task(sara, website.id(), "Collect testimonial quotes",
                "Waiting for marketing input", TaskPriority.LOW,
                null, today.plusDays(30), null, List.of()); // unassigned, TO_DO
        UUID droppedTask = task(sara, website.id(), "Dark mode exploration",
                "Out of scope for this quarter", TaskPriority.LOW,
                today.minusDays(9), today.plusDays(20), "5", List.of(ahmad.id()));

        // IN_PROGRESS
        status(ahmad, heroTask, TaskStatus.IN_PROGRESS, null);
        // IN_REVIEW
        status(ahmad, navTask, TaskStatus.IN_PROGRESS, null);
        status(ahmad, navTask, TaskStatus.IN_REVIEW, "Ready for a look");
        // REOPENED (with review note)
        status(lina, seoTask, TaskStatus.IN_PROGRESS, null);
        status(lina, seoTask, TaskStatus.IN_REVIEW, null);
        status(sara, seoTask, TaskStatus.REOPENED, "Meta descriptions still missing on 3 pages");
        // APPROVED end-to-end
        status(lina, copyTask, TaskStatus.IN_PROGRESS, null);
        status(lina, copyTask, TaskStatus.IN_REVIEW, "First full draft attached");
        status(sara, copyTask, TaskStatus.APPROVED, "Great work");
        // CANCELLED
        status(sara, droppedTask, TaskStatus.CANCELLED, "Deprioritized by product");

        // Mobile App Launch — planned work, mostly TO_DO.
        task(sara, mobile.id(), "Store listing assets", "Screenshots and copy",
                TaskPriority.MEDIUM, today.plusDays(7), today.plusDays(21), "6",
                List.of(khaled.id()));
        UUID betaTask = task(sara, mobile.id(), "Recruit beta testers",
                "20 external testers via the newsletter", TaskPriority.HIGH,
                today.plusDays(3), today.plusDays(14), "2", List.of(ahmad.id()));
        status(ahmad, betaTask, TaskStatus.IN_PROGRESS, null);

        // Internal Tools — finished project, everything approved.
        UUID reportTask = task(omar, internalTools.id(), "Weekly ops report script",
                "Python script + cron", TaskPriority.MEDIUM,
                today.minusDays(80), today.minusDays(40), "10", List.of(khaled.id()));
        status(khaled, reportTask, TaskStatus.IN_PROGRESS, null);
        // Time must be logged before approval (entries lock afterwards).
        timeEntryService.create(khaled, reportTask, new TimeEntryRequest(
                today.minusDays(50), new BigDecimal("8"), "Script + cron setup"));
        status(khaled, reportTask, TaskStatus.IN_REVIEW, null);
        status(omar, reportTask, TaskStatus.APPROVED, null);
        UUID importTask = task(omar, internalTools.id(), "CSV import cleanup",
                null, TaskPriority.LOW, today.minusDays(70), today.minusDays(45), "4",
                List.of(khaled.id()));
        status(khaled, importTask, TaskStatus.IN_PROGRESS, null);
        status(khaled, importTask, TaskStatus.IN_REVIEW, null);
        status(omar, importTask, TaskStatus.APPROVED, null);

        // Legacy Migration — cancelled project with a cancelled overdue task.
        UUID crmTask = task(omar, legacy.id(), "Map CRM entities",
                "Blocked on vendor export", TaskPriority.HIGH,
                today.minusDays(110), today.minusDays(60), "16", List.of());
        status(omar, crmTask, TaskStatus.CANCELLED, "Project cancelled");

        // --- Comments (incl. one edited) ---------------------------------
        commentService.create(ahmad, heroTask, new CommentTextRequest(
                "First exploration pushed to Figma — feedback welcome."));
        commentService.create(sara, heroTask, new CommentTextRequest(
                "Like option B, the gradient one. Can we try it on mobile widths?"));
        CommentResponse toEdit = commentService.create(lina, seoTask, new CommentTextRequest(
                "Working through the audit list now."));
        commentService.update(lina, toEdit.id(), new CommentTextRequest(
                "Working through the audit list now — 12 of 18 items done."));
        commentService.create(rami, navTask, new CommentTextRequest(
                "Keyboard navigation works well. Reviewing contrast next."));

        // --- Attachments --------------------------------------------------
        attachmentService.upload(ahmad, heroTask, new SeedFile(
                "hero-concepts.txt",
                "Option A: photo hero\nOption B: gradient hero\nOption C: illustration"));
        attachmentService.upload(sara, copyTask, new SeedFile(
                "pricing-copy-final.txt",
                "Simple, honest pricing.\nOne plan, everything included."));

        // --- Time entries -------------------------------------------------
        timeEntryService.create(ahmad, heroTask, new TimeEntryRequest(
                today.minusDays(2), new BigDecimal("3.5"), "Hero variants in Figma"));
        timeEntryService.create(ahmad, heroTask, new TimeEntryRequest(
                today.minusDays(1), new BigDecimal("2"), "Mobile width pass"));
        timeEntryService.create(lina, seoTask, new TimeEntryRequest(
                today.minusDays(3), new BigDecimal("4"), "Audit items 1-12"));

        // --- Recurring rule + one generation pass --------------------------
        recurringTaskRuleService.create(sara, new CreateRecurringTaskRuleRequest(
                website.id(), "Weekly design sync notes",
                "Post the sync summary as a task for visibility", TaskPriority.LOW,
                null, null, new BigDecimal("1"), List.of(lina.id()),
                RecurrenceFrequency.WEEKLY, 1, today.minusDays(7), null));
        recurringTaskGenerationService.generateDueTasks();

        // --- Notification read-states: Ahmad is caught up, others are not.
        notificationService.markAllRead(ahmad.id());

        log.info("DemoDataInitializer: seeded demo org '{}' — 8 users (1 deactivated, 1 custom role), "
                + "2 teams, 4 projects, ~16 tasks across all 6 statuses, comments, attachments, "
                + "time entries, 1 recurring rule. Login: {} / {}", "Demo Organization",
                ADMIN_EMAIL, PASSWORD);
    }

    private AuthenticatedUser createUser(AuthenticatedUser admin, String name, String email,
                                         String legacyRoleName, UUID roleId) {
        userService.create(admin, new CreateUserRequest(name, email, PASSWORD, roleId, legacyRoleName));
        return principal(email);
    }

    /** Builds a service-layer principal for a seeded user (role is in-session). */
    private AuthenticatedUser principal(String email) {
        AppUser user = userRepository.findByEmailIgnoreCase(email).orElseThrow();
        return new AuthenticatedUser(user.getId(), user.getOrganization().getId(),
                user.getRole().getName(), user.getRole().getDataScope(),
                Set.copyOf(user.getRole().getPermissions()));
    }

    private UUID task(AuthenticatedUser creator, UUID projectId, String title, String description,
                      TaskPriority priority, LocalDate start, LocalDate due, String estimatedHours,
                      List<UUID> assigneeIds) {
        return taskService.create(creator, new CreateTaskRequest(
                projectId, title, description, priority, start, due,
                estimatedHours == null ? null : new BigDecimal(estimatedHours),
                assigneeIds)).id();
    }

    private void status(AuthenticatedUser actor, UUID taskId, TaskStatus to, String note) {
        taskService.changeStatus(actor, taskId, new UpdateTaskStatusRequest(to, note));
    }

    /** Minimal in-memory MultipartFile for seeding attachments. */
    private record SeedFile(String filename, String content) implements MultipartFile {

        private byte[] bytes() {
            return content.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public String getName() {
            return "file";
        }

        @Override
        public String getOriginalFilename() {
            return filename;
        }

        @Override
        public String getContentType() {
            return "text/plain";
        }

        @Override
        public boolean isEmpty() {
            return content.isEmpty();
        }

        @Override
        public long getSize() {
            return bytes().length;
        }

        @Override
        public byte[] getBytes() {
            return bytes();
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(bytes());
        }

        @Override
        public void transferTo(File dest) throws IOException {
            try (FileOutputStream out = new FileOutputStream(dest)) {
                out.write(bytes());
            }
        }
    }
}
