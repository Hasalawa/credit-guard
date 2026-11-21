package com.creditguard;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class CreditGuardApplication {
    public static void main(String[] args) {
        SpringApplication.run(CreditGuardApplication.class, args);
    }
}
