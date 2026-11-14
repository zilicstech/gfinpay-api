package com.fintech.agreement;

import com.fintech.platform.web.ApiException;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class AgreementRenderer {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([a-zA-Z0-9_.-]+)\\}\\}");

    public static final int FOOTER_SIGN_X = 410;
    public static final int FOOTER_SIGN_Y = 24;
    public static final int EXECUTION_BOX_X = 330;
    public static final int EXECUTION_BOX_Y = 260;

    public record RenderedPdf(byte[] bytes, String sha256, int pageCount) {}

    public RenderedPdf render(AgreementType type, Map<String, String> values) {
        String rawTemplate = loadTemplate(type.templateVersion());
        String html = fillPlaceholders(rawTemplate, values);
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.withHtmlContent(html, null);
            builder.toStream(out);
            builder.run();
            byte[] pdfBytes = out.toByteArray();
            int pages;
            try (PDDocument doc = PDDocument.load(pdfBytes)) {
                pages = Math.max(1, doc.getNumberOfPages());
            }
            return new RenderedPdf(pdfBytes, sha256Hex(pdfBytes), pages);
        } catch (Exception e) {
            throw ApiException.of(HttpStatus.INTERNAL_SERVER_ERROR, "AGREEMENT_RENDER_FAILED",
                    "Failed to generate agreement PDF: " + e.getMessage());
        }
    }

    public Map<String, List<Map<String, Integer>>> buildSignaturePositions(int pageCount, boolean signAllPages) {
        int total = Math.max(1, pageCount);
        Map<String, List<Map<String, Integer>>> positions = new LinkedHashMap<>();
        if (signAllPages) {
            for (int p = 1; p < total; p++) {
                positions.put(String.valueOf(p), List.of(Map.of("x", FOOTER_SIGN_X, "y", FOOTER_SIGN_Y)));
            }
        }
        List<Map<String, Integer>> lastPage = new ArrayList<>();
        lastPage.add(Map.of("x", EXECUTION_BOX_X, "y", EXECUTION_BOX_Y));
        if (signAllPages) {
            lastPage.add(Map.of("x", FOOTER_SIGN_X, "y", FOOTER_SIGN_Y));
        }
        positions.put(String.valueOf(total), lastPage);
        return positions;
    }

    public String extractSignerNameFromPdf(byte[] pdfBytes) {
        if (pdfBytes == null || pdfBytes.length < 5) {
            return null;
        }
        try (PDDocument doc = PDDocument.load(pdfBytes)) {
            List<PDSignature> signatures = doc.getSignatureDictionaries();
            if (signatures != null) {
                for (PDSignature sig : signatures) {
                    if (sig.getName() != null && !sig.getName().isBlank()) {
                        return sig.getName().trim();
                    }
                }
            }
            if (doc.getDocumentInformation() != null) {
                String custom = doc.getDocumentInformation().getCustomMetadataValue("EsignSignerName");
                if (custom != null && !custom.isBlank()) {
                    return custom.trim();
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    public static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private String loadTemplate(String templateVersion) {
        String path = "agreements/" + templateVersion + ".html";
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw ApiException.of(HttpStatus.INTERNAL_SERVER_ERROR, "AGREEMENT_TEMPLATE_MISSING",
                    "Agreement template not found: " + path);
        }
    }

    private String fillPlaceholders(String template, Map<String, String> values) {
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String key = m.group(1);
            String val = values.getOrDefault(key, "—");
            if (val == null || val.isBlank()) {
                val = "—";
            }
            val = val.replace("₹", "Rs. ");
            m.appendReplacement(sb, Matcher.quoteReplacement(escapeXml(val)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String escapeXml(String input) {
        StringBuilder out = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> {
                    if (c >= 0x20 || c == '\n' || c == '\r' || c == '\t') {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}

