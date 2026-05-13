package com.mirboard;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class MirboardApplication {

    public static void main(String[] args) {
        SpringApplication.run(MirboardApplication.class, args);
    }
}
