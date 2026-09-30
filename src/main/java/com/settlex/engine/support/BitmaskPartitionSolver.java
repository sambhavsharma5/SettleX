package com.settlex.engine.support;

import java.util.ArrayList;
import java.util.List;

/**
 * Exact solver for the NP-hard <em>maximum zero-sum partition</em> problem.
 *
 * <p><strong>The problem.</strong> Given {@code N} participants with net balances summing to
 * zero, partition them into the maximum possible number {@code k} of pairwise disjoint
 * subsets, each of which individually sums to zero. Every such subset is an independent
 * island: money never needs to cross between islands.</p>
 *
 * <p><strong>Why it matters.</strong> Settling an island of size {@code m} requires at
 * least {@code m - 1} transfers — {@code m} settlements would be needed only if nothing
 * ever nets to zero, which cannot happen when the island balances. Summing over islands,
 * the provable lower bound on any settlement is:</p>
 *
 * <pre>{@code  minimum transfers = Σ (|S_i| - 1) = N - k }</pre>
 *
 * <p>so maximising {@code k} minimises the transfer count. Since {@code k >= 1} always,
 * this bound is never worse than the greedy heuristic's {@code N - 1}.</p>
 *
 * <p><strong>The algorithm.</strong> Bitmask dynamic programming. For a subset {@code mask}
 * whose balances sum to zero, {@code dp[mask]} is the maximum number of zero-sum groups it
 * decomposes into:</p>
 *
 * <pre>{@code
 *   dp[0]     = 0
 *   dp[mask]  = 1 + max( dp[mask \ sub] )   over proper submasks sub of mask
 *                                          that contain mask's lowest set bit
 *                                          and satisfy sum[sub] == 0
 * }</pre>
 *
 * <p>Requiring {@code sub} to contain the lowest set bit is the standard symmetry break: it
 * prevents counting the same partition in many orders, and because {@code sub} is a strict
 * subset containing a bit below every other bit of the remainder, {@code mask \ sub < mask}
 * numerically — so a single ascending pass over masks suffices, with no recursion and no
 * memoisation overhead.</p>
 *
 * <p><strong>Complexity.</strong> {@code O(3^N / 2)} time and {@code O(2^N)} space. At
 * {@code N = 16} that is roughly 21.5 million inner iterations and two 65 536-element
 * tables, which runs comfortably inside a request budget. {@code N > 16} is refused; the
 * caller degrades to the greedy heuristic.</p>
 *
 * <p>The DP is exact and deterministic: given the same input it returns the same partition,
 * because mask iteration is ascending and ties keep the first candidate found.</p>
 */
public final class BitmaskPartitionSolver {

    /**
     * Default participant ceiling for the exact solver. {@code 2^16 = 65 536} states is the
     * practical ceiling for an in-memory request-scoped solver.
     */
    public static final int DEFAULT_MAX_PARTICIPANTS = 16;

    /** Absolute ceiling, guarding against {@code 1 << n} overflow. Bit 31 would go negative. */
    private static final int HARD_MATHEMATICAL_LIMIT = 30;

    private BitmaskPartitionSolver() {
        throw new AssertionError("Utility class");
    }

    /**
     * Outcome of an exact partition run.
     *
     * @param groups          the discovered zero-sum subsets; every element of {@code groups}
     *                        sums to zero and they are pairwise disjoint and exhaustive
     * @param groupCount      {@code k}, the maximum number of zero-sum subsets
     * @param theoreticalMinimum  {@code N - k}, the provable minimum transfer count
     * @param statesEvaluated number of bitmask states materialised, {@code 2^N}
     * @param transitionsExamined number of candidate submasks considered across all states
     */
    public record Partition(
            List<List<Position>> groups,
            int groupCount,
            int theoreticalMinimum,
            int statesEvaluated,
            long transitionsExamined
    ) {
        public Partition {
            groups = List.copyOf(groups);
        }
    }

    /** {@code true} when the exact solver is willing to attempt this participant count. */
    public static boolean isWithinExactLimit(int participantCount, int maxParticipants) {
        return participantCount >= 0
                && participantCount <= maxParticipants
                && participantCount <= HARD_MATHEMATICAL_LIMIT;
    }

    /**
     * Finds a maximum-cardinality partition of {@code positions} into zero-sum subsets.
     *
     * @param positions participants with non-zero net balances; their balances must sum to zero
     * @return the optimal partition together with its accounting
     * @throws IllegalArgumentException if the participant count is beyond the mathematical
     *                                  limit, or the balances do not sum to zero
     */
    public static Partition solve(List<Position> positions) {
        int n = positions.size();
        if (n == 0) {
            return new Partition(List.of(), 0, 0, 1, 0L);
        }
        if (n > HARD_MATHEMATICAL_LIMIT) {
            throw new IllegalArgumentException(
                    "Bitmask solver supports at most " + HARD_MATHEMATICAL_LIMIT
                            + " participants, received " + n);
        }

        long[] balances = new long[n];
        for (int i = 0; i < n; i++) {
            balances[i] = positions.get(i).balance();
        }

        int stateCount = 1 << n;
        int fullMask = stateCount - 1;

        // sum[mask]: exact net balance of the participants selected by mask.
        long[] sum = new long[stateCount];
        for (int mask = 1; mask < stateCount; mask++) {
            int lowBit = mask & -mask;
            int index = Integer.numberOfTrailingZeros(lowBit);
            sum[mask] = sum[mask ^ lowBit] + balances[index];
        }

        if (sum[fullMask] != 0L) {
            throw new IllegalArgumentException(
                    "Cannot partition a ledger whose balances do not sum to zero (residual "
                            + MoneyMath.formatMinorUnits(sum[fullMask]) + ")");
        }

        int[] dp = new int[stateCount];
        int[] choice = new int[stateCount];
        long transitions = 0L;

        for (int mask = 1; mask < stateCount; mask++) {
            // A mask with non-zero balance cannot itself be a union of zero-sum groups.
            if (sum[mask] != 0L) {
                continue;
            }

            // The whole mask is always a valid single group, since its sum is zero.
            int best = 1;
            int bestSub = mask;

            // Symmetry break: only consider groups containing the lowest set bit.
            int lowBit = mask & -mask;
            int rest = mask ^ lowBit;
            for (int sub = rest; sub != 0; sub = (sub - 1) & rest) {
                int group = sub | lowBit;
                if (sum[group] != 0L) {
                    continue;
                }
                int remainder = dp[mask ^ group];
                if (remainder < 0) {
                    continue;
                }
                int candidate = remainder + 1;
                if (candidate > best) {
                    best = candidate;
                    bestSub = group;
                }
            }
            transitions += Long.bitCount(rest);

            dp[mask] = best;
            choice[mask] = bestSub;
        }

        List<List<Position>> groups = new ArrayList<>(dp[fullMask]);
        int remaining = fullMask;
        while (remaining != 0) {
            int group = choice[remaining];
            if (group == 0) {
                throw new IllegalStateException(
                        "Partition reconstruction stalled at mask " + remaining);
            }
            List<Position> members = new ArrayList<>(Integer.bitCount(group));
            for (int i = 0; i < n; i++) {
                if ((group & (1 << i)) != 0) {
                    members.add(positions.get(i));
                }
            }
            groups.add(members);
            remaining ^= group;
        }

        int groupCount = dp[fullMask];
        return new Partition(groups, groupCount, n - groupCount, stateCount, transitions);
    }

    /**
     * Computes only {@code k}, the maximum number of zero-sum subsets, without materialising
     * the partition. Used by the greedy engine to decide whether its own heuristic answer
     * happened to be provably minimal.
     */
    public static int maximumZeroSumSubsetCount(List<Position> positions) {
        return solve(positions).groupCount();
    }
}