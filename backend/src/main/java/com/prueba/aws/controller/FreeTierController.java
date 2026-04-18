package com.prueba.aws.controller;

import com.prueba.aws.dto.Dtos.FreeTierStatus;
import com.prueba.aws.service.FreeTierService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/free-tier")
public class FreeTierController {

    private final FreeTierService svc;

    public FreeTierController(FreeTierService svc) {
        this.svc = svc;
    }

    /**
     * Estado del Free Tier + créditos AWS de la cuenta. Se recalcula cada vez:
     * - daysRemaining cae diariamente
     * - creditsRemaining cae si MeteredCostService reporta nuevo consumo
     * Espeja exactamente lo que la consola AWS muestra en el panel "Estado del plan gratuito".
     */
    @GetMapping("/status")
    public FreeTierStatus status() {
        return svc.status();
    }
}
