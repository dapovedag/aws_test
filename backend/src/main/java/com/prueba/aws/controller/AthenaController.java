package com.prueba.aws.controller;

import com.prueba.aws.dto.Dtos.AthenaQueryDef;
import com.prueba.aws.dto.Dtos.AthenaResult;
import com.prueba.aws.service.AthenaService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/athena")
public class AthenaController {

    private final AthenaService svc;

    public AthenaController(AthenaService svc) {
        this.svc = svc;
    }

    @GetMapping("/queries")
    public List<AthenaQueryDef> queries() {
        return svc.queries();
    }

    @PostMapping("/run/{id}")
    public AthenaResult run(@PathVariable String id) {
        return svc.run(id);
    }
}
