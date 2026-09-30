package com.settlex.engine;

import com.settlex.config.SettleXProperties;
import com.settlex.dto.OptimizationStrategy;
import com.settlex.dto.Settlement;
import com.settlex.dto.SettlementPlan;
import com.settlex.dto.Transaction;
import com.settlex.engine.support.BitmaskPartitionSolver;
import com.settlex.engine.support.LedgerNormalizer;
import com.settlex.engine.support.MoneyMath;
import com.settlex.engine.support.PlanAssembler;
import com.settlex.engine.support.Position;
import com.settlex.engine.support.SettlementProblem;
import com.settlex.exception.UnsupportedStrategyException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Greedy max-heap settlement: repeatedly match the largest debtor against the largest
 * creditor until every exposure is extinguished.
 *
 * <p><strong>Algorithm.</strong> Net balances are split into two {@link PriorityQueue}s that
 * behave as max-heaps (a {@code PriorityQueue} is a min-heap, so the comparator is reversed).
 * Each round pops one creditor and one debtor and transfers the lesser of the two
 * exposures, re-queueing whichever side survives.</p>
 *
 * <p><strong>Guarantees.</strong> Every round fully discharges at least one participant, so
 * the run terminates in at most {@code N - 1} transfers for {@code N} participants — the
 * classical result that any zero-sum ledger can be settled with at most {@code N - 1}
 * transactions. Time complexity is {@code O(N log N)}: {@code O(N)} rounds, each doing a
 * constant number of {@code O(log N)} heap operations.</p>
 *
 * <p><strong>What it does not guarantee.</strong> Optimality. Greedy is blind to the island
 * structure: given {@code [100, 100, -100, -100]} it settles 200 in two transfers where
 * 100 + 100 is achievable by pairing each debtor with one creditor. This engine therefore
 * reports {@code isMathematicallyOptimal} only after verifying its answer against the exact
 * bound {@code N - k} — and only when {@code N} is small enough for that bound to be
 * computable.</p>
 *
 * <p>The settlement core is exposed as {@link #settleGroup} so
 * {@link OptimalSubsetPartitionEngine} can apply the same deterministic inner routine
 * inside each island it discovers.</p>
 */
@Component
public class GreedyPriorityQueueEngine implements SettlementEngine {

    /** Reported as {@code algorithmUsed}. */
    public static final String ALGORITHM_NAME = "Greedy Priority-Queue Heuristic";

    private final SettleXProperties properties;

    public GreedyPriorityQueueEngine(SettleXProperties properties) {
        this.properties = properties;
    }

    @Override
    public OptimizationStrategy supportedStrategy() {
        return OptimizationStrategy.GREEDY;
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
        List<Settlement> settlements = settleGroup(problem.creditors(), problem.debtors());
        long elapsedNanos = System.nanoTime() - startNanos;

        /*
         * Optimality is a claim about optimality, not about intent, so it is verified rather
         * than assumed. When the ledger is small enough, the exact bitmask solver supplies
         * the lower bound N - k; the heuristic is optimal precisely when it matched it.
         */
        int participantCount = problem.activeParticipantCount();
        int exactLimit = properties.engine().exactSolverMaxParticipants();

        int lowerBound = -1;
        int groupCount = countDoubleClears(problem.creditors(), problem.debtors(), settlements.size());
        boolean provablyOptimal;

        if (BitmaskPartitionSolver.isWithinExactLimit(participantCount, exactLimit)) {
            int k = BitmaskPartitionSolver.maximumZeroSumSubsetCount(problem.positions());
            lowerBound = participantCount - k;
            groupCount = k;
            provablyOptimal = settlements.size() == lowerBound;
        } else {
            // Beyond the exact solver's reach the minimum transfer count is unknown, so the
            // heuristic is explicitly reported as unproven rather than optimistically claimed.
            provablyOptimal = false;
        }

        return PlanAssembler.assemble(problem, settlements, elapsedNanos, ALGORITHM_NAME,
                groupCount, lowerBound, false, List.of(), provablyOptimal);
    }

    /**
     * The deterministic greedy settlement core.
     *
     * @param creditors participants with a positive net balance
     * @param debtors   participants with a negative net balance
     * @return transfers that fully extinguish every exposure; empty when both sides are empty
     * @throws IllegalStateException if the inputs are not balanced, which would indicate a
     *                               corruption upstream rather than a caller error
     */
    public static List<Settlement> settleGroup(List<Position> creditors, List<Position> debtors) {
        if (creditors.isEmpty() && debtors.isEmpty()) {
            return List.of();
        }

        // Fail fast on mis-signed input. This guard is load-bearing, not defensive noise: if a
        // creditor-side position carried a non-positive balance the transfer amount would be 0,
        // reduce(0) would return the very same position, and the loop below would re-queue it
        // forever, appending zero-value settlements until the heap was exhausted.
        requireCorrectlySigned(creditors, true);
        requireCorrectlySigned(debtors, false);

        // A PriorityQueue is a min-heap; reversing the total order turns it into a max-heap.
        PriorityQueue<Position> creditorHeap = new PriorityQueue<>(Position.BY_MAGNITUDE_DESC);
        PriorityQueue<Position> debtorHeap = new PriorityQueue<>(Position.BY_MAGNITUDE_DESC);
        creditorHeap.addAll(creditors);
        debtorHeap.addAll(debtors);

        List<Settlement> settlements = new ArrayList<>(creditors.size() + debtors.size() - 1);

        while (!creditorHeap.isEmpty() && !debtorHeap.isEmpty()) {
            Position creditor = creditorHeap.poll();
            Position debtor = debtorHeap.poll();

            // Transfer the smaller of the two exposures; the larger side survives.
            long amount = Math.min(creditor.credit(), debtor.debt());

            settlements.add(new Settlement(
                    debtor.participant(),
                    creditor.participant(),
                    MoneyMath.fromMinorUnits(amount)));

            Position residualCreditor = creditor.reduce(amount);
            Position residualDebtor = debtor.reduce(amount);
            if (residualCreditor != null) {
                creditorHeap.add(residualCreditor);
            }
            if (residualDebtor != null) {
                debtorHeap.add(residualDebtor);
            }
        }

        if (!creditorHeap.isEmpty() || !debtorHeap.isEmpty()) {
            throw new IllegalStateException(
                    "Greedy settlement terminated with unresolved exposure: "
                            + creditorHeap.size() + " creditor(s) and "
                            + debtorHeap.size() + " debtor(s) remain; the group is unbalanced");
        }

        return List.copyOf(settlements);
    }

    /**
     * Rejects positions that arrived on the wrong side of the settlement.
     *
     * @param side         the creditor or debtor side about to be settled
     * @param creditorSide {@code true} for the creditor side, {@code false} for the debtor side
     * @throws IllegalArgumentException if any position's sign contradicts its side
     */
    private static void requireCorrectlySigned(List<Position> side, boolean creditorSide) {
        // reversed() walks the caller's list back to front so the reported offenders appear in
        // the same order the caller supplied them, not in heap order.
        List<Position> misplaced = side.reversed().stream()
                .filter(position -> creditorSide != position.isCreditor())
                .toList();

        if (!misplaced.isEmpty()) {
            throw new IllegalArgumentException(
                    "Greedy settlement received " + misplaced.size() + " position(s) on the "
                            + (creditorSide ? "creditor" : "debtor") + " side requiring "
                            + (creditorSide ? "a positive" : "a negative")
                            + " balance: " + misplaced);
        }
    }

    /**
     * Number of rounds that simultaneously discharged a debtor and a creditor.
     *
     * <p>Each round discharges at least one participant and the final round discharges two,
     * so for {@code N} active participants and {@code m} transfers the number of
     * double-discharge rounds is exactly {@code N - m}. That is the group count the greedy
     * run implicitly achieved, and it is directly comparable to {@code k} from the exact
     * solver.</p>
     */
    static int countDoubleClears(List<Position> creditors, List<Position> debtors, int transfers) {
        int active = creditors.size() + debtors.size();
        return active - transfers;
    }
}