package com.easytask.backend.report;

import com.easytask.backend.report.ProjectReportService.MemberProgress;
import com.easytask.backend.report.ProjectReportService.ReportModel;
import com.easytask.backend.task.TaskListItemResponse;
import com.easytask.backend.task.TaskStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The single source-of-truth report template (P4-12, D40): one self-contained
 * XHTML document with inline CSS and no external assets. The Arabic-capable
 * font is referenced via {@code @font-face} pointing at the bundled TTF so
 * Arabic project/task names shape correctly when rendered to PDF. Returned
 * verbatim for {@code format=html} and fed to openhtmltopdf for {@code pdf}.
 */
final class ProjectReportHtml {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private ProjectReportHtml() {
    }

    /**
     * @param fontUri a URI the renderer can resolve to the bundled Arabic font
     *                (a {@code file:} URI for PDF, or empty to omit @font-face
     *                for the browser HTML view where system fonts apply).
     */
    static String render(ReportModel model, String fontUri) {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<html xmlns=\"http://www.w3.org/1999/xhtml\"><head>");
        sb.append("<meta charset=\"UTF-8\"/>");
        sb.append("<title>").append(esc(model.projectName())).append(" — Report</title>");
        sb.append("<style>");
        if (fontUri != null && !fontUri.isBlank()) {
            sb.append("@font-face{font-family:'ReportArabic';src:url('")
                    .append(fontUri).append("');}");
        }
        sb.append("""
                * { box-sizing: border-box; }
                body { font-family: 'ReportArabic', 'Noto Naskh Arabic', sans-serif; color: #1e293b;
                       font-size: 12px; margin: 24px; }
                h1 { font-size: 22px; margin: 0 0 4px; }
                h2 { font-size: 15px; margin: 20px 0 8px; color: #0f172a;
                     border-bottom: 2px solid #e2e8f0; padding-bottom: 4px; }
                .muted { color: #64748b; }
                .header-meta { margin: 0 0 12px; }
                .progress-wrap { background: #e2e8f0; border-radius: 6px; height: 14px; width: 240px;
                                 display: inline-block; vertical-align: middle; overflow: hidden; }
                .progress-bar { background: #0ea5e9; height: 14px; }
                table { border-collapse: collapse; width: 100%; margin-top: 6px; }
                th, td { border: 1px solid #e2e8f0; padding: 5px 7px; text-align: left; vertical-align: top; }
                th { background: #f1f5f9; font-weight: bold; }
                .chip { display: inline-block; padding: 1px 6px; border-radius: 4px; font-size: 10px; }
                .blocked { color: #b45309; font-weight: bold; }
                .status-counts td { text-align: center; }
                """);
        sb.append("</style></head><body>");

        // --- Header ---
        sb.append("<h1>").append(esc(model.projectName())).append("</h1>");
        sb.append("<p class=\"header-meta\">");
        sb.append("<span class=\"chip\" style=\"background:#e0f2fe;\">")
                .append(esc(model.status().name())).append("</span> ");
        sb.append("<span class=\"muted\">")
                .append(dateRange(model.startDate(), model.dueDate())).append("</span>");
        sb.append("</p>");
        sb.append("<p>Progress: <span class=\"progress-wrap\"><span class=\"progress-bar\" style=\"width:")
                .append(model.progress()).append("%;\"></span></span> <strong>")
                .append(model.progress()).append("%</strong> ")
                .append("<span class=\"muted\">(").append(model.approvedTasks()).append(" of ")
                .append(model.totalTasks()).append(" tasks approved)</span></p>");

        // --- Status summary ---
        sb.append("<h2>Status summary</h2>");
        sb.append("<table class=\"status-counts\"><tr>");
        for (TaskStatus status : TaskStatus.values()) {
            sb.append("<th>").append(esc(status.name())).append("</th>");
        }
        sb.append("</tr><tr>");
        Map<TaskStatus, Long> counts = model.statusCounts();
        for (TaskStatus status : TaskStatus.values()) {
            sb.append("<td>").append(counts.getOrDefault(status, 0L)).append("</td>");
        }
        sb.append("</tr></table>");

        // --- Team progress ---
        sb.append("<h2>Team progress</h2>");
        if (model.team().isEmpty()) {
            sb.append("<p class=\"muted\">No project members.</p>");
        } else {
            sb.append("<table><tr><th>Member</th><th>Assigned</th><th>Completed</th>"
                    + "<th>Logged hours</th></tr>");
            for (MemberProgress m : model.team()) {
                sb.append("<tr><td>").append(esc(m.fullName())).append("</td>")
                        .append("<td>").append(m.assigned()).append("</td>")
                        .append("<td>").append(m.completed()).append("</td>")
                        .append("<td>").append(hours(m.loggedHours())).append("</td></tr>");
            }
            sb.append("</table>");
        }

        // --- Task table ---
        sb.append("<h2>Tasks</h2>");
        if (model.tasks().isEmpty()) {
            sb.append("<p class=\"muted\">No tasks.</p>");
        } else {
            sb.append("<table><tr><th>Title</th><th>Status</th><th>Priority</th><th>Assignees</th>"
                    + "<th>Due</th><th>Blocked</th><th>Checklist</th></tr>");
            for (TaskListItemResponse t : model.tasks()) {
                String assignees = t.assignees().stream()
                        .map(a -> esc(a.fullName()))
                        .collect(Collectors.joining(", "));
                sb.append("<tr><td>").append(esc(t.title())).append("</td>")
                        .append("<td>").append(esc(t.status().name())).append("</td>")
                        .append("<td>").append(esc(t.priority().name())).append("</td>")
                        .append("<td>").append(assignees.isEmpty() ? "—" : assignees).append("</td>")
                        .append("<td>").append(t.dueDate() == null ? "—" : t.dueDate().format(DATE)).append("</td>")
                        .append("<td>").append(t.blocked()
                                ? "<span class=\"blocked\">Blocked</span>" : "—").append("</td>")
                        .append("<td>").append(t.checklistTotal() == 0 ? "—"
                                : t.checklistDone() + "/" + t.checklistTotal()).append("</td></tr>");
            }
            sb.append("</table>");
        }

        sb.append("<p class=\"muted\" style=\"margin-top:24px;\">Generated on ")
                .append(model.generatedOn().format(DATE)).append("</p>");
        sb.append("</body></html>");
        return sb.toString();
    }

    private static String dateRange(LocalDate start, LocalDate due) {
        String s = start == null ? "—" : start.format(DATE);
        String d = due == null ? "—" : due.format(DATE);
        return s + " → " + d;
    }

    private static String hours(BigDecimal value) {
        return value == null ? "0" : value.stripTrailingZeros().toPlainString();
    }

    private static String esc(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
