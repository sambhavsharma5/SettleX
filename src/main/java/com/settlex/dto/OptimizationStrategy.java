package com.settlex.dto;

/**
 * Selects which settlement algorithm the service should apply.
 *
 * <p>Resolved via an exhaustive {@code switch} pattern match so that adding a strategy
 * becomes a compile-time obligation rather than a silent runtime default.</p>
 */
public enum OptimizationStrategy {

    /**
     * Heuristic: repeatedly match the largest debtor against the largest creditor.
     * O(N log N). Always produces at most N-1 transfers but is not guaranteed minimal.
     */
    GREEDY,

    /**
     * Exact: decompose the ledger into the maximum number of disjoint zero-sum subsets
     * (NP-hard, solved by bitmask dynamic programming for N &lt;= 16) and greedily settle
     * each subset, attaining the true minimum transfer count of N - k.
     * Falls back to {@link #GREEDY} when the participant count exceeds the exact-solver limit.
     */
    OPTIMAL_SUBSET
}