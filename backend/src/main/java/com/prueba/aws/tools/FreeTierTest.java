package com.prueba.aws.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.prueba.aws.config.AppProperties;
import com.prueba.aws.dto.Dtos;
import com.prueba.aws.service.FreeTierService;
import com.prueba.aws.service.MeteredCostService;

/**
 * Prueba el FreeTierService sin levantar Spring ni AWS.
 * MeteredCostService.compute() falla por null clients → catch en FreeTierService → consumo = 0
 * → API debe reportar: 184 días restantes, $120 créditos, $0 cargo real.
 *
 * Uso: java -cp target/classes;<deps> com.prueba.aws.tools.FreeTierTest
 */
public class FreeTierTest {
    public static void main(String[] args) throws Exception {
        // 1. Construir AppProperties con los defaults del código (120 USD · 2026-10-17)
        AppProperties props = new AppProperties();

        // 2. MeteredCostService con clientes null (compute() lanzará NPE → catcheada)
        MeteredCostService cost = new MeteredCostService(null, null, null, null, null, props);

        // 3. FreeTierService que consulta los valores
        FreeTierService svc = new FreeTierService(props, cost);

        // 4. Llamar al método que el endpoint /api/free-tier/status expone
        Dtos.FreeTierStatus status = svc.status();

        // 5. Imprimir como JSON (idéntico a la respuesta HTTP)
        ObjectMapper m = new ObjectMapper();
        m.registerModule(new JavaTimeModule());
        m.enable(SerializationFeature.INDENT_OUTPUT);
        m.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        System.out.println("======================================================");
        System.out.println("RESPUESTA del endpoint GET /api/free-tier/status");
        System.out.println("======================================================");
        System.out.println(m.writeValueAsString(status));
        System.out.println("======================================================");
        System.out.println();
        System.out.println("Verificación humana:");
        System.out.println("  Free Tier termina:    " + status.freeTierEndsOn());
        System.out.println("  Días restantes:       " + status.daysRemaining());
        System.out.println("  Créditos iniciales:   " + status.startingCreditsUsd());
        System.out.println("  Créditos consumidos:  " + status.creditsConsumedUsd());
        System.out.println("  Créditos restantes:   " + status.creditsRemainingUsd());
        System.out.println("  Cargo real (tarjeta): " + status.outOfPocketUsd());
        System.out.println();
        System.out.println("Resumen humano:");
        System.out.println("  " + status.summary());
    }
}
