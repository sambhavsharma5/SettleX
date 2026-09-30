package com.settlex.support;

import com.settlex.dto.OptimizationStrategy;
import com.settlex.dto.SettlementPlan;
import com.settlex.dto.SettlementRequest;
import com.settlex.dto.Transaction;
import com.settlex.engine.GreedyPriorityQueueEngine;
import com.settlex.engine.OptimalSubsetPartitionEngine;
import com.settlex.engine.support.BitmaskPartitionSolver;
import com.settlex.engine.support.LedgerNormalizer;
import com.settlex.engine.support.Position;
import com.settlex.engine.support.SettlementProblem;
import com.settlex.service.SettlementService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared fixtures and assertions for the engine test suite.
 */
public final class EngineFixtures {

    private EngineFixtures() {
    }

    /** The default properties used by hand-wired engines in unit tests. */
    public static final com.settlex.config.SettleXProperties DEFAULT_PROPERTIES =
            new com.settlex.config.SettleXProperties(null, null);

    public static Transaction tx(String debtor, String creditor, String amount) {
        return new Transaction(debtor, creditor, new BigDecimal(amount));
    }

    public static Transaction tx(String debtor, String creditor, BigDecimal amount) {
        return new Transaction(debtor, creditor, amount);
    }

    public static Transaction tx(String debtor, String creditor, double amount) {
        return new Transaction(debtor, creditor, BigDecimal.valueOf(amount));
    }

    /** Builds balances directly, bypassing the obligation graph, for DP-focused tests. */
    public static List<Position> positions(long... balances) {
        List<Position> positions = new ArrayList<>(balances.length);
        for (int i = 0; i < balances.length; i++) {
            positions.add(new Position("P" + i, balances[i]));
        }
        return positions;
    }

    /**
     * Realizes a set of target net balances as a dense obligation graph.
     *
     * <p>Every debtor pays every creditor in a round-robin fashion until all exposure is
     * consumed, which guarantees the resulting ledger nets to exactly the requested balances
     * and is always balanced.</p>
     */
    public static List<Transaction> ledgerFor(Map<String, Long> balancesInMinorUnits) {
        List<String> creditors = new ArrayList<>();
        List<String> debtors = new ArrayList<>();
        Map<String, Long> remaining = new HashMap<>(balancesInMinorUnits);

        for (Map.Entry<String, Long> entry : balancesInMinorUnits.entrySet()) {
            if (entry.getValue() > 0) {
                creditors.add(entry.getKey());
            } else if (entry.getValue() < 0) {
                debtors.add(entry.getKey());
            }
        }

        List<Transaction> transactions = new ArrayList<>();
        int creditorIndex = 0;
        int debtorIndex = 0;

        while (debtorIndex < debtors.size()) {
            String debtor = debtors.get(debtorIndex);
            String creditor = creditors.get(creditorIndex);
            long owed = Math.abs(remaining.get(debtor));
            long due = remaining.get(creditor);
            long amount = Math.min(owed, due);

            transactions.add(new Transaction(debtor, creditor,
                    com.settlex.engine.support.MoneyMath.fromMinorUnits(amount)));

            remaining.put(debtor, remaining.get(debtor) + amount);
            remaining.put(creditor, remaining.get(creditor) - amount);

            if (remaining.get(debtor) == 0L) {
                debtorIndex++;
            }
            if (remaining.get(creditor) == 0L) {
                creditorIndex++;
                if (creditorIndex >= creditors.size()) {
                    break;
                }
            }
        }
        return transactions;
    }

    public static Map<String, Long> balances(String... nameThenMinorUnits) {
        if (nameThenMinorUnits.length % 2 != 0) {
            throw new IllegalArgumentException("Expected name/amount pairs");
        }
        Map<String, Long> map = new HashMap<>();
        for (int i = 0; i < nameThenMinorUnits.length; i += 2) {
            map.put(nameThenMinorUnits[i], Long.parseLong(nameThenMinorUnits[i + 1]));
        }
        return map;
    }

    public static GreedyPriorityQueueEngine greedy() {
        return new GreedyPriorityQueueEngine(DEFAULT_PROPERTIES);
    }

    public static OptimalSubsetPartitionEngine optimal() {
        return new OptimalSubsetPartitionEngine(DEFAULT_PROPERTIES);
    }

    public static SettlementPlan greedyPlan(List<Transaction> transactions) {
        return greedy().minimize(transactions, OptimizationStrategy.GREEDY);
    }

    public static SettlementPlan optimalPlan(List<Transaction> transactions) {
        return optimal().minimize(transactions, OptimizationStrategy.OPTIMAL_SUBSET);
    }

    /** Verifies a plan actually discharges every obligation, by replaying it against the ledger. */
    public static void assertLedgerFullySettled(List<Transaction> transactions, SettlementPlan plan) {
        SettlementProblem problem = LedgerNormalizer.normalize(transactions);

        // Apply the optimized transfers to the original balances and require all zeros.
        Map<String, Long> running = new HashMap<>();
        problem.netBalances().forEach((name, money) ->
                running.put(name, money.movePointRight(2).longValueExact()));

        plan.settlements().forEach(settlement -> {
            long amount = settlement.amount().movePointRight(2).longValueExact();
            // A positive net balance means "is owed money". Receiving it consumes that claim,
            // so the creditor's balance falls; a debtor paying off moves back toward zero,
            // so the debtor's balance rises.
            running.merge(settlement.from(), amount, Long::sum);
            running.merge(settlement.to(), -amount, Long::sum);
        });

        running.forEach((name, balance) -> {
            if (balance != 0L) {
                throw new AssertionError("Plan leaves " + name + " unsettled by "
                        + com.settlex.engine.support.MoneyMath.formatMinorUnits(balance));
            }
        });
    }

    /** Verifies the plan's own self-reported invariants. */
    public static void assertPlanInternallyConsistent(SettlementPlan plan) {
        if (plan.optimizedTransactionCount() != plan.settlements().size()) {
            throw new AssertionError("optimizedTransactionCount disagrees with settlements.size()");
        }
        if (plan.initialTransactionCount() < plan.optimizedTransactionCount()) {
            throw new AssertionError("Optimization increased the transaction count: "
                    + plan.initialTransactionCount() + " -> " + plan.optimizedTransactionCount());
        }
        if (plan.settlements().stream().anyMatch(s -> s.amount().signum() <= 0)) {
            throw new AssertionError("Settlement plan contains a non-positive transfer");
        }
        for (var settlement : plan.settlements()) {
            if (settlement.amount().scale() != 2) {
                throw new AssertionError("Transfer " + settlement.amount()
                        + " is not normalized to two decimal places");
            }
        }
        if (plan.optimalTransactionLowerBound() >= 0 && plan.isMathematicallyOptimal()
                && plan.optimizedTransactionCount() != plan.optimalTransactionLowerBound()) {
            // Only meaningful when optimality was actually claimed. The greedy engine reports
            // the DP-derived bound even when its own answer falls short of it, and that is
            // exactly how a suboptimal heuristic run is made visible.
            throw new AssertionError("Plan claims a proven bound of "
                    + plan.optimalTransactionLowerBound() + " but emitted "
                    + plan.optimizedTransactionCount());
        }
        if (plan.isMathematicallyOptimal() && plan.isOptimalFallback()) {
            throw new AssertionError("A fallback run cannot claim mathematical optimality");
        }
    }

    /** Verifies that every island reported by the DP genuinely sums to zero and is disjoint. */
    public static void assertValidPartition(List<List<String>> islands, Map<String, BigDecimal> netBalances) {
        if (islands.isEmpty()) {
            return;
        }
        Map<String, Integer> occurrences = new HashMap<>();
        for (List<String> island : islands) {
            BigDecimal sum = BigDecimal.ZERO;
            for (String participant : island) {
                occurrences.merge(participant, 1, Integer::sum);
                sum = sum.add(netBalances.getOrDefault(participant, BigDecimal.ZERO));
            }
            if (sum.compareTo(BigDecimal.ZERO) != 0) {
                throw new AssertionError("Island " + island + " does not sum to zero: " + sum);
            }
        }
        occurrences.forEach((participant, count) -> {
            if (count != 1) {
                throw new AssertionError(
                        "Participant " + participant + " appears in " + count + " islands; "
                                + "the partition must be disjoint and exhaustive");
            }
        });
    }

    public static BigDecimal money(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_EVEN);
    }

    public static SettlementRequest request(List<Transaction> transactions, OptimizationStrategy strategy) {
        return new SettlementRequest(transactions, strategy);
    }
}