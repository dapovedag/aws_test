package com.prueba.aws.controller;

import com.prueba.aws.dto.Dtos.DataModel;
import com.prueba.aws.service.DataModelService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class DataModelController {
    private final DataModelService svc;
    public DataModelController(DataModelService svc) { this.svc = svc; }

    @GetMapping("/data-model")
    public DataModel get() { return svc.describe(); }
}
