package com.prueba.aws.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app")
public class AppProperties {
    private final Aws aws = new Aws();
    private final Github github = new Github();
    private final Cors cors = new Cors();
    private final Report report = new Report();
    private final Cache cache = new Cache();
    private final Credits credits = new Credits();
    private String editToken;

    public Aws getAws() { return aws; }
    public Github getGithub() { return github; }
    public Cors getCors() { return cors; }
    public Report getReport() { return report; }
    public Cache getCache() { return cache; }
    public Credits getCredits() { return credits; }
    public String getEditToken() { return editToken; }
    public void setEditToken(String editToken) { this.editToken = editToken; }

    public static class Aws {
        private String region;
        private String publicBucket;
        private String athenaResultsBucket;
        private String athenaWorkgroup;
        private String athenaDatabase;

        public String getRegion() { return region; }
        public void setRegion(String v) { this.region = v; }
        public String getPublicBucket() { return publicBucket; }
        public void setPublicBucket(String v) { this.publicBucket = v; }
        public String getAthenaResultsBucket() { return athenaResultsBucket; }
        public void setAthenaResultsBucket(String v) { this.athenaResultsBucket = v; }
        public String getAthenaWorkgroup() { return athenaWorkgroup; }
        public void setAthenaWorkgroup(String v) { this.athenaWorkgroup = v; }
        public String getAthenaDatabase() { return athenaDatabase; }
        public void setAthenaDatabase(String v) { this.athenaDatabase = v; }
    }

    public static class Github {
        private String token;
        private String repo;
        private String branch;
        public String getToken() { return token; }
        public void setToken(String v) { this.token = v; }
        public String getRepo() { return repo; }
        public void setRepo(String v) { this.repo = v; }
        public String getBranch() { return branch; }
        public void setBranch(String v) { this.branch = v; }
    }

    public static class Cors {
        private String allowedOrigins;
        public String getAllowedOrigins() { return allowedOrigins; }
        public void setAllowedOrigins(String v) { this.allowedOrigins = v; }
        public String[] getAllowedOriginsArray() {
            return allowedOrigins == null ? new String[0] : allowedOrigins.split("\\s*,\\s*");
        }
    }

    public static class Report {
        private boolean regenerateOnStartup = true;
        private String cron;
        public boolean isRegenerateOnStartup() { return regenerateOnStartup; }
        public void setRegenerateOnStartup(boolean v) { this.regenerateOnStartup = v; }
        public String getCron() { return cron; }
        public void setCron(String v) { this.cron = v; }
    }

    public static class Cache {
        private long athenaTtlSeconds = 300;
        public long getAthenaTtlSeconds() { return athenaTtlSeconds; }
        public void setAthenaTtlSeconds(long v) { this.athenaTtlSeconds = v; }
    }

    /** Saldo inicial de créditos AWS otorgados a la cuenta (USD). El consumo se descuenta de aquí; el cobro real a la tarjeta es $0 mientras queden créditos. */
    public static class Credits {
        private java.math.BigDecimal startingUsd = new java.math.BigDecimal("120.00");
        /** Fecha de fin de la ventana Free Tier 12 meses (cuenta nueva). Después de esta fecha el acceso gratuito termina. */
        private java.time.LocalDate freeTierEndsOn = java.time.LocalDate.parse("2026-10-17");

        public java.math.BigDecimal getStartingUsd() { return startingUsd; }
        public void setStartingUsd(java.math.BigDecimal v) { this.startingUsd = v; }
        public java.time.LocalDate getFreeTierEndsOn() { return freeTierEndsOn; }
        public void setFreeTierEndsOn(java.time.LocalDate v) { this.freeTierEndsOn = v; }
    }
}
