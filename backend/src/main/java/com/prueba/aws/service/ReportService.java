package com.prueba.aws.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Orquesta la generación del PDF entregable de la prueba: delega la construcción a
 * HtmlPdfService (HTML + CSS → OpenHTMLtoPDF), persiste el resultado en S3 público y
 * mantiene el SHA actual para cache-busting de la URL.
 */
@Service
public class ReportService {
    private static final Logger log = LoggerFactory.getLogger(ReportService.class);

    private final HtmlPdfService pdf;
    private final S3Service s3;

    private final AtomicReference<String> currentSha = new AtomicReference<>("init");
    private OffsetDateTime lastFill = null;

    public ReportService(HtmlPdfService pdf, S3Service s3) {
        this.pdf = pdf;
        this.s3 = s3;
    }

    public String currentSha() { return currentSha.get(); }
    public OffsetDateTime lastFill() { return lastFill; }

    public synchronized String regenerate() {
        try {
            log.info("Regenerando PDF (HTML→PDF)...");
            byte[] bytes = pdf.buildPdf();
            String sha = sha256(bytes);
            s3.uploadPublic("report.pdf", bytes, "application/pdf");
            currentSha.set(sha);
            lastFill = OffsetDateTime.now(ZoneOffset.UTC);
            log.info("PDF subido · sha={} bytes={}", sha.substring(0, 8), bytes.length);
            return sha;
        } catch (Exception e) {
            log.error("Error generando PDF: {}", e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    public String publicUrl() {
        String sha = currentSha.get();
        String shortSha = sha.length() >= 12 ? sha.substring(0, 12) : sha;
        return s3.publicUrl("report.pdf") + "?v=" + shortSha;
    }

    private static String sha256(byte[] data) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return Long.toHexString(System.currentTimeMillis());
        }
    }
}
