package com.settlex.engine;

import com.settlex.config.SettleXProperties;
import com.settlex.dto.OptimizationStrategy;
import com.settlex.dto.Settlement;
import com.settlex.dto.SettlementPlan;
import com.settlex.dto.Transaction;
import com.settlex.engine.support.BitmaskPartitionSolver;
import com.settlex.engine.support.LedgerNormalizer;
import com.settlex.engine.support.PlanAssembler;
import com.settlex.engine.support.Position;
import com.settlex.engine.support.SettlementProblem;
import com.settlex.exception.UnsupportedStrategyException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Exact minimum-transfer settlement via maximum zero-sum partitioning.
 *
 * <p>Where {@link GreedyPriorityQueueEngine} pairs exposures greedily and hopes for the best,
 * this engine first discovers the ledger's <em>independent islands</em> and only then settles.
 * Splitting the work correctly is what removes the transfers greedy cannot see.</p>
 *
 * <p><strong>Method.</strong></p>
 * <ol>
 *   <li>Net every participant and discard those at exactly zero.</li>
 *   <li>For {@code N <= 16}, run {@link BitmaskPartitionSolver} to find the maximum number
 *       {@code k} of disjoint zero-sum subsets — an exact, NP-hard subpartition solved in
 *       {@code O(3^N / 2)} time.</li>
 *   <li>Settle each island independently with the greedy routine. An island of size
 *       {@code m} costs exactly {@code m - 1} transfers, so the total is
 *       {@code Σ(m_i - 1) = N - k}.</li>
 * </ol>
 *
 * <p><strong>Optimality.</strong> Within a zero-sum island, every transfer discharges at
 * least one participant and the last discharges two, so no solution can use fewer than
 * {@code m - 1} transfers. Since islands are independent, the bound sums: {@code N - k} is a
 * true lower bound on <em>every</em> settlement of this ledger, and step 3 attains it.
 * The result is therefore provably minimal, not merely better than the heuristic.</p>
 *
 * <p><strong>Degradation.</strong> For {@code N > 16} the bitmask state space exceeds what
 * is tractable per request, so the engine transparently falls back to the greedy heuristic
 * and sets {@code isOptimalFallback = true}. Optimality is then reported as unproven
 * rather than claimed.</p>
 */
@Component
public class OptimalSubsetPartitionEngine implements SettlementEngine {

    /** Reported as {@code algorithmUsed} on a successful exact run. */
    public static final String ALGORITHM_NAME = "Optimal Zero-Sum Subpartition (Bitmask DP)";

    /** Reported when the participant count forced a fallback to the greedy heuristic. */
    public static final String FALLBACK_ALGORITHM_NAME =
            "Optimal Zero-Sum Subpartition (Bitmask DP) -> Greedy Priority-Queue Heuristic [N>limit]";

    private final SettleXProperties properties;

    public OptimalSubsetPartitionEngine(SettleXProperties properties) {
        this.properties = properties;
    }

    @Override
    public OptimizationStrategy supportedStrategy() {
        return OptimizationStrategy.OPTIMAL_SUBSET;
    }

    @Override
    public String name() {
        return ALGORITHM_NAME;
    }

    @Override
    public SettlementPlan minimize(List<Transaction> transactions, OptimizationStrategy strategy) {
        if (strategy != supportedStrategy()) {
            throw new UnsupportedStrategyException(ALGORITHM_NAME, String.valueOf(strategy),
                    supportedStrategy().name());
        }

        long startNanos = System.nanoTime();
        SettlementProblem problem = LedgerNormalizer.normalize(transactions);

        List<Position> active = problem.positions();
        int participantCount = active.size();
        int exactLimit = properties.engine().exactSolverMaxParticipants();

        List<List<Position>> islands;
        boolean fellBack;
        int lowerBound;

        if (BitmaskPartitionSolver.isWithinExactLimit(participantCount, exactLimit)) {
            BitmaskPartitionSolver.Partition partition = BitmaskPartitionSolver.solve(active);
            islands = partition.groups();
            lowerBound = partition.theoreticalMinimum();
            fellBack = false;
        } else {
            // Beyond the bitmask limit: treat the whole ledger as one island and let the
            // greedy routine handle it, which still yields the N-1 bound.
            islands = List.of(active);
            lowerBound = -1;
            fellBack = true;
        }

        /*
         * Settle each island strictly on its own. Merging islands before settling would
         * silently degrade this engine into the greedy heuristic, because greedy would then be
         * free to route money across an island boundary that should never be crossed.
         */
        List<Settlement> settlements = new ArrayList<>();
        List<List<String>> islandNames = new ArrayList<>(islands.size());

        for (List<Position> island : islands) {
            List<Position> islandCreditors = new ArrayList<>(island.size());
            List<Position> islandDebtors = new ArrayList<>(island.size());
            List<String> names = new ArrayList<>(island.size());

            for (Position position : island) {
                names.add(position.participant());
                if (position.isCreditor()) {
                    islandCreditors.add(position);
                } else {
                    islandDebtors.add(position);
                }
            }

            settlements.addAll(GreedyPriorityQueueEngine.settleGroup(islandCreditors, islandDebtors));
            islandNames.add(List.copyOf(names));
        }

        long elapsedNanos = System.nanoTime() - startNanos;

        /*
         * The DP-derived bound is a theorem about this ledger, so when the exact path ran it
         * must agree with what the greedy inner routine produced. A mismatch would mean the
         * two engines disagree about the same input, which must never pass silently.
         */
        if (!fellBack && settlements.size() != lowerBound) {
            throw new IllegalStateException(
                    "Exact solver produced " + settlements.size() + " transfers but the DP "
                            + "proves the minimum is " + lowerBound);
        }

        return PlanAssembler.assemble(problem, settlements, elapsedNanos,
                fellBack ? FALLBACK_ALGORITHM_NAME : ALGORITHM_NAME,
                islands.size(), lowerBound, fellBack, islandNames, !fellBack);
    }
}