package com.easytask.backend.report;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * Renders the report HTML to PDF via openhtmltopdf (P4-12, D40). The bundled
 * Arabic font is registered programmatically as family {@code ReportArabic} —
 * the template's CSS uses that family so Arabic project/task names shape
 * correctly. openhtmltopdf requires well-formed XHTML, which the template emits.
 */
@Component
public class ProjectReportPdfRenderer {

    private static final String FONT_PATH = "fonts/NotoNaskhArabic-Regular.ttf";
    private static final String FONT_FAMILY = "ReportArabic";

    public byte[] render(ProjectReportService.ReportModel model) {
        // The font is registered by family below, so the CSS @font-face src is not needed for PDF.
        String html = ProjectReportHtml.render(model, null);
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.useFont(() -> openFont(), FONT_FAMILY);
            builder.withHtmlContent(html, null);
            builder.toStream(out);
            builder.run();
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to render project report PDF", e);
        }
    }

    private static InputStream openFont() {
        try {
            return new ClassPathResource(FONT_PATH).getInputStream();
        } catch (IOException e) {
            throw new UncheckedIOException("Bundled report font missing: " + FONT_PATH, e);
        }
    }
}
