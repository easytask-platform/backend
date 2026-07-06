package com.easytask.backend.dashboard;

import com.easytask.backend.auth.AuthenticatedUser;
import com.easytask.backend.common.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;
    private final ReportService reportService;

    @GetMapping("/dashboard/admin")
    @PreAuthorize("hasRole('ORGANIZATION_ADMIN')")
    public AdminDashboardResponse adminDashboard(@AuthenticationPrincipal AuthenticatedUser principal) {
        return dashboardService.adminDashboard(principal);
    }

    @GetMapping("/dashboard/manager")
    @PreAuthorize("hasAnyRole('ORGANIZATION_ADMIN', 'MANAGER')")
    public ManagerDashboardResponse managerDashboard(@AuthenticationPrincipal AuthenticatedUser principal) {
        return dashboardService.managerDashboard(principal);
    }

    @GetMapping("/reports/workload")
    @PreAuthorize("hasAnyRole('ORGANIZATION_ADMIN', 'MANAGER')")
    public PageResponse<WorkloadItemResponse> workload(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(required = false) UUID teamId,
            @RequestParam(required = false) UUID projectId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @PageableDefault(sort = "fullName") Pageable pageable) {
        return reportService.workload(principal, teamId, projectId, from, to, pageable);
    }

    @GetMapping("/reports/project-progress")
    @PreAuthorize("hasAnyRole('ORGANIZATION_ADMIN', 'MANAGER')")
    public PageResponse<ProjectProgressItemResponse> projectProgress(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(required = false) UUID projectId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @PageableDefault(sort = "name") Pageable pageable) {
        return reportService.projectProgress(principal, projectId, from, to, pageable);
    }
}
