package com.prueba.aws.controller;

import com.prueba.aws.service.DivisasService;
import com.prueba.aws.service.DivisasService.*;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/divisas")
public class DivisasController {

    private final DivisasService svc;
    public DivisasController(DivisasService svc) { this.svc = svc; }

    @GetMapping("/pares")
    public List<Par> pares() { return svc.pares(); }

    @GetMapping("/ticks")
    public List<Tick> ticks(@RequestParam(required = false) String par,
                            @RequestParam(defaultValue = "24") int hours,
                            @RequestParam(defaultValue = "100") int limit) {
        return svc.ticks(par, Math.min(hours, 720), Math.min(limit, 1000));
    }

    @GetMapping("/notificaciones")
    public List<Notificacion> notificaciones(@RequestParam(defaultValue = "20") int limit) {
        return svc.notificaciones(Math.min(limit, 200));
    }

    @GetMapping("/modelos")
    public List<Modelo> modelos() { return svc.modelos(); }

    @GetMapping("/dashboard")
    public Dashboard dashboard() { return svc.dashboard(); }
}
