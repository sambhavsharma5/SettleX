package com.settlex.service;

import com.settlex.config.SettleXProperties;
import com.settlex.dto.OptimizationStrategy;
import com.settlex.dto.SampleGraph;
import com.settlex.dto.SettlementPlan;
import com.settlex.dto.Transaction;
import com.settlex.engine.GreedyPriorityQueueEngine;
import com.settlex.engine.OptimalSubsetPartitionEngine;
import com.settlex.engine.support.LedgerNormalizer;
import com.settlex.engine.support.SettlementProblem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static com.settlex.support.EngineFixtures.assertLedgerFullySettled;
import static com.settlex.support.EngineFixtures.assertPlanInternallyConsistent;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks down the sample library.
 *
 * <p>The dashboard ships these to users as "known good" one-click scenarios, so each one is
 * asserted to be a genuinely balanced ledger and to produce the outcome its own
 * {@code expectedOutcome} field advertises. If an algorithm change breaks a sample, this
 * suite fails rather than leaving a misleading fixture in the UI.</p>
 */
@DisplayName("SampleDataService")
class SampleDataServiceTest {

    private final SampleDataService sampleDataService = new SampleDataService();
    private final GreedyPriorityQueueEngine greedyEngine =
            new GreedyPriorityQueueEngine(new SettleXProperties(null, null));
    private final OptimalSubsetPartitionEngine optimalEngine =
            new OptimalSubsetPartitionEngine(new SettleXProperties(null, null));

    static Stream<SampleGraph> samples() {
        return new SampleDataService().all().stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("samples")
    @DisplayName("every sample is a balanced ledger whose nets sum to exactly zero")
    void everySampleBalances(SampleGraph sample) {
        SettlementProblem problem = LedgerNormalizer.normalize(sample.transactions());

        assertThat(problem.netBalances()).isNotEmpty();

        BigDecimal net = problem.netBalances().values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(net).as("net balances must sum to zero").isEqualByComparingTo(BigDecimal.ZERO);

        long totalCreditorCredit = problem.creditors().stream()
                .mapToLong(com.settlex.engine.support.Position::credit)
                .sum();
        assertThat(problem.totalOutstandingMinor())
                .as("total debtor exposure must equal total creditor credit")
                .isEqualTo(totalCreditorCredit);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("samples")
    @DisplayName("both engines settle every sample completely and self-consistently")
    void bothEnginesSettleEverySample(SampleGraph sample) {
        for (SettlementPlan plan : List.of(
                greedyEngine.minimize(sample.transactions(), OptimizationStrategy.GREEDY),
                optimalEngine.minimize(sample.transactions(), OptimizationStrategy.OPTIMAL_SUBSET))) {

            assertPlanInternallyConsistent(plan);
            assertLedgerFullySettled(sample.transactions(), plan);

            assertThat(plan.totalCashFlowAfter())
                    .as("optimized cash flow must never exceed the original")
                    .isLessThanOrEqualTo(plan.totalCashFlowBefore());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("samples")
    @DisplayName("samples carry descriptive metadata for the UI")
    void samplesAreDocumented(SampleGraph sample) {
        assertThat(sample.id()).isNotBlank();
        assertThat(sample.name()).isNotBlank();
        assertThat(sample.description()).isNotBlank();
        assertThat(sample.expectedOutcome()).isNotBlank();
        assertThat(sample.suggestedStrategy()).isNotNull();
        assertThat(sample.transactions()).isNotEmpty();
    }

    @Test
    @DisplayName("ids are unique and lookup is case-insensitive")
    void idsAreUniqueAndLookupWorks() {
        List<SampleGraph> all = sampleDataService.all();

        assertThat(all.stream().map(SampleGraph::id).distinct())
                .as("sample ids must be unique so the dropdown can key on them")
                .hasSameSizeAs(all);

        assertThat(sampleDataService.byId("TRIP-5").id()).isEqualTo("trip-5");
    }

    // ---------------------------------------------------------------------------------------
    // Documented behaviours. Each of these encodes the claim shown in the dashboard.
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("circular-deadlock nets to zero and needs no transfers at all")
    void circularDeadlockNeedsZeroTransfers() {
        SampleGraph sample = sampleDataService.byId("circular-deadlock");

        SettlementPlan plan = optimalEngine.minimize(sample.transactions(),
                OptimizationStrategy.OPTIMAL_SUBSET);

        assertThat(plan.settlements()).isEmpty();
        assertThat(plan.reductionPercentage()).isEqualTo(100.0d);
        assertThat(plan.netBalances().values()).allSatisfy(balance ->
                assertThat(balance).isEqualByComparingTo(BigDecimal.ZERO));
    }

    @Test
    @DisplayName("disjoint-islands resolves into exactly two independent zero-sum groups")
    void disjointIslandsFindTwoGroups() {
        SampleGraph sample = sampleDataService.byId("disjoint-islands");

        SettlementPlan plan = optimalEngine.minimize(sample.transactions(),
                OptimizationStrategy.OPTIMAL_SUBSET);

        assertThat(plan.independentSubsetCount()).isEqualTo(2);
        assertThat(plan.optimizedTransactionCount()).isEqualTo(3);
        assertThat(plan.initialTransactionCount()).isEqualTo(7);
        assertThat(plan.isMathematicallyOptimal()).isTrue();
        assertThat(plan.independentSubsets()).hasSize(2);
    }

    @Test
    @DisplayName("greedy-defeated is the sample where the exact solver strictly wins")
    void greedyDefeatedIsWorthItsName() {
        SampleGraph sample = sampleDataService.byId("greedy-defeated");

        SettlementPlan exact = optimalEngine.minimize(sample.transactions(),
                OptimizationStrategy.OPTIMAL_SUBSET);
        SettlementPlan heuristic = greedyEngine.minimize(sample.transactions(),
                OptimizationStrategy.GREEDY);

        assertThat(exact.optimizedTransactionCount()).isEqualTo(3);
        assertThat(exact.independentSubsetCount()).isEqualTo(2);
        assertThat(heuristic.optimizedTransactionCount()).isEqualTo(4);
        assertThat(exact.optimizedTransactionCount())
                .as("this sample exists to prove the DP beats greedy")
                .isLessThan(heuristic.optimizedTransactionCount());
    }

    @Test
    @DisplayName("star-topology needs one transfer per creditor and honestly reports 0% reduction")
    void starTopologyHasNoReduction() {
        SampleGraph sample = sampleDataService.byId("star-topology");

        SettlementPlan plan = greedyEngine.minimize(sample.transactions(), OptimizationStrategy.GREEDY);

        assertThat(plan.settlements()).hasSize(5);
        assertThat(plan.reductionPercentage()).isEqualTo(0.0d);
        assertThat(plan.totalCashFlowAfter()).isEqualByComparingTo("599.99");
        assertThat(plan.isMathematicallyOptimal()).isTrue();
    }

    @Test
    @DisplayName("fractional-cents snaps to exact cents via HALF_EVEN with no residue")
    void fractionalCentsRoundsExactly() {
        SampleGraph sample = sampleDataService.byId("fractional-cents");

        SettlementPlan plan = optimalEngine.minimize(sample.transactions(),
                OptimizationStrategy.OPTIMAL_SUBSET);

        assertPlanInternallyConsistent(plan);
        Map<String, BigDecimal> nets = plan.netBalances();
        assertThat(nets.values()).allSatisfy(balance -> assertThat(balance.scale()).isEqualTo(2));

        // 10.005 -> 10.00 and 20.015 -> 20.02, so Alan nets out to +30.02.
        assertThat(nets).containsEntry("Alan", new BigDecimal("30.02"));
        assertThat(plan.settlements()).allSatisfy(settlement ->
                assertThat(settlement.amount().scale()).isEqualTo(2));
    }

    @Test
    @DisplayName("single-transaction is already minimal and reports a 0% reduction")
    void singleTransactionHasNoReduction() {
        SampleGraph sample = sampleDataService.byId("single-transaction");

        SettlementPlan plan = greedyEngine.minimize(sample.transactions(), OptimizationStrategy.GREEDY);

        assertThat(plan.initialTransactionCount()).isEqualTo(1);
        assertThat(plan.optimizedTransactionCount()).isEqualTo(1);
        assertThat(plan.reductionPercentage()).isEqualTo(0.0d);
    }

    @Test
    @DisplayName("trip-5 collapses a ten-obligation web to the N-1 bound of four transfers")
    void tripFiveCollapsesToFour() {
        SampleGraph sample = sampleDataService.byId("trip-5");

        SettlementPlan plan = greedyEngine.minimize(sample.transactions(), OptimizationStrategy.GREEDY);

        assertThat(plan.initialTransactionCount()).isEqualTo(10);
        assertThat(plan.optimizedTransactionCount()).isEqualTo(4);
        assertThat(plan.participantCount()).isEqualTo(5);
        assertThat(plan.reductionPercentage()).isEqualTo(60.0d);
    }

    @Test
    @DisplayName("an unknown sample id is rejected with the list of valid ids")
    void unknownSampleIsRejected() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> sampleDataService.byId("nope"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("trip-5");
    }

    @Test
    @DisplayName("the returned sample list is immutable")
    void sampleListIsImmutable() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> sampleDataService.all().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }
}