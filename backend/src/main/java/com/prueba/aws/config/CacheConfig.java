package com.prueba.aws.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Configuration
public class CacheConfig {
    private final AppProperties props;

    public CacheConfig(AppProperties props) {
        this.props = props;
    }

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager mgr = new CaffeineCacheManager();
        mgr.setCacheNames(List.of("athena", "exercises", "data-quality", "kpis",
                "docs", "cost-today", "cost-mtd", "metered-cost", "free-tier"));
        mgr.setCaffeine(Caffeine.newBuilder()
                .expireAfterWrite(props.getCache().getAthenaTtlSeconds(), TimeUnit.SECONDS)
                .maximumSize(200));
        return mgr;
    }
}
