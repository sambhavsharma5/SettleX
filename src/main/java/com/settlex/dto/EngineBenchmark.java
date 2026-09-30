package com.settlex.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * One engine's contribution to a {@code /benchmark} comparison.
 *
 * @param algorithmUsed               engine identifier
 * @param optimizedTransactionCount   transfers produced by this engine
 * @param executionTimeNs             time spent inside the engine, excluding scheduler jitter
 * @param totalCashFlowAfter          sum of produced transfer amounts
 * @param mathematicallyOptimal       whether this run provably hit the transfer-count lower bound
 * @param optimalFallback             whether the exact solver degraded to the greedy heuristic
 * @param independentSubsetCount      zero-sum components discovered ({@code k})
 * @param settlements                 the produced payment instructions
 */
public record EngineBenchmark(
        String algorithmUsed,
        int optimizedTransactionCount,
        long executionTimeNs,
        BigDecimal totalCashFlowAfter,
        boolean mathematicallyOptimal,
        boolean optimalFallback,
        int independentSubsetCount,
        List<Settlement> settlements
) {

    /** Engine time in microseconds, 2dp, for human-readable comparison. */
    public double executionTimeMicros() {
        return BigDecimal.valueOf(executionTimeNs)
                .divide(BigDecimal.valueOf(1_000L))
                .setScale(2, java.math.RoundingMode.HALF_EVEN)
                .doubleValue();
    }
}