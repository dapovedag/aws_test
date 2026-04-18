package com.prueba.aws.controller;

import com.prueba.aws.dto.Dtos.CostReport;
import com.prueba.aws.service.CostService;
import com.prueba.aws.service.MeteredCostService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Costo medido del despliegue. Estrategia:
 *  - /metered: SIEMPRE devuelve el cálculo basado en mediciones reales (EC2, RDS,
 *    S3, Glue, Athena APIs) × precios oficiales us-east-1. ESTA es la fuente de verdad.
 *  - /today, /month-to-date: intentan AWS Cost Explorer; si no está activo
 *    (cuenta nueva, requiere one-time manual enable) caen al cálculo metered.
 */
@RestController
@RequestMapping("/api/cost")
public class CostController {
    private final CostService cost;
    private final MeteredCostService metered;

    public CostController(CostService cost, MeteredCostService metered) {
        this.cost = cost;
        this.metered = metered;
    }

    @GetMapping("/metered")
    public CostReport metered() { return metered.compute(); }

    @GetMapping("/today")
    public CostReport today() {
        try {
            CostReport r = cost.today();
            if (r.totalMonth1() != null && !r.totalMonth1().equals("—")
                    && !r.totalMonth1().contains("estimado")) {
                return r;
            }
        } catch (Exception ignored) {}
        return metered.compute();
    }

    @GetMapping("/month-to-date")
    public CostReport monthToDate() {
        try {
            CostReport r = cost.monthToDate();
            if (r.totalMonth1() != null && !r.totalMonth1().equals("—")
                    && !r.totalMonth1().contains("estimado")) {
                return r;
            }
        } catch (Exception ignored) {}
        return metered.compute();
    }

    @GetMapping
    public CostReport legacy() { return metered.compute(); }

    @GetMapping("/sources")
    public Map<String, String> sources() {
        return Map.of(
                "metered", "Mediciones reales (EC2, RDS, S3, Glue, Athena APIs) × precios oficiales us-east-1",
                "cost-explorer", "AWS Cost Explorer · UnblendedCost por SERVICE — requiere activación manual one-time",
                "preferred", "metered"
        );
    }
}
