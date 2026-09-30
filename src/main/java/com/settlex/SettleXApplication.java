package com.settlex;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * SettleX — Algorithmic Multi-Party Debt Optimization Engine.
 *
 * <p>Serves both the REST API under {@code /api/v1/settlements} and the interactive dashboard
 * at the root path, from a single self-contained process.</p>
 */
@SpringBootApplication
public class SettleXApplication {

    public static void main(String[] args) {
        SpringApplication.run(SettleXApplication.class, args);
    }
}