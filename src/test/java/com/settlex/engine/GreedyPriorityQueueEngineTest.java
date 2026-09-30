package com.settlex.engine;

import com.settlex.dto.OptimizationStrategy;
import com.settlex.dto.Settlement;
import com.settlex.dto.SettlementPlan;
import com.settlex.dto.Transaction;
import com.settlex.engine.support.Position;
import com.settlex.exception.UnbalancedLedgerException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.settlex.support.EngineFixtures.assertLedgerFullySettled;
import static com.settlex.support.EngineFixtures.assertPlanInternallyConsistent;
import static com.settlex.support.EngineFixtures.balances;
import static com.settlex.support.EngineFixtures.greedy;
import static com.settlex.support.EngineFixtures.greedyPlan;
import static com.settlex.support.EngineFixtures.ledgerFor;
import static com.settlex.support.EngineFixtures.optimalPlan;
import static com.settlex.support.EngineFixtures.positions;
import static com.settlex.support.EngineFixtures.tx;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("GreedyPriorityQueueEngine")
class GreedyPriorityQueueEngineTest {

    @Nested
    @DisplayName("circular debt resolution")
    class CircularDebt {

        /**
         * A owes B, B owes C, C owes A at equal amounts. Every obligation is individually
         * binding yet collectively void: the three-way loop nets out and the correct answer
         * is that nobody needs to move any money at all.
         */
        @Test
        @DisplayName("A->B, B->C, C->A at equal amounts resolves to zero transactions")
        void threeWayCircularLoopResolvesToNothing() {
            List<Transaction> cycle = List.of(
                    tx("A", "B", "100.00"),
                    tx("B", "C", "100.00"),
                    tx("C", "A", "100.00"));

            SettlementPlan plan = greedyPlan(cycle);

            assertThat(plan.settlements()).isEmpty();
            assertThat(plan.optimizedTransactionCount()).isZero();
            // Three obligations of 100.00 each were supplied; none of them need to happen.
            assertThat(plan.totalCashFlowBefore()).isEqualByComparingTo("300.00");
            assertThat(plan.totalCashFlowAfter()).isEqualByComparingTo("0.00");
            assertThat(plan.reductionPercentage()).isEqualTo(100.0d);
            assertThat(plan.isMathematicallyOptimal()).isTrue();
            assertPlanInternallyConsistent(plan);
        }

        @Test
        @DisplayName("an unequal four-party cycle collapses to two direct transfers")
        void fourPartyLoopCollapses() {
            List<Transaction> cycle = List.of(
                    tx("Ana", "Ben", "100.00"),
                    tx("Ben", "Chloe", "100.00"),
                    tx("Chloe", "Diego", "100.00"),
                    tx("Diego", "Ana", "100.00"));

            SettlementPlan plan = greedyPlan(cycle);

            assertThat(plan.settlements()).isEmpty();
            assertThat(plan.netBalances()).allSatisfy((name, balance) ->
                    assertThat(balance).isEqualByComparingTo("0.00"));
        }

        @Test
        @DisplayName("a perturbed cycle collapses to a single direct transfer")
        void perturbedCycleResolvesAlongOneEdge() {
            // Ana owes Ben 100 and Diego owes Ana 90, so Ana nets to -10; Chloe nets to +10.
            List<Transaction> cycle = List.of(
                    tx("Ana", "Ben", "100.00"),
                    tx("Ben", "Chloe", "100.00"),
                    tx("Chloe", "Diego", "90.00"),
                    tx("Diego", "Ana", "90.00"));

            SettlementPlan plan = greedyPlan(cycle);

            assertThat(plan.netBalances())
                    .containsEntry("Ana", new BigDecimal("-10.00"))
                    .containsEntry("Ben", new BigDecimal("0.00"))
                    .containsEntry("Chloe", new BigDecimal("10.00"))
                    .containsEntry("Diego", new BigDecimal("0.00"));

            assertThat(plan.settlements()).containsExactly(
                    new Settlement("Ana", "Chloe", new BigDecimal("10.00")));
            assertLedgerFullySettled(cycle, plan);
            assertPlanInternallyConsistent(plan);
        }
    }

    @Nested
    @DisplayName("structural guarantees")
    class Guarantees {

        @Test
        @DisplayName("never emits more than N-1 transfers")
        void respectsTheNMinusOneBound() {
            // 2 creditors, 5 debtors -> at most 6 transfers.
            List<Transaction> ledger = ledgerFor(balances(
                    "C1", "5000", "C2", "7000",
                    "D1", "-2000", "D2", "-3000", "D3", "-1000", "D4", "-4000", "D5", "-2000"));

            SettlementPlan plan = greedyPlan(ledger);

            assertThat(plan.participantCount()).isEqualTo(7);
            assertThat(plan.settlements()).hasSizeLessThanOrEqualTo(6);
            assertLedgerFullySettled(ledger, plan);
        }

        @Test
        @DisplayName("reports optimality only when it can prove it")
        void reportsOptimalityOnlyWhenProven() {
            // 20 active participants exceeds the default bitmask ceiling of 16, so the
            // heuristic cannot prove minimality and must say so rather than guess.
            Map<String, Long> targetBalances = new LinkedHashMap<>();
            for (int i = 0; i < 10; i++) {
                targetBalances.put("D" + i, -1000L);
            }
            for (int i = 0; i < 10; i++) {
                targetBalances.put("C" + i, 1000L);
            }

            List<Transaction> ledger = ledgerFor(targetBalances);

            SettlementPlan plan = greedyPlan(ledger);

            assertThat(plan.participantCount()).isEqualTo(20);
            assertThat(plan.optimalTransactionLowerBound()).isEqualTo(-1);
            assertThat(plan.isMathematicallyOptimal()).isFalse();
            assertLedgerFullySettled(ledger, plan);
        }

        @Test
        @DisplayName("is deterministic across repeated runs")
        void isDeterministic() {
            List<Transaction> ledger = ledgerFor(balances(
                    "A", "3000", "B", "2500", "C", "1250",
                    "X", "-2000", "Y", "-3000", "Z", "-1750"));

            SettlementPlan first = greedyPlan(ledger);
            SettlementPlan second = greedyPlan(ledger);

            assertThat(first.settlements()).isEqualTo(second.settlements());
            assertThat(first.algorithmUsed()).isEqualTo(second.algorithmUsed());
        }
    }

    @Nested
    @DisplayName("preconditions")
    class Preconditions {

        /**
         * Every obligation in the {@link Transaction} model is bilateral: one amount is
         * debited from one participant and credited to another. Gross debits therefore always
         * equal gross credits, and the net balances always sum to zero — a client simply
         * cannot express an unbalanced ledger.
         *
         * <p>{@link com.settlex.exception.UnbalancedLedgerException} is consequently a
         * defensive invariant rather than a reachable client error: it guards the netting
         * logic against regression. This test pins that invariant down over a spread of
         * ledgers so the guarantee cannot be broken silently.</p>
         */
        @Test
        @DisplayName("net balances always sum to zero, so an unbalanced ledger is unexpressible")
        void bilateralLedgerIsStructurallyBalanced() {
            List<List<Transaction>> ledgers = List.of(
                    List.of(tx("A", "B", "100.00")),
                    List.of(tx("A", "B", "100.00"), tx("C", "D", "30.00")),
                    List.of(tx("A", "B", "75.50"), tx("B", "C", "75.50"), tx("C", "A", "75.50")),
                    List.of(tx("A", "B", "10.01"), tx("A", "C", "20.02"),
                            tx("B", "D", "5.55"), tx("D", "C", "24.48")));

            for (List<Transaction> ledger : ledgers) {
                com.settlex.engine.support.SettlementProblem problem =
                        com.settlex.engine.support.LedgerNormalizer.normalize(ledger);

                BigDecimal net = problem.netBalances().values().stream()
                        .reduce(BigDecimal.ZERO, BigDecimal::add);

                assertThat(net).as("net balances must sum to exactly zero for %s", ledger)
                        .isEqualByComparingTo(BigDecimal.ZERO);
                assertThat(problem.totalOutstandingMinor())
                        .as("debtor exposure must equal creditor credit")
                        .isEqualTo(sumOfCredits(problem));
            }
        }

        private static long sumOfCredits(com.settlex.engine.support.SettlementProblem problem) {
            long total = 0L;
            for (com.settlex.engine.support.Position creditor : problem.creditors()) {
                total += creditor.credit();
            }
            return total;
        }

        @Test
        @DisplayName("rejects being handed a strategy it does not implement")
        void rejectsForeignStrategy() {
            List<Transaction> ledger = List.of(tx("A", "B", "10.00"));

            assertThatThrownBy(() -> greedy().minimize(ledger, OptimizationStrategy.OPTIMAL_SUBSET))
                    .isInstanceOf(com.settlex.exception.UnsupportedStrategyException.class);
        }

        @Test
        @DisplayName("rejects an empty ledger")
        void rejectsEmptyLedger() {
            assertThatThrownBy(() -> greedyPlan(List.of()))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("topology")
    class Topology {

        @Test
        @DisplayName("a single hub debtor drains in exactly one transfer per creditor")
        void starTopology() {
            List<Transaction> star = List.of(
                    tx("Kai", "Lena", "220.00"),
                    tx("Kai", "Mateo", "175.25"),
                    tx("Kai", "Nina", "98.75"),
                    tx("Kai", "Omar", "64.00"),
                    tx("Kai", "Tess", "41.99"));

            SettlementPlan plan = greedyPlan(star);

            assertThat(plan.settlements()).hasSize(5);
            assertThat(plan.settlements()).allSatisfy(settlement ->
                    assertThat(settlement.from()).isEqualTo("Kai"));
            assertThat(plan.totalCashFlowAfter()).isEqualByComparingTo("599.99");
            assertLedgerFullySettled(star, plan);
        }

        @Test
        @DisplayName("directors always sit on the creditor side and payers on the debtor side")
        void respectsNetPositions() {
            List<Transaction> ledger = ledgerFor(balances(
                    "Winner", "9000", "RunnerUp", "1000",
                    "Loser", "-4000", "Middle", "-3000", "AlsoLoser", "-3000"));

            SettlementPlan plan = greedyPlan(ledger);
            List<String> creditors = List.of("Winner", "RunnerUp");
            List<String> debtors = List.of("Loser", "Middle", "AlsoLoser");

            assertThat(plan.settlements()).allSatisfy(settlement -> {
                assertThat(debtors).contains(settlement.from());
                assertThat(creditors).contains(settlement.to());
            });
        }

        @Test
        @DisplayName("a chain of obligations collapses to a single direct transfer")
        void chainCollapsesToOneTransfer() {
            List<Transaction> chain = List.of(
                    tx("A", "B", "10.00"),
                    tx("B", "C", "10.00"),
                    tx("C", "D", "10.00"));

            SettlementPlan plan = greedyPlan(chain);

            assertThat(plan.settlements()).containsExactly(
                    new Settlement("A", "D", new BigDecimal("10.00")));
            assertThat(plan.reductionPercentage()).isEqualTo(66.67d);
        }
    }

    @Nested
    @DisplayName("scale")
    class Scale {

        @ParameterizedTest(name = "N = {0} active participants")
        @ValueSource(ints = {2, 4, 8, 16})
        @DisplayName("settles large ledgers completely and within the N-1 bound")
        void settlesManyParticipants(int halfSize) {
            // halfSize creditors of +100 against halfSize debtors of -100.
            long[] balance = new long[halfSize * 2];
            for (int i = 0; i < halfSize; i++) {
                balance[i] = 100L;
                balance[halfSize + i] = -100L;
            }

            List<Transaction> ledger = ledgerFor(
                    java.util.stream.IntStream.range(0, halfSize * 2)
                            .boxed()
                            .collect(java.util.stream.Collectors.toMap(
                                    i -> "P" + i, i -> balance[i], (a, b) -> a,
                                    java.util.LinkedHashMap::new)));

            SettlementPlan plan = greedyPlan(ledger);

            assertThat(plan.participantCount()).isEqualTo(halfSize * 2);
            assertThat(plan.settlements()).hasSizeLessThanOrEqualTo(halfSize * 2 - 1);
            assertLedgerFullySettled(ledger, plan);
            assertPlanInternallyConsistent(plan);
        }
    }

    @Test
    @DisplayName("exposes a stable algorithm identifier for the UI")
    void exposesAlgorithmName() {
        assertThat(greedy().name()).isEqualTo(GreedyPriorityQueueEngine.ALGORITHM_NAME);
        assertThat(greedy().supportedStrategy()).isEqualTo(OptimizationStrategy.GREEDY);
    }

    @Test
    @DisplayName("settleGroup handles empty input and refuses an unbalanced group")
    void settleGroupHandlesEmptyInput() {
        assertThat(GreedyPriorityQueueEngine.settleGroup(List.of(), List.of())).isEmpty();
        assertThat(GreedyPriorityQueueEngine.settleGroup(
                List.of(new Position("Ana", 100L)), List.of(new Position("Ben", -100L))))
                .containsExactly(new Settlement("Ben", "Ana", new BigDecimal("1.00")));

        assertThatThrownBy(() -> GreedyPriorityQueueEngine.settleGroup(positions(100L), List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unbalanced");
        assertThatThrownBy(() -> GreedyPriorityQueueEngine.settleGroup(
                positions(100L), positions(-50L)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unbalanced");
    }

    @Test
    @DisplayName("settleGroup rejects positions supplied on the wrong side instead of spinning")
    void settleGroupRejectsMisSignedPositions() {
        // A negative balance on the creditor side would make the transfer amount 0 and reduce()
        // a no-op; the engine must refuse the input rather than loop until the heap dies.
        assertThatThrownBy(() -> GreedyPriorityQueueEngine.settleGroup(
                positions(-200L), positions(100L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("creditor side")
                .hasMessageContaining("P0=-2.00");

        assertThatThrownBy(() -> GreedyPriorityQueueEngine.settleGroup(
                positions(100L), positions(200L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("debtor side")
                .hasMessageContaining("P0=2.00");
    }
}