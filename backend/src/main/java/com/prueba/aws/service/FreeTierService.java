package com.prueba.aws.service;

import com.prueba.aws.config.AppProperties;
import com.prueba.aws.dto.Dtos.CreditView;
import com.prueba.aws.dto.Dtos.FreeTierStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/**
 * Calcula el estado del Free Tier + créditos AWS de la cuenta de forma dinámica.
 * Espeja la información que muestra la consola AWS en "Estado del plan gratuito":
 * créditos restantes, días restantes hasta el fin del Free Tier y cargo real
 * facturable. Los valores cambian solos con el tiempo (días) y con el consumo (créditos).
 */
@Service
public class FreeTierService {
    private static final Logger log = LoggerFactory.getLogger(FreeTierService.class);

    private final AppProperties props;
    private final MeteredCostService meteredCostSvc;

    public FreeTierService(AppProperties props, MeteredCostService meteredCostSvc) {
        this.props = props;
        this.meteredCostSvc = meteredCostSvc;
    }

    @Cacheable("free-tier")
    public FreeTierStatus status() {
        BigDecimal starting = props.getCredits().getStartingUsd();
        LocalDate endsOn = props.getCredits().getFreeTierEndsOn();
        // America/Bogota para que coincida con la zona horaria del usuario
        // (la consola AWS usa la zona local del perfil del usuario)
        LocalDate today = LocalDate.now(ZoneId.of("America/Bogota"));
        // +1 porque la consola AWS cuenta el día actual de forma inclusiva
        int daysRemaining = (int) Math.max(0, ChronoUnit.DAYS.between(today, endsOn) + 1);

        BigDecimal consumed = BigDecimal.ZERO;
        BigDecimal listConsumed = BigDecimal.ZERO;
        BigDecimal freeTierSaved = BigDecimal.ZERO;
        try {
            var costReport = meteredCostSvc.compute();
            if (costReport.credits() != null) {
                CreditView c = costReport.credits();
                consumed = parseUsd(c.creditsApplied());
                listConsumed = parseUsd(c.listConsumed());
                freeTierSaved = parseUsd(c.freeTierSaved());
            }
        } catch (Exception e) {
            log.warn("MeteredCostService no disponible para Free Tier status: {}", e.getMessage());
        }

        BigDecimal remaining = starting.subtract(consumed).max(BigDecimal.ZERO);
        BigDecimal outOfPocket = consumed.subtract(starting).max(BigDecimal.ZERO);

        String summary;
        if (outOfPocket.compareTo(BigDecimal.ZERO) > 0) {
            summary = "Créditos AWS agotados · cargo facturable: $" + outOfPocket.setScale(2, RoundingMode.HALF_UP) + " USD";
        } else if (consumed.compareTo(BigDecimal.ZERO) > 0) {
            summary = "Despliegue dentro del Free Tier · $" + consumed.setScale(2, RoundingMode.HALF_UP)
                    + " absorbido por créditos · quedan $" + remaining.setScale(2, RoundingMode.HALF_UP)
                    + " USD y " + daysRemaining + " días";
        } else {
            summary = "Despliegue 100% dentro del Free Tier · $0 cobrado · quedan $"
                    + remaining.setScale(2, RoundingMode.HALF_UP) + " USD y " + daysRemaining + " días";
        }

        return new FreeTierStatus(
                endsOn,
                daysRemaining,
                "$" + starting.setScale(2, RoundingMode.HALF_UP) + " USD",
                "$" + consumed.setScale(4, RoundingMode.HALF_UP) + " USD",
                "$" + remaining.setScale(2, RoundingMode.HALF_UP) + " USD",
                "$" + outOfPocket.setScale(2, RoundingMode.HALF_UP) + " USD",
                "$" + listConsumed.setScale(4, RoundingMode.HALF_UP) + " USD",
                "$" + freeTierSaved.setScale(4, RoundingMode.HALF_UP) + " USD",
                summary,
                OffsetDateTime.now(ZoneOffset.UTC)
        );
    }

    /** "$1,2345 USD" → 1.2345. */
    private static BigDecimal parseUsd(String formatted) {
        if (formatted == null) return BigDecimal.ZERO;
        String cleaned = formatted.replace("$", "").replace("USD", "").replace(",", ".").trim();
        try { return new BigDecimal(cleaned); }
        catch (Exception e) { return BigDecimal.ZERO; }
    }
}
