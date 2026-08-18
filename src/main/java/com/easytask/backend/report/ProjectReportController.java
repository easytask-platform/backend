package com.easytask.backend.report;

import com.easytask.backend.auth.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

/**
 * P4-12 (D40): project report export. One server-side HTML template is the
 * single source: {@code format=html} returns it as text/html; {@code format=pdf}
 * (default) renders it to a downloadable PDF via openhtmltopdf. Permission
 * {@code dashboard:manager}, org-scoped (cross-org is a 404).
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/report")
@RequiredArgsConstructor
public class ProjectReportController {

    private final ProjectReportService reportService;
    private final ProjectReportPdfRenderer pdfRenderer;

    @GetMapping
    @PreAuthorize("hasAuthority('dashboard:manager')")
    public ResponseEntity<byte[]> report(@AuthenticationPrincipal AuthenticatedUser principal,
                                         @PathVariable UUID projectId,
                                         @RequestParam(defaultValue = "pdf") String format) {
        ProjectReportService.ReportModel model = reportService.buildModel(principal, projectId);

        if ("html".equalsIgnoreCase(format)) {
            String html = ProjectReportHtml.render(model, null);
            return ResponseEntity.ok()
                    .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                    .body(html.getBytes(StandardCharsets.UTF_8));
        }

        byte[] pdf = pdfRenderer.render(model);
        String filename = slug(model.projectName()) + "-report.pdf";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(filename).build().toString())
                .body(pdf);
    }

    /** Filesystem-safe file stem from the project name (ASCII only, dashes). */
    private static String slug(String name) {
        String slug = name.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
        return slug.isBlank() ? "project" : slug;
    }
}
