package com.prueba.aws.controller;

import com.prueba.aws.dto.Dtos.DataQualitySummary;
import com.prueba.aws.service.DataQualityService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/data-quality")
public class DataQualityController {
    private final DataQualityService svc;
    public DataQualityController(DataQualityService svc) { this.svc = svc; }

    @GetMapping
    public DataQualitySummary summary() { return svc.getSummary(); }

    @GetMapping("/report-url")
    public Map<String, String> reportUrl() {
        return Map.of("url", svc.reportHtmlUrl());
    }
}
