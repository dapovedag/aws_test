package com.prueba.aws.controller;

import com.prueba.aws.service.CsvExportService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.List;

@RestController
@RequestMapping("/api/export")
public class ExportController {

    private final CsvExportService svc;

    public ExportController(CsvExportService svc) {
        this.svc = svc;
    }

    @GetMapping
    public List<String> tables() {
        return svc.allowedTables();
    }

    @GetMapping(value = "/{schema}/{table}.csv", produces = "text/csv")
    public ResponseEntity<StreamingResponseBody> download(
            @PathVariable String schema,
            @PathVariable String table,
            @RequestParam(defaultValue = "10000") int max) {
        String fq = schema + "." + table;
        if (!svc.isAllowed(fq)) {
            return ResponseEntity.status(404).body(out -> out.write(("Tabla no permitida: " + fq).getBytes()));
        }
        StreamingResponseBody body = out -> svc.writeCsv(fq, max, out);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv; charset=utf-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + table + ".csv\"")
                .body(body);
    }
}
