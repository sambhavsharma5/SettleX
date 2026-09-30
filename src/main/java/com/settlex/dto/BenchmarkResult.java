package com.settlex.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Head-to-head result of running both engines concurrently over the same ledger.
 *
 * @param participantCount            distinct participants in the input ledger
 * @param initialTransactionCount     number of raw obligations supplied
 * @param netCreditorCount            participants with a positive net balance
 * @param netDebtorCount              participants with a negative net balance
 * @param settledParticipantCount     participants whose net balance is exactly zero
 * @param greedy                      the heuristic engine run
 * @param optimalSubset               the exact bitmask partition engine run
 * @param transactionCountDelta       {@code greedy - optimalSubset}; positive means the
 *                                    exact solver saved that many transfers
 * @param cashFlowDelta               {@code greedy.cashFlow - optimal.cashFlow}
 * @param fastestEngine               identifier of whichever engine had the lower engine time
 * @param speedupRatio                {@code slower / faster} of the two engine times
 * @param optimalProvedStrictlyFewer  {@code true} when the exact solver produced strictly
 *                                    fewer transfers than the greedy heuristic
 * @param notes                       short human-readable commentary for the dashboard
 */
public record BenchmarkResult(
        int participantCount,
        int initialTransactionCount,
        int netCreditorCount,
        int netDebtorCount,
        int settledParticipantCount,
        EngineBenchmark greedy,
        EngineBenchmark optimalSubset,
        int transactionCountDelta,
        BigDecimal cashFlowDelta,
        String fastestEngine,
        double speedupRatio,
        boolean optimalProvedStrictlyFewer,
        String notes
) {
}