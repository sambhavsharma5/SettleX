package com.settlex.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Wires the operational beans the engine layer depends on.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SettleXProperties.class)
public class EngineConfig {

    /**
     * Dedicated pool for {@code /benchmark}, which genuinely runs both engines at once.
     *
     * <p>Kept separate from the request pool on purpose: benchmark work is CPU-bound, so it
     * must not be allowed to starve the tomcat threads that serve ordinary optimization
     * requests.</p>
     */
    @Bean(name = "benchmarkExecutor", destroyMethod = "shutdown")
    public Executor benchmarkExecutor(SettleXProperties properties) {
        SettleXProperties.Benchmark config = properties.benchmark();

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(config.corePoolSize());
        executor.setMaxPoolSize(config.maxPoolSize());
        executor.setQueueCapacity(config.queueCapacity());
        executor.setThreadNamePrefix("settlex-bench-");
        // Shed load rather than let a burst of benchmarks accumulate unboundedly.
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(15);
        executor.initialize();
        return executor;
    }
}