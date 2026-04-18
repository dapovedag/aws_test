package com.prueba.aws.controller;

import com.prueba.aws.dto.Dtos.HealthInfo;
import com.prueba.aws.service.ReportService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@RestController
@RequestMapping("/api")
public class HealthController {

    private final JdbcTemplate jdbc;
    private final ReportService report;
    private final OffsetDateTime started = OffsetDateTime.now(ZoneOffset.UTC);

    public HealthController(JdbcTemplate jdbc, ReportService report) {
        this.jdbc = jdbc;
        this.report = report;
    }

    @GetMapping("/health")
    public HealthInfo health() {
        boolean db = false;
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
            db = true;
        } catch (Exception ignored) {}
        return new HealthInfo("ok", "0.1.0", started, db, report.lastFill());
    }
}
