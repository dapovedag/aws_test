package com.prueba.aws.controller;

import com.prueba.aws.service.ReportService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/downloads")
public class DownloadController {

    private final ReportService report;

    public DownloadController(ReportService report) {
        this.report = report;
    }

    @GetMapping
    public Map<String, Object> all() {
        return Map.of(
                "report", Map.of(
                        "label", "Descargar PDF · Prueba AWS · solución completa",
                        "url", report.publicUrl(),
                        "sha", report.currentSha()
                )
        );
    }
}
