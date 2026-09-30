package com.settlex.engine;

import com.settlex.dto.OptimizationStrategy;
import com.settlex.dto.SettlementPlan;
import com.settlex.dto.Transaction;
import com.settlex.engine.support.BitmaskPartitionSolver;
import com.settlex.engine.support.Position;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.settlex.support.EngineFixtures.assertLedgerFullySettled;
import static com.settlex.support.EngineFixtures.assertPlanInternallyConsistent;
import static com.settlex.support.EngineFixtures.assertValidPartition;
import static com.settlex.support.EngineFixtures.balances;
import static com.settlex.support.EngineFixtures.greedyPlan;
import static com.settlex.support.EngineFixtures.ledgerFor;
import static com.settlex.support.EngineFixtures.optimal;
import static com.settlex.support.EngineFixtures.optimalPlan;
import static com.settlex.support.EngineFixtures.positions;
import static com.settlex.support.EngineFixtures.tx;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("OptimalSubsetPartitionEngine")
class OptimalSubsetPartitionEngineTest {

    @Nested
    @DisplayName("bitmask zero-sum partitioning")
    class BitmaskPartitioning {

        @Test
        @DisplayName("finds no island when no proper zero-sum subset exists")
        void noPartitionWhenPrimitive() {
            // +3, +4 against -7: no proper subset sums to zero, so the ledger is one island.
            BitmaskPartitionSolver.Partition partition =
                    BitmaskPartitionSolver.solve(positions(300L, 400L, -700L));

            assertThat(partition.groupCount()).isEqualTo(1);
            assertThat(partition.theoreticalMinimum()).isEqualTo(2);
            assertThat(partition.groups()).hasSize(1);
            assertThat(partition.groups().getFirst()).hasSize(3);
        }

        @Test
        @DisplayName("splits a ledger that looks monolithic into two paired islands")
        void splitsPairedIslands() {
            // +3, +4 against -3, -4 does decompose: {+3,-3} and {+4,-4}.
            BitmaskPartitionSolver.Partition partition =
                    BitmaskPartitionSolver.solve(positions(300L, 400L, -300L, -400L));

            assertThat(partition.groupCount()).isEqualTo(2);
            assertThat(partition.theoreticalMinimum()).isEqualTo(2);
            assertThat(partition.groups()).hasSize(2);
        }

        @Test
        @DisplayName("splits a balanced ledger into as many zero-sum islands as exist")
        void findsEveryIsland() {
            // Two perfectly paired couples: k must be 4, so N - k = 4 transfers.
            BitmaskPartitionSolver.Partition partition = BitmaskPartitionSolver.solve(
                    positions(500L, 300L, -500L, -300L, 900L, -900L, 250L, -250L));

            assertThat(partition.groupCount()).isEqualTo(4);
            assertThat(partition.theoreticalMinimum()).isEqualTo(4);
            assertThat(partition.groups()).hasSize(4);
            assertThat(partition.groups()).allSatisfy(island ->
                    assertThat(island).hasSize(2));
        }

        @Test
        @DisplayName("finds a three-way island that pairwise matching would miss")
        void findsThreeWayIsland() {
            // +6, +5, -5, -3, -3: {+6,-3,-3} and {+5,-5} -> k = 2, minimum 3.
            BitmaskPartitionSolver.Partition partition =
                    BitmaskPartitionSolver.solve(positions(600L, 500L, -500L, -300L, -300L));

            assertThat(partition.groupCount()).isEqualTo(2);
            assertThat(partition.theoreticalMinimum()).isEqualTo(3);
            assertThat(partition.groups()).anySatisfy(island -> {
                assertThat(island).hasSize(3);
                assertThat(island.stream().mapToLong(Position::balance).sum()).isZero();
            });
        }

        @Test
        @DisplayName("handles the degenerate empty input")
        void handlesEmptyInput() {
            BitmaskPartitionSolver.Partition partition = BitmaskPartitionSolver.solve(List.of());

            assertThat(partition.groupCount()).isZero();
            assertThat(partition.theoreticalMinimum()).isZero();
            assertThat(partition.groups()).isEmpty();
        }

        @Test
        @DisplayName("refuses a ledger whose balances do not sum to zero")
        void refusesUnbalancedBalances() {
            assertThatThrownBy(() -> BitmaskPartitionSolver.solve(positions(100L, -50L)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("do not sum to zero");
        }

        @Test
        @DisplayName("declines participant counts beyond the configured ceiling")
        void respectsExactLimit() {
            assertThat(BitmaskPartitionSolver.isWithinExactLimit(16, 16)).isTrue();
            assertThat(BitmaskPartitionSolver.isWithinExactLimit(17, 16)).isFalse();
            assertThat(BitmaskPartitionSolver.isWithinExactLimit(20, 24)).isTrue();
        }
    }

    @Nested
    @DisplayName("greedy is beaten when islands exist")
    class BeatsGreedy {

        /**
         * The canonical demonstration. Positions are Quinn +3.00 and Priya +4.00 against
         * Sam -3.00, Omar -2.00 and Raj -2.00. The exact optimum is two islands —{Quinn, Sam}
         * and {Priya, Omar, Raj}— giving 3 transfers. Greedy's largest-with-largest rule first
         * pairs Priya with Sam, which destroys both islands and costs an extra transfer.
         */
        @Test
        @DisplayName("bitmask DP yields strictly fewer transactions than pure greedy")
        void dpBeatsGreedy() {
            List<Transaction> ledger = ledgerFor(balances(
                    "Quinn", "300", "Priya", "400",
                    "Sam", "-300", "Omar", "-200", "Raj", "-200"));

            SettlementPlan exact = optimalPlan(ledger);
            SettlementPlan heuristic = greedyPlan(ledger);

            assertThat(exact.optimizedTransactionCount()).isEqualTo(3);
            assertThat(exact.independentSubsetCount()).isEqualTo(2);
            assertThat(exact.optimalTransactionLowerBound()).isEqualTo(3);
            assertThat(exact.isMathematicallyOptimal()).isTrue();

            assertThat(heuristic.optimizedTransactionCount()).isEqualTo(4);
            assertThat(heuristic.isMathematicallyOptimal()).isFalse();

            assertThat(exact.optimizedTransactionCount())
                    .as("the DP must strictly beat the heuristic here")
                    .isLessThan(heuristic.optimizedTransactionCount());

            assertLedgerFullySettled(ledger, exact);
            assertLedgerFullySettled(ledger, heuristic);
            assertPlanInternallyConsistent(exact);
        }

        @Test
        @DisplayName("two independent islands never leak money across the boundary")
        void independentIslands() {
            List<Transaction> ledger = ledgerFor(balances(
                    "Ravi", "-5000", "Sana", "3000", "Imran", "2000",
                    "Mia", "-8000", "Noah", "8000"));

            SettlementPlan exact = optimalPlan(ledger);

            assertThat(exact.independentSubsetCount()).isEqualTo(2);
            assertThat(exact.optimizedTransactionCount()).isEqualTo(3);

            assertValidPartition(exact.independentSubsets(), exact.netBalances());

            // No settlement may connect the two islands.
            Set<String> islandOne = Set.copyOf(exact.independentSubsets().get(0));
            Set<String> islandTwo = Set.copyOf(exact.independentSubsets().get(1));
            assertThat(exact.settlements()).allSatisfy(settlement -> {
                boolean bothInOne = islandOne.contains(settlement.from())
                        && islandOne.contains(settlement.to());
                boolean bothInTwo = islandTwo.contains(settlement.from())
                        && islandTwo.contains(settlement.to());
                assertThat(bothInOne || bothInTwo)
                        .as("settlement %s crosses an island boundary", settlement)
                        .isTrue();
            });
        }

        @Test
        @DisplayName("never does worse than the greedy heuristic on any tested ledger")
        void neverWorseThanGreedy() {
            List<Map<String, Long>> scenarios = List.of(
                    balances("A", "300", "B", "400", "C", "-300", "D", "-200", "E", "-200"),
                    balances("A", "1000", "B", "1000", "C", "-1000", "D", "-1000"),
                    balances("A", "500", "B", "300", "C", "200", "D", "-1000"),
                    balances("A", "-250", "B", "-250", "C", "-500", "D", "500", "E", "500"),
                    balances("A", "100", "B", "100", "C", "100", "D", "-100", "E", "-100", "F", "-100"));

            for (Map<String, Long> scenario : scenarios) {
                List<Transaction> ledger = ledgerFor(scenario);

                SettlementPlan exact = optimalPlan(ledger);
                SettlementPlan heuristic = greedyPlan(ledger);

                assertThat(exact.optimizedTransactionCount())
                        .as("exact solver vs greedy on %s", scenario)
                        .isLessThanOrEqualTo(heuristic.optimizedTransactionCount());
                assertLedgerFullySettled(ledger, exact);
                assertPlanInternallyConsistent(exact);
            }
        }
    }

    @Nested
    @DisplayName("greedy fallback beyond the bitmask limit")
    class Fallback {

        @Test
        @DisplayName("falls back to greedy and flags isOptimalFallback when N exceeds the limit")
        void fallsBackAboveLimit() {
            Map<String, Long> targetBalances = new LinkedHashMap<>();
            for (int i = 0; i < 10; i++) {
                targetBalances.put("D" + i, -1000L);
            }
            for (int i = 0; i < 10; i++) {
                targetBalances.put("C" + i, 1000L);
            }
            List<Transaction> ledger = ledgerFor(targetBalances);

            SettlementPlan plan = optimalPlan(ledger);

            assertThat(plan.participantCount()).isEqualTo(20);
            assertThat(plan.isOptimalFallback()).isTrue();
            assertThat(plan.algorithmUsed()).isEqualTo(OptimalSubsetPartitionEngine.FALLBACK_ALGORITHM_NAME);
            assertThat(plan.optimalTransactionLowerBound()).isEqualTo(-1);
            assertThat(plan.isMathematicallyOptimal()).isFalse();
            assertLedgerFullySettled(ledger, plan);
        }

        @Test
        @DisplayName("the fallback path produces exactly what the greedy engine produces")
        void fallbackMatchesGreedyOutput() {
            Map<String, Long> targetBalances = new LinkedHashMap<>();
            for (int i = 0; i < 9; i++) {
                targetBalances.put("D" + i, -700L);
            }
            for (int i = 0; i < 9; i++) {
                targetBalances.put("C" + i, 700L);
            }
            List<Transaction> ledger = ledgerFor(targetBalances);

            SettlementPlan fallback = optimalPlan(ledger);
            SettlementPlan heuristic = greedyPlan(ledger);

            assertThat(fallback.settlements()).isEqualTo(heuristic.settlements());
        }

        @Test
        @DisplayName("an 18-participant ledger stays on the exact path when the limit is raised")
        void raisedLimitKeepsExactPath() {
            Map<String, Long> targetBalances = new LinkedHashMap<>();
            for (int i = 0; i < 9; i++) {
                targetBalances.put("D" + i, -700L);
            }
            for (int i = 0; i < 9; i++) {
                targetBalances.put("C" + i, 700L);
            }
            List<Transaction> ledger = ledgerFor(targetBalances);

            com.settlex.config.SettleXProperties permissive = new com.settlex.config.SettleXProperties(
                    new com.settlex.config.SettleXProperties.Engine(20), null);
            SettlementPlan plan = new OptimalSubsetPartitionEngine(permissive)
                    .minimize(ledger, OptimizationStrategy.OPTIMAL_SUBSET);

            assertThat(plan.isOptimalFallback()).isFalse();
            assertThat(plan.isMathematicallyOptimal()).isTrue();
            assertThat(plan.algorithmUsed()).isEqualTo(OptimalSubsetPartitionEngine.ALGORITHM_NAME);
        }
    }

    @Nested
    @DisplayName("settlement correctness")
    class Correctness {

        @ParameterizedTest(name = "{0} -> {1} transfers")
        @CsvSource({
                "'100.00,100.00,-100.00,-100.00', 2",
                "'300.00,400.00,-300.00,-200.00,-200.00', 3",
                "'1000.00,1000.00,1000.00,-1000.00,-1000.00,-1000.00', 3",
                "'500.00,300.00,-300.00,-500.00', 2",
        })
        @DisplayName("hits the theoretical minimum for textbook balance sets")
        void hitsTheoreticalMinimum(String balanceSpec, int expectedTransfers) {
            String[] parts = balanceSpec.split(",");
            long[] raw = new long[parts.length];
            for (int i = 0; i < parts.length; i++) {
                raw[i] = new BigDecimal(parts[i].trim())
                        .movePointRight(com.settlex.engine.support.MoneyMath.SCALE)
                        .longValueExact();
            }
            List<Position> positions = new ArrayList<>(parts.length);
            for (int i = 0; i < raw.length; i++) {
                positions.add(new Position("P" + i, raw[i]));
            }

            BitmaskPartitionSolver.Partition partition = BitmaskPartitionSolver.solve(positions);
            List<Transaction> ledger = ledgerFor(
                    java.util.stream.IntStream.range(0, raw.length)
                            .boxed()
                            .collect(java.util.stream.Collectors.toMap(
                                    i -> "P" + i, i -> raw[i], (a, b) -> a,
                                    LinkedHashMap::new)));

            SettlementPlan plan = optimalPlan(ledger);

            assertThat(plan.optimizedTransactionCount()).isEqualTo(expectedTransfers);
            assertThat(plan.optimizedTransactionCount())
                    .isEqualTo(partition.theoreticalMinimum());
            assertLedgerFullySettled(ledger, plan);
            assertPlanInternallyConsistent(plan);
        }

        @Test
        @DisplayName("resolves a fully circular ledger to zero transfers")
        void circularLedgerResolvesToNothing() {
            List<Transaction> cycle = List.of(
                    tx("Ana", "Ben", "100.00"),
                    tx("Ben", "Chloe", "100.00"),
                    tx("Chloe", "Diego", "100.00"),
                    tx("Diego", "Ana", "100.00"));

            SettlementPlan plan = optimalPlan(cycle);

            assertThat(plan.settlements()).isEmpty();
            assertThat(plan.isMathematicallyOptimal()).isTrue();
            assertThat(plan.reductionPercentage()).isEqualTo(100.0d);
        }

        @Test
        @DisplayName("is deterministic across repeated runs")
        void isDeterministic() {
            List<Transaction> ledger = ledgerFor(balances(
                    "A", "3000", "B", "2500", "C", "1250",
                    "X", "-2000", "Y", "-3000", "Z", "-1750"));

            SettlementPlan first = optimalPlan(ledger);
            SettlementPlan second = optimalPlan(ledger);

            assertThat(first.settlements()).isEqualTo(second.settlements());
            assertThat(first.independentSubsets()).isEqualTo(second.independentSubsets());
        }
    }

    @Test
    @DisplayName("rejects being handed a strategy it does not implement")
    void rejectsForeignStrategy() {
        List<Transaction> ledger = List.of(tx("A", "B", "10.00"));

        assertThatThrownBy(() -> optimal().minimize(ledger, OptimizationStrategy.GREEDY))
                .isInstanceOf(com.settlex.exception.UnsupportedStrategyException.class);
    }

    @Test
    @DisplayName("exposes a stable algorithm identifier for the UI")
    void exposesAlgorithmName() {
        assertThat(optimal().name()).isEqualTo(OptimalSubsetPartitionEngine.ALGORITHM_NAME);
        assertThat(optimal().supportedStrategy()).isEqualTo(OptimizationStrategy.OPTIMAL_SUBSET);
    }
}