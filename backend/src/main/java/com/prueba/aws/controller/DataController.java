package com.prueba.aws.controller;

import com.prueba.aws.dto.Dtos.*;
import com.prueba.aws.repo.DataRepository;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
public class DataController {

    private final DataRepository repo;

    public DataController(DataRepository repo) {
        this.repo = repo;
    }

    @GetMapping("/proveedores")
    public List<Proveedor> proveedores(@RequestParam(defaultValue = "200") int limit,
                                       @RequestParam(defaultValue = "0") int offset) {
        return repo.proveedores(Math.min(limit, 1000), Math.max(offset, 0));
    }

    @GetMapping("/clientes")
    public List<Cliente> clientes(@RequestParam(defaultValue = "200") int limit,
                                  @RequestParam(defaultValue = "0") int offset) {
        return repo.clientes(Math.min(limit, 2000), Math.max(offset, 0));
    }

    @GetMapping("/transacciones")
    public List<Transaccion> transacciones(@RequestParam(defaultValue = "200") int limit,
                                           @RequestParam(defaultValue = "0") int offset) {
        return repo.transacciones(Math.min(limit, 2000), Math.max(offset, 0));
    }

    @GetMapping("/dimensiones/ciudad")
    public List<Ciudad> ciudades() { return repo.ciudades(); }

    @GetMapping("/dimensiones/tipo-energia")
    public List<TipoEnergia> tiposEnergia() { return repo.tiposEnergia(); }

    @GetMapping("/kpis")
    public Kpis kpis() { return repo.kpis(); }

    @GetMapping("/datasource")
    public DatasourceMeta datasource() { return repo.datasource(); }
}
