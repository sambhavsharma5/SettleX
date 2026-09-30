package com.settlex.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Type-safe binding for the {@code settlex.*} configuration namespace.
 *
 * <p>Bound via constructor binding, so the canonical constructor is the single validation
 * point. Nested value objects are null-guarded because Spring Boot passes {@code null}
 * rather than a default when a whole section is absent.</p>
 *
 * @param engine    solver tuning
 * @param benchmark concurrency settings for the dual-engine comparison endpoint
 */
@ConfigurationProperties(prefix = "settlex")
public record SettleXProperties(Engine engine, Benchmark benchmark) {

    /** Applied when the {@code settlex.engine} section is absent entirely. */
    public static final int DEFAULT_EXACT_SOLVER_MAX_PARTICIPANTS = 16;

    public SettleXProperties {
        engine = engine == null
                ? new Engine(DEFAULT_EXACT_SOLVER_MAX_PARTICIPANTS)
                : engine;
        benchmark = benchmark == null ? new Benchmark(2, 8, 256, 30_000L) : benchmark;
    }

    /**
     * @param exactSolverMaxParticipants upper bound on {@code N} for which the exact bitmask
     *                                   zero-sum partition solver is attempted. Above this
     *                                   the engine falls back to the greedy heuristic.
     */
    public record Engine(
            @DefaultValue("16") int exactSolverMaxParticipants
    ) {
        public Engine {
            // 2^N states: 24 is the point where the table itself becomes unreasonable.
            if (exactSolverMaxParticipants < 1 || exactSolverMaxParticipants > 24) {
                throw new IllegalArgumentException(
                        "settlex.engine.exact-solver-max-participants must be between 1 and 24, got "
                                + exactSolverMaxParticipants);
            }
        }
    }

    /**
     * @param corePoolSize  threads available for concurrent engine execution
     * @param maxPoolSize   upper bound on the benchmark pool
     * @param queueCapacity pending benchmark requests before load is shed
     * @param timeoutMs     how long a benchmark waits for both engines before giving up
     */
    public record Benchmark(
            @DefaultValue("2") int corePoolSize,
            @DefaultValue("8") int maxPoolSize,
            @DefaultValue("256") int queueCapacity,
            @DefaultValue("30000") long timeoutMs
    ) {
        public Benchmark {
            if (corePoolSize < 1 || maxPoolSize < corePoolSize || queueCapacity < 1 || timeoutMs < 1) {
                throw new IllegalArgumentException(
                        "settlex.benchmark requires corePoolSize >= 1, maxPoolSize >= corePoolSize, "
                                + "queueCapacity >= 1 and timeoutMs >= 1");
            }
        }
    }
}