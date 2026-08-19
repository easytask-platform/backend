package com.easytask.backend.runner;

import com.easytask.backend.attachment.AttachmentService;
import com.easytask.backend.auth.AuthService;
import com.easytask.backend.auth.AuthenticatedUser;
import com.easytask.backend.auth.RegisterOrganizationRequest;
import com.easytask.backend.checklist.ChecklistService;
import com.easytask.backend.checklist.CreateChecklistItemRequest;
import com.easytask.backend.checklist.UpdateChecklistItemRequest;
import com.easytask.backend.comment.CommentResponse;
import com.easytask.backend.comment.CommentService;
import com.easytask.backend.comment.CommentTextRequest;
import com.easytask.backend.comment.CreateCommentRequest;
import com.easytask.backend.notification.NotificationService;
import com.easytask.backend.project.AddProjectMemberRequest;
import com.easytask.backend.project.CreateProjectRequest;
import com.easytask.backend.project.ProjectService;
import com.easytask.backend.project.ProjectStatus;
import com.easytask.backend.recurring.CreateRecurringTaskRuleRequest;
import com.easytask.backend.recurring.RecurrenceFrequency;
import com.easytask.backend.recurring.RecurringTaskGenerationService;
import com.easytask.backend.recurring.RecurringTaskRuleResponse;
import com.easytask.backend.recurring.RecurringTaskRuleService;
import com.easytask.backend.savedfilter.CreateSavedFilterRequest;
import com.easytask.backend.savedfilter.SavedFilterService;
import com.easytask.backend.tag.CreateTagRequest;
import com.easytask.backend.tag.TagService;
import com.easytask.backend.task.CreateTaskRequest;
import com.easytask.backend.task.TaskPinService;
import com.easytask.backend.task.TaskPriority;
import com.easytask.backend.task.TaskService;
import com.easytask.backend.task.TaskStatus;
import com.easytask.backend.task.UpdateTaskBlockedRequest;
import com.easytask.backend.task.UpdateTaskStatusRequest;
import com.easytask.backend.team.AddTeamMemberRequest;
import com.easytask.backend.team.CreateTeamRequest;
import com.easytask.backend.team.TeamService;
import com.easytask.backend.timeentry.TimeEntryRequest;
import com.easytask.backend.timeentry.TimeEntryService;
import com.easytask.backend.user.AppUser;
import com.easytask.backend.user.AppUserRepository;
import com.easytask.backend.user.CreateUserRequest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
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
import java.util.stream.Collectors;

/**
 * One-shot production reseed (EASYTASK_RESEED=true). WIPES every organization's
 * data then seeds a single org "Easy Task Org" with a fixed set of accounts and
 * a broad, realistic dataset — built THROUGH THE SERVICE LAYER so activity logs
 * and notifications are generated exactly like real usage.
 *
 * <p>Only two tables are preserved: {@code flyway_schema_history} and the
 * {@code permissions} catalog (reference data that {@code role_permissions}
 * references and role provisioning depends on).
 *
 * <p>Exactly four users (one org admin + three employees, no managers) by
 * design, so the manager-scope, custom-role and deactivated-user cases are not
 * represented; everything else the frontends can display is.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EasyTaskReseedInitializer {

    public static final String ORG_NAME = "Easy Task Org";
    public static final String PASSWORD = "1234567890";
    public static final String ADMIN_EMAIL = "abdalazez.alboga@gmail.com";

    private final AuthService authService;
    private final AppUserRepository userRepository;
    private final com.easytask.backend.user.UserService userService;
    private final TeamService teamService;
    private final ProjectService projectService;
    private final TagService tagService;
    private final TaskService taskService;
    private final ChecklistService checklistService;
    private final TaskPinService taskPinService;
    private final CommentService commentService;
    private final AttachmentService attachmentService;
    private final TimeEntryService timeEntryService;
    private final RecurringTaskRuleService recurringTaskRuleService;
    private final RecurringTaskGenerationService recurringTaskGenerationService;
    private final SavedFilterService savedFilterService;
    private final NotificationService notificationService;
    private final tools.jackson.databind.ObjectMapper objectMapper;

    @PersistenceContext
    private EntityManager entityManager;

    @Transactional
    public void reseed() {
        wipeAllData();
        seed();
    }

    /** Truncates every table except Flyway history and the permission catalog. */
    @SuppressWarnings("unchecked")
    private void wipeAllData() {
        List<String> tables = entityManager.createNativeQuery(
                        "SELECT tablename FROM pg_tables WHERE schemaname = 'public' "
                                + "AND tablename NOT IN ('flyway_schema_history', 'permissions')")
                .getResultList();
        if (tables.isEmpty()) {
            return;
        }
        String joined = tables.stream().map(t -> "\"" + t + "\"").collect(Collectors.joining(", "));
        entityManager.createNativeQuery("TRUNCATE TABLE " + joined + " RESTART IDENTITY CASCADE")
                .executeUpdate();
        entityManager.flush();
        entityManager.clear();
        log.info("EasyTaskReseed: wiped {} tables", tables.size());
    }

    private void seed() {
        LocalDate today = LocalDate.now();

        // --- Organization + people (exactly 4: 1 admin, 3 employees) --------
        authService.registerOrganization(new RegisterOrganizationRequest(
                ORG_NAME, "Abdalazez Alboga", ADMIN_EMAIL, PASSWORD));
        AuthenticatedUser admin = principal(ADMIN_EMAIL);

        AuthenticatedUser siba = createEmployee(admin, "Siba Ghazaly", "siba.ghazaly@gmail.com");
        AuthenticatedUser esraa = createEmployee(admin, "Esraa Abo Isa", "esraaaboisaa2@gmail.com");
        AuthenticatedUser sajed = createEmployee(admin, "Sajed Taljahd", "sajdtaljahd4@gmail.com");

        // --- Teams ----------------------------------------------------------
        var development = teamService.create(admin, new CreateTeamRequest(
                "Development", "Builds and ships the product"));
        for (AuthenticatedUser member : List.of(siba, esraa)) {
            teamService.addMember(admin, development.id(), new AddTeamMemberRequest(member.id()));
        }
        var design = teamService.create(admin, new CreateTeamRequest(
                "Design", "Owns UX and visual language"));
        for (AuthenticatedUser member : List.of(sajed, esraa)) {
            teamService.addMember(admin, design.id(), new AddTeamMemberRequest(member.id()));
        }

        // --- Projects (one per status; one overdue window) ------------------
        var website = projectService.create(admin, new CreateProjectRequest(
                "Website Redesign", "Public site refresh with the new brand",
                ProjectStatus.ACTIVE, today.minusDays(20), today.plusDays(25)));
        for (AuthenticatedUser member : List.of(siba, esraa, sajed)) {
            projectService.addMember(admin, website.id(), new AddProjectMemberRequest(member.id()));
        }
        var mobile = projectService.create(admin, new CreateProjectRequest(
                "Mobile App Launch", "Take the task app to the stores",
                ProjectStatus.PLANNED, today.plusDays(7), today.plusDays(60)));
        projectService.addMember(admin, mobile.id(), new AddProjectMemberRequest(siba.id()));
        projectService.addMember(admin, mobile.id(), new AddProjectMemberRequest(sajed.id()));
        var internalTools = projectService.create(admin, new CreateProjectRequest(
                "Internal Tools", "Small automations for the operations team",
                ProjectStatus.COMPLETED, today.minusDays(90), today.minusDays(10)));
        projectService.addMember(admin, internalTools.id(), new AddProjectMemberRequest(esraa.id()));
        var legacy = projectService.create(admin, new CreateProjectRequest(
                "Legacy Migration", "Abandoned attempt to port the old CRM",
                ProjectStatus.CANCELLED, today.minusDays(120), today.minusDays(30)));

        // --- Tags -----------------------------------------------------------
        var designTag = tagService.create(admin, website.id(), new CreateTagRequest("Design", "#8b5cf6"));
        var frontendTag = tagService.create(admin, website.id(), new CreateTagRequest("Frontend", "#0ea5e9"));
        var contentTag = tagService.create(admin, website.id(), new CreateTagRequest("Content", "#f59e0b"));
        var storeTag = tagService.create(admin, mobile.id(), new CreateTagRequest("Store", "#22c55e"));

        // --- Tasks: every status, priority, overdue, multi/unassigned -------
        UUID heroTask = task(admin, website.id(), "Design new hero section",
                "Figma exploration for the landing hero", TaskPriority.HIGH,
                today.minusDays(15), today.plusDays(2), "6", List.of(siba.id(), esraa.id()),
                List.of(designTag.id(), frontendTag.id()));
        UUID navTask = task(admin, website.id(), "Rebuild navigation component",
                "Accessible dropdown navigation", TaskPriority.MEDIUM,
                today.minusDays(12), today.plusDays(5), "8", List.of(siba.id()),
                List.of(frontendTag.id()));
        UUID seoTask = task(admin, website.id(), "SEO audit fixes",
                "Apply the findings from the March audit", TaskPriority.LOW,
                today.minusDays(10), today.minusDays(1), "4", List.of(esraa.id()),
                List.of(contentTag.id())); // overdue
        UUID copyTask = task(admin, website.id(), "Rewrite pricing page copy",
                "Tone-of-voice pass on pricing", TaskPriority.CRITICAL,
                today.minusDays(8), today.plusDays(1), "3", List.of(esraa.id()),
                List.of(contentTag.id()));
        UUID blogTask = task(admin, website.id(), "Blog templates", null,
                TaskPriority.MEDIUM, today.minusDays(5), null, null, List.of(siba.id())); // no due date
        task(admin, website.id(), "Collect testimonial quotes",
                "Waiting for marketing input", TaskPriority.LOW,
                null, today.plusDays(30), null, List.of()); // unassigned, TO_DO backlog
        UUID droppedTask = task(admin, website.id(), "Dark mode exploration",
                "Out of scope for this quarter", TaskPriority.LOW,
                today.minusDays(9), today.plusDays(20), "5", List.of(siba.id()));

        // Status journeys covering every state.
        status(siba, heroTask, TaskStatus.IN_PROGRESS, null);                       // IN_PROGRESS
        status(siba, navTask, TaskStatus.IN_PROGRESS, null);
        status(siba, navTask, TaskStatus.IN_REVIEW, "Ready for a look");            // IN_REVIEW
        status(esraa, seoTask, TaskStatus.IN_PROGRESS, null);
        status(esraa, seoTask, TaskStatus.IN_REVIEW, null);
        status(admin, seoTask, TaskStatus.REOPENED, "Meta descriptions still missing on 3 pages"); // REOPENED
        status(esraa, copyTask, TaskStatus.IN_PROGRESS, null);
        status(esraa, copyTask, TaskStatus.IN_REVIEW, "First full draft attached");
        status(admin, copyTask, TaskStatus.APPROVED, "Great work");                 // APPROVED
        status(admin, droppedTask, TaskStatus.CANCELLED, "Deprioritized by product"); // CANCELLED

        // Mobile App Launch — planned work.
        UUID storeTask = task(admin, mobile.id(), "Store listing assets", "Screenshots and copy",
                TaskPriority.MEDIUM, today.plusDays(7), today.plusDays(21), "6",
                List.of(sajed.id()), List.of(storeTag.id()));
        UUID betaTask = task(admin, mobile.id(), "Recruit beta testers",
                "20 external testers via the newsletter", TaskPriority.HIGH,
                today.plusDays(3), today.plusDays(14), "2", List.of(siba.id()));
        status(siba, betaTask, TaskStatus.IN_PROGRESS, null);

        // Internal Tools — finished project, everything approved.
        UUID reportTask = task(admin, internalTools.id(), "Weekly ops report script",
                "Python script + cron", TaskPriority.MEDIUM,
                today.minusDays(80), today.minusDays(40), "10", List.of(esraa.id()));
        status(esraa, reportTask, TaskStatus.IN_PROGRESS, null);
        // Time must be logged before approval (entries lock afterwards).
        timeEntryService.create(esraa, reportTask, new TimeEntryRequest(
                today.minusDays(50), new BigDecimal("8"), "Script + cron setup"));
        status(esraa, reportTask, TaskStatus.IN_REVIEW, null);
        status(admin, reportTask, TaskStatus.APPROVED, null);
        UUID importTask = task(admin, internalTools.id(), "CSV import cleanup",
                null, TaskPriority.LOW, today.minusDays(70), today.minusDays(45), "4",
                List.of(esraa.id()));
        status(esraa, importTask, TaskStatus.IN_PROGRESS, null);
        status(esraa, importTask, TaskStatus.IN_REVIEW, null);
        status(admin, importTask, TaskStatus.APPROVED, null);

        // Legacy Migration — cancelled project with a cancelled overdue task.
        UUID crmTask = task(admin, legacy.id(), "Map CRM entities",
                "Blocked on vendor export", TaskPriority.HIGH,
                today.minusDays(110), today.minusDays(60), "16", List.of());
        status(admin, crmTask, TaskStatus.CANCELLED, "Project cancelled");

        // --- Checklists -----------------------------------------------------
        var heroWireframe = checklistService.create(admin, heroTask,
                new CreateChecklistItemRequest("Wireframe three hero variants"));
        checklistService.create(admin, heroTask, new CreateChecklistItemRequest("Desktop design in Figma"));
        checklistService.create(admin, heroTask, new CreateChecklistItemRequest("Mobile width pass"));
        checklistService.update(siba, heroWireframe.id(), new UpdateChecklistItemRequest(null, true, null));
        var storeScreens = checklistService.create(admin, storeTask,
                new CreateChecklistItemRequest("Capture screenshots on both stores"));
        checklistService.create(admin, storeTask, new CreateChecklistItemRequest("Write listing copy"));
        checklistService.update(sajed, storeScreens.id(), new UpdateChecklistItemRequest(null, true, null));

        // --- Blocked flag ---------------------------------------------------
        taskService.setBlocked(siba, blogTask, new UpdateTaskBlockedRequest(true,
                "بانتظار محتوى التسويق قبل البدء بالقوالب"));

        // --- Focus pins -----------------------------------------------------
        for (UUID pinned : List.of(heroTask, copyTask, betaTask)) {
            taskPinService.pin(admin, pinned);
        }
        taskPinService.pin(siba, heroTask);
        taskPinService.pin(siba, navTask);

        // --- Comments (incl. one edited + a reply with a mention) -----------
        CommentResponse heroThread = commentService.create(siba, heroTask, new CreateCommentRequest(
                "First exploration pushed to Figma — feedback welcome.", null, null));
        commentService.create(admin, heroTask, new CreateCommentRequest(
                "Like option B, the gradient one. Can we try it on mobile widths?",
                heroThread.id(), List.of(siba.id())));
        CommentResponse toEdit = commentService.create(esraa, seoTask, new CreateCommentRequest(
                "Working through the audit list now.", null, null));
        commentService.update(esraa, toEdit.id(), new CommentTextRequest(
                "Working through the audit list now — 12 of 18 items done."));

        // --- Attachments (files are ephemeral on Render; rows persist) ------
        attachmentService.upload(siba, heroTask, new SeedFile("hero-concepts.txt",
                "Option A: photo hero\nOption B: gradient hero\nOption C: illustration"));
        attachmentService.upload(admin, copyTask, new SeedFile("pricing-copy-final.txt",
                "Simple, honest pricing.\nOne plan, everything included."));

        // --- Time entries ---------------------------------------------------
        timeEntryService.create(siba, heroTask, new TimeEntryRequest(
                today.minusDays(2), new BigDecimal("3.5"), "Hero variants in Figma"));
        timeEntryService.create(siba, heroTask, new TimeEntryRequest(
                today.minusDays(1), new BigDecimal("2"), "Mobile width pass"));
        timeEntryService.create(esraa, seoTask, new TimeEntryRequest(
                today.minusDays(3), new BigDecimal("4"), "Audit items 1-12"));

        // --- Recurring rule + one generation pass + a skipped occurrence ----
        RecurringTaskRuleResponse weeklySync = recurringTaskRuleService.create(admin,
                new CreateRecurringTaskRuleRequest(
                        website.id(), "Weekly design sync notes",
                        "Post the sync summary as a task for visibility", TaskPriority.LOW,
                        null, null, new BigDecimal("1"), List.of(esraa.id()),
                        RecurrenceFrequency.WEEKLY, 1, today.minusDays(7), null));
        recurringTaskGenerationService.generateDueTasks();
        recurringTaskRuleService.occurrences(admin, weeklySync.id(), 6).items().stream()
                .filter(o -> o.date().isAfter(today))
                .findFirst()
                .ifPresent(o -> recurringTaskRuleService.addException(admin, weeklySync.id(), o.date()));

        // --- Saved filters --------------------------------------------------
        savedFilterService.create(admin, new CreateSavedFilterRequest("My overdue work",
                objectMapper.createObjectNode().put("overdue", true).put("sort", "dueDate")));
        savedFilterService.create(admin, new CreateSavedFilterRequest("Critical & high",
                objectMapper.createObjectNode().put("priority", "CRITICAL")));

        // --- Notification read-states: Siba is caught up, others are not ----
        notificationService.markAllRead(siba.id());

        log.info("EasyTaskReseed: seeded '{}' — 4 users (1 admin, 3 employees), 2 teams, 4 projects, "
                + "~15 tasks across all statuses, 4 tags, checklists, a blocked task, focus pins, "
                + "comments, attachments, time entries, and a recurring rule. Login with {} / {}",
                ORG_NAME, ADMIN_EMAIL, PASSWORD);
    }

    private AuthenticatedUser createEmployee(AuthenticatedUser admin, String name, String email) {
        // initialPassword path → mustChangePassword=true; clear it so the seeded
        // accounts sign in directly with PASSWORD (no forced first-login change).
        userService.create(admin, new CreateUserRequest(name, email, PASSWORD, null, "EMPLOYEE"));
        AppUser user = userRepository.findByEmailIgnoreCase(email).orElseThrow();
        user.setMustChangePassword(false);
        userRepository.save(user);
        return principal(email);
    }

    private AuthenticatedUser principal(String email) {
        AppUser user = userRepository.findByEmailIgnoreCase(email).orElseThrow();
        return new AuthenticatedUser(user.getId(), user.getOrganization().getId(),
                user.getRole().getName(), user.getRole().getDataScope(),
                Set.copyOf(user.getRole().getPermissions()));
    }

    private UUID task(AuthenticatedUser creator, UUID projectId, String title, String description,
                      TaskPriority priority, LocalDate start, LocalDate due, String estimatedHours,
                      List<UUID> assigneeIds) {
        return task(creator, projectId, title, description, priority, start, due, estimatedHours,
                assigneeIds, null);
    }

    private UUID task(AuthenticatedUser creator, UUID projectId, String title, String description,
                      TaskPriority priority, LocalDate start, LocalDate due, String estimatedHours,
                      List<UUID> assigneeIds, List<UUID> tagIds) {
        return taskService.create(creator, new CreateTaskRequest(
                projectId, title, description, priority, start, due,
                estimatedHours == null ? null : new BigDecimal(estimatedHours),
                assigneeIds, tagIds)).id();
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
