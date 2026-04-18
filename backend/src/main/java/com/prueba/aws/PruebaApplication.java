package com.prueba.aws;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableCaching
@EnableScheduling
public class PruebaApplication {
    public static void main(String[] args) {
        SpringApplication.run(PruebaApplication.class, args);
    }
}
