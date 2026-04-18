package com.prueba.aws.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.prueba.aws.config.AppProperties;
import com.prueba.aws.dto.Dtos.DataQualitySummary;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

@Service
public class DataQualityService {
    private static final ObjectMapper M = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final S3Service s3;
    private final AppProperties props;

    public DataQualityService(S3Service s3, AppProperties props) {
        this.s3 = s3;
        this.props = props;
    }

    @Cacheable("data-quality")
    public DataQualitySummary getSummary() {
        String json = s3.getTextOrNull(props.getAws().getPublicBucket(), "data_quality_summary.json");
        if (json == null || json.isBlank()) {
            return new DataQualitySummary(null, 0, "n/a", 0, 0, 0, 0, java.util.List.of());
        }
        try {
            return M.readValue(json, DataQualitySummary.class);
        } catch (Exception e) {
            throw new RuntimeException("data_quality_summary.json malformado: " + e.getMessage(), e);
        }
    }

    public String reportHtmlUrl() {
        return s3.publicUrl("data_quality_report.html");
    }
}
