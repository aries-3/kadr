package com.teacode.kazakov.kadr.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class KadrApplication {

    public static void main(String[] args) {
        SpringApplication.run(KadrApplication.class, args);
    }
}
