package com.prueba.aws.service;

import com.prueba.aws.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

@Service
public class S3Service {
    private static final Logger log = LoggerFactory.getLogger(S3Service.class);

    private final S3Client s3;
    private final AppProperties props;

    public S3Service(S3Client s3, AppProperties props) {
        this.s3 = s3;
        this.props = props;
    }

    public String publicUrl(String key) {
        return "https://%s.s3.amazonaws.com/%s".formatted(props.getAws().getPublicBucket(), key);
    }

    public String uploadPublic(String key, byte[] body, String contentType) {
        s3.putObject(PutObjectRequest.builder()
                .bucket(props.getAws().getPublicBucket())
                .key(key)
                .contentType(contentType)
                .cacheControl("public, max-age=60")
                .build(), RequestBody.fromBytes(body));
        log.info("Uploaded {} bytes → s3://{}/{}", body.length, props.getAws().getPublicBucket(), key);
        return publicUrl(key);
    }

    public String getTextOrNull(String bucket, String key) {
        try (InputStream in = s3.getObject(b -> b.bucket(bucket).key(key))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            in.transferTo(out);
            return out.toString(StandardCharsets.UTF_8);
        } catch (NoSuchKeyException e) {
            return null;
        } catch (Exception e) {
            log.warn("S3 get failed for s3://{}/{}: {}", bucket, key, e.getMessage());
            return null;
        }
    }
}
