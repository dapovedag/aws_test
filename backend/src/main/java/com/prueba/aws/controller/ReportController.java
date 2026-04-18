package com.prueba.aws.controller;

import com.prueba.aws.config.AppProperties;
import com.prueba.aws.service.ReportService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
@RequestMapping("/api/report")
public class ReportController {

    private final ReportService report;
    private final AppProperties props;

    public ReportController(ReportService report, AppProperties props) {
        this.report = report;
        this.props = props;
    }

    @GetMapping("/url")
    public Map<String, String> url() {
        return Map.of(
                "url", report.publicUrl(),
                "sha", report.currentSha(),
                "lastFill", report.lastFill() == null ? "n/a" : report.lastFill().toString()
        );
    }

    @PostMapping("/regenerate")
    public Map<String, String> regenerate(@RequestHeader(value = "X-Edit-Token", required = false) String token) {
        if (props.getEditToken() == null || !props.getEditToken().equals(token)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Token requerido");
        }
        String sha = report.regenerate();
        return Map.of("ok", "true", "sha", sha, "url", report.publicUrl());
    }
}
