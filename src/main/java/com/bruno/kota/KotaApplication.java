package com.bruno.kota;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class KotaApplication {

    public static void main(String[] args) {
        SpringApplication.run(KotaApplication.class, args);
    }

}