package com.settlex.engine;

import com.settlex.dto.SettlementPlan;
import com.settlex.dto.Transaction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.util.List;

import static com.settlex.support.EngineFixtures.assertLedgerFullySettled;
import static com.settlex.support.EngineFixtures.assertPlanInternallyConsistent;
import static com.settlex.support.EngineFixtures.greedyPlan;
import static com.settlex.support.EngineFixtures.optimalPlan;
import static com.settlex.support.EngineFixtures.tx;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Idempotency and degenerate inputs")
class IdempotencyTest {

    /**
     * A single obligation is already minimal: one debtor, one creditor, one transfer. Any
     * optimizer that reports a reduction here is fabricating work.
     */
    @Test
    @DisplayName("a single transaction settles to exactly itself under both engines")
    void singleTransactionIsIdempotent() {
        List<Transaction> single = List.of(tx("Ivy", "Jon", "42.50"));

        for (SettlementPlan plan : List.of(greedyPlan(single), optimalPlan(single))) {
            assertThat(plan.initialTransactionCount()).isEqualTo(1);
            assertThat(plan.optimizedTransactionCount()).isEqualTo(1);
            assertThat(plan.reductionPercentage()).isEqualTo(0.0d);
            assertThat(plan.settlements()).singleElement().satisfies(settlement -> {
                assertThat(settlement.from()).isEqualTo("Ivy");
                assertThat(settlement.to()).isEqualTo("Jon");
                assertThat(settlement.amount()).isEqualByComparingTo("42.50");
            });
            assertThat(plan.totalCashFlowBefore()).isEqualByComparingTo("42.50");
            assertThat(plan.totalCashFlowAfter()).isEqualByComparingTo("42.50");
            assertPlanInternallyConsistent(plan);
        }
    }

    /**
     * A settlement plan is a fixed point of the optimizer. Feeding a plan's own transfers back
     * as the input ledger yields exactly the same transfers, so repeatedly re-optimizing can
     * never oscillate or invent new work.
     */
    @Test
    @DisplayName("a plan is a fixed point: re-optimizing it reproduces it exactly")
    void planIsAFixedPoint() {
        List<Transaction> ledger = List.of(
                tx("A", "B", "10.00"),
                tx("B", "C", "10.00"),
                tx("C", "D", "10.00"),
                tx("A", "D", "4.00"),
                tx("D", "B", "4.00"));

        SettlementPlan plan = greedyPlan(ledger);
        assertThat(plan.settlements()).isNotEmpty();

        List<Transaction> planAsLedger = plan.settlements().stream()
                .map(s -> tx(s.from(), s.to(), s.amount()))
                .toList();

        SettlementPlan replayed = greedyPlan(planAsLedger);

        assertThat(replayed.settlements())
                .as("re-optimizing a settled ledger must be a no-op")
                .isEqualTo(plan.settlements());
        assertThat(replayed.optimizedTransactionCount())
                .isEqualTo(plan.optimizedTransactionCount());
    }

    @ParameterizedTest
    @EnumSource(com.settlex.dto.OptimizationStrategy.class)
    @DisplayName("a ledger of mutually cancelling pairs settles to zero transfers")
    void cancellingPairsSettleToNothing(com.settlex.dto.OptimizationStrategy strategy) {
        List<Transaction> ledger = List.of(
                tx("A", "B", "25.00"),
                tx("B", "A", "25.00"),
                tx("C", "D", "60.00"),
                tx("D", "C", "60.00"));

        SettlementPlan plan = strategy == com.settlex.dto.OptimizationStrategy.GREEDY
                ? greedyPlan(ledger) : optimalPlan(ledger);

        assertThat(plan.settlements()).isEmpty();
        assertThat(plan.reductionPercentage()).isEqualTo(100.0d);
        assertThat(plan.isMathematicallyOptimal()).isTrue();
    }

    @Test
    @DisplayName("a self-referential obligation is inert and nets to zero")
    void selfReferentialObligationIsNeutral() {
        List<Transaction> ledger = List.of(
                tx("Solo", "Solo", "500.00"),
                tx("A", "B", "10.00"),
                tx("B", "A", "10.00"));

        SettlementPlan plan = greedyPlan(ledger);

        assertThat(plan.settlements()).isEmpty();
        assertThat(plan.netBalances()).containsEntry("Solo", new BigDecimal("0.00"));
        assertLedgerFullySettled(ledger, plan);
    }

    @Test
    @DisplayName("a single creditor paid by many debtors needs one transfer per debtor")
    void manyToOne() {
        List<Transaction> ledger = List.of(
                tx("A", "Hub", "10.00"),
                tx("B", "Hub", "20.00"),
                tx("C", "Hub", "30.00"));

        SettlementPlan plan = greedyPlan(ledger);

        assertThat(plan.settlements()).hasSize(3);
        assertThat(plan.settlements()).allSatisfy(s -> assertThat(s.to()).isEqualTo("Hub"));
        assertThat(plan.totalCashFlowAfter()).isEqualByComparingTo("60.00");
    }

    @Test
    @DisplayName("a fractional-cent ledger still nets to exact cents")
    void fractionalCentLedger() {
        // 10.005 -> 10.00 (ties down), 20.015 -> 20.02 (ties up).
        List<Transaction> ledger = List.of(
                tx("Ada", "Grace", "10.005"),
                tx("Ada", "Alan", "20.015"),
                tx("Grace", "Alan", "10.005"),
                tx("Alan", "Ada", "10.005"),
                tx("Grace", "Alan", "10.005"),
                tx("Ada", "Grace", "20.005"));

        SettlementPlan plan = optimalPlan(ledger);

        assertPlanInternallyConsistent(plan);
        assertLedgerFullySettled(ledger, plan);

        // Every reported amount must be exactly two decimals: no floating-point residue.
        assertThat(plan.settlements()).allSatisfy(settlement -> {
            assertThat(settlement.amount().scale()).isEqualTo(2);
            assertThat(settlement.amount()).isEqualByComparingTo(settlement.amount().setScale(2));
        });
        assertThat(plan.netBalances().values()).allSatisfy(balance ->
                assertThat(balance.scale()).isEqualTo(2));
    }

    @Test
    @DisplayName("three-way rounding ties balance to the cent after normalization")
    void roundingPreservesBalance() {
        List<Transaction> ledger = List.of(
                tx("X", "Y", "0.125"),
                tx("Y", "Z", "0.125"),
                tx("Z", "X", "0.125"));

        // 0.125 -> 0.12 for each side, so the ledger remains balanced.
        SettlementPlan plan = greedyPlan(ledger);

        assertThat(plan.settlements()).isEmpty();
        assertThat(plan.netBalances().values()).allSatisfy(balance ->
                assertThat(balance).isEqualByComparingTo(BigDecimal.ZERO));
    }
}