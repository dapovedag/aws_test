package com.prueba.aws.tools;

import com.prueba.aws.config.AppProperties;
import com.prueba.aws.service.FreeTierService;
import com.prueba.aws.service.HtmlPdfService;
import com.prueba.aws.service.MeteredCostService;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Genera el PDF localmente sin levantar Spring ni AWS.
 * - Las llamadas a Athena/Divisas/DataModel fallan por NPE → fallbacks offline.
 * - FreeTierService SÍ se construye con AppProperties + MeteredCostService(null clients)
 *   para que el preview muestre los valores REALES (días, créditos consumidos
 *   por Secrets Manager + Glue Crawlers, restantes, out-of-pocket).
 */
public class PdfPreview {
    public static void main(String[] args) throws Exception {
        AppProperties props = new AppProperties();
        MeteredCostService cost = new MeteredCostService(null, null, null, null, null, props);
        FreeTierService freeTier = new FreeTierService(props, cost);

        HtmlPdfService svc = new HtmlPdfService(null, null, null, null, null, freeTier);
        byte[] pdf = svc.buildPdf();
        Path out = Path.of(args.length > 0 ? args[0] : "pdf-preview.pdf");
        Files.write(out, pdf);
        System.out.println("PDF escrito: " + out.toAbsolutePath() + "  (" + pdf.length + " bytes)");
    }
}
