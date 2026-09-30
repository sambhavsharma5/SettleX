package com.settlex.dto;

import com.settlex.engine.support.MoneyMath;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

/**
 * Immutable result of one optimization run.
 *
 * <p>Every currency field is exact at two decimal places ({@link RoundingMode#HALF_EVEN});
 * {@code reductionPercentage} is the only derived non-monetary figure and is rounded to
 * two decimals for stable JSON.</p>
 *
 * @param settlements                 the optimized payment instructions, possibly empty
 *                                    when every participant is already square
 * @param initialTransactionCount     number of raw obligations supplied by the client
 * @param optimizedTransactionCount   number of transfers required to settle the ledger
 * @param reductionPercentage         {@code (initial - optimized) / initial * 100}, 2dp
 * @param totalCashFlowBefore         sum of all raw obligation amounts
 * @param totalCashFlowAfter          sum of all optimized transfer amounts
 * @param executionTimeNs             wall-clock engine time in nanoseconds
 * @param algorithmUsed               human-readable algorithm identifier
 * @param netBalances                 participant to net position; positive means the
 *                                    participant is owed money (creditor)
 * @param isMathematicallyOptimal     {@code true} only when the transfer count provably
 *                                    equals the theoretical minimum for this ledger
 * @param isOptimalFallback           {@code true} when the exact solver was requested but
 *                                    the participant count exceeded the bitmask limit and
 *                                    the greedy heuristic was substituted
 * @param participantCount            distinct participants appearing in the ledger
 * @param independentSubsetCount      number {@code k} of disjoint zero-sum subsets found;
 *                                    the minimum transfer count is {@code N - k}
 * @param optimalTransactionLowerBound  the provable lower bound ({@code N - k}) when known,
 *                                    otherwise {@code -1} when out of exact-solver reach
 * @param independentSubsets          the discovered zero-sum components (names only), empty
 *                                    for the greedy strategy
 */
public record SettlementPlan(
        List<Settlement> settlements,
        int initialTransactionCount,
        int optimizedTransactionCount,
        double reductionPercentage,
        BigDecimal totalCashFlowBefore,
        BigDecimal totalCashFlowAfter,
        long executionTimeNs,
        String algorithmUsed,
        Map<String, BigDecimal> netBalances,
        boolean isMathematicallyOptimal,
        boolean isOptimalFallback,
        int participantCount,
        int independentSubsetCount,
        int optimalTransactionLowerBound,
        List<List<String>> independentSubsets
) {

    /** Zero-sum, empty, already-settled plan. */
    public static SettlementPlan settled(String algorithmUsed,
                                         int initialTransactionCount,
                                         long executionTimeNs,
                                         Map<String, BigDecimal> netBalances) {
        return new SettlementPlan(
                List.of(),
                initialTransactionCount,
                0,
                reductionPercentage(initialTransactionCount, 0),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                executionTimeNs,
                algorithmUsed,
                Map.copyOf(netBalances),
                true,
                false,
                netBalances.size(),
                0,
                0,
                List.of());
    }

    /**
     * Percentage of obligations eliminated by optimization, rounded to two decimals.
     * Returns {@code 0.0} when there is nothing to reduce, avoiding division by zero.
     */
    public static double reductionPercentage(int initial, int optimized) {
        if (initial <= 0) {
            return 0.0d;
        }
        double raw = (1.0d - ((double) optimized / (double) initial)) * 100.0d;
        return BigDecimal.valueOf(raw)
                .setScale(2, RoundingMode.HALF_EVEN)
                .doubleValue();
    }

    /** {@code executionTimeNs} rendered in microseconds for UI display. */
    public double executionTimeMicros() {
        return MoneyMath.nanosToMicros(executionTimeNs);
    }
}