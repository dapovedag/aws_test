package com.prueba.aws.service;

import com.prueba.aws.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ReportScheduler {
    private static final Logger log = LoggerFactory.getLogger(ReportScheduler.class);
    private final ReportService report;
    private final AppProperties props;

    public ReportScheduler(ReportService report, AppProperties props) {
        this.report = report;
        this.props = props;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        if (props.getReport().isRegenerateOnStartup()) {
            try {
                log.info("PDF regen on startup → start");
                report.regenerate();
            } catch (Exception e) {
                log.warn("PDF startup regen failed (continuing): {}", e.getMessage());
            }
        }
    }

    @Scheduled(cron = "${app.report.cron:0 0 6 * * *}")
    public void scheduledRebuild() {
        try {
            log.info("PDF scheduled regen");
            report.regenerate();
        } catch (Exception e) {
            log.warn("PDF scheduled regen failed: {}", e.getMessage());
        }
    }
}
