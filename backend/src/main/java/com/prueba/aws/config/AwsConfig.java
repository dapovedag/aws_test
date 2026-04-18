package com.prueba.aws.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.athena.AthenaClient;
import software.amazon.awssdk.services.costexplorer.CostExplorerClient;
import software.amazon.awssdk.services.glue.GlueClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;

@Configuration
public class AwsConfig {
    private final AppProperties props;

    public AwsConfig(AppProperties props) {
        this.props = props;
    }

    private Region region() {
        return Region.of(props.getAws().getRegion());
    }

    @Bean
    public S3Client s3Client() {
        return S3Client.builder()
                .region(region())
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }

    @Bean
    public AthenaClient athenaClient() {
        return AthenaClient.builder()
                .region(region())
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }

    @Bean
    public GlueClient glueClient() {
        return GlueClient.builder()
                .region(region())
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }

    @Bean
    public SecretsManagerClient secretsManagerClient() {
        return SecretsManagerClient.builder()
                .region(region())
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }

    /** Cost Explorer es global pero requiere region fija us-east-1. */
    @Bean
    public CostExplorerClient costExplorerClient() {
        return CostExplorerClient.builder()
                .region(software.amazon.awssdk.regions.Region.US_EAST_1)
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }

    @Bean
    public software.amazon.awssdk.services.ec2.Ec2Client ec2Client() {
        return software.amazon.awssdk.services.ec2.Ec2Client.builder()
                .region(region())
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }

    @Bean
    public software.amazon.awssdk.services.rds.RdsClient rdsClient() {
        return software.amazon.awssdk.services.rds.RdsClient.builder()
                .region(region())
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }

    @Bean
    public software.amazon.awssdk.services.cloudwatch.CloudWatchClient cloudWatchClient() {
        return software.amazon.awssdk.services.cloudwatch.CloudWatchClient.builder()
                .region(region())
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }
}
