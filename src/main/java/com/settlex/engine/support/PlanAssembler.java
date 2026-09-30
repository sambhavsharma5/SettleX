package com.settlex.engine.support;

import com.settlex.dto.Settlement;
import com.settlex.dto.SettlementPlan;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Builds the {@link SettlementPlan} returned by every engine, so that reported metrics are
 * computed identically no matter which algorithm produced the settlements.
 *
 * <p>All monetary totals are accumulated in {@code long} minor units and converted once, at
 * the boundary, which keeps the arithmetic exact regardless of how many settlements a run
 * produced.</p>
 */
public final class PlanAssembler {

    private PlanAssembler() {
        throw new AssertionError("Utility class");
    }

    /**
     * @param problem            the validated problem the engine solved
     * @param settlements        the produced transfers
     * @param elapsedNanos       engine wall-clock time
     * @param algorithm          algorithm identifier for the response
     * @param groupCount         {@code k} for the exact solver; the implicitly achieved group
     *                           count for the greedy solver
     * @param lowerBound         provable minimum transfer count, or {@code -1} when unknown
     * @param fallback           whether the exact solver degraded to the heuristic
     * @param islands            discovered zero-sum components as participant names
     * @param provablyOptimal    whether the transfer count is known to be minimal
     */
    public static SettlementPlan assemble(SettlementProblem problem,
                                          List<Settlement> settlements,
                                          long elapsedNanos,
                                          String algorithm,
                                          int groupCount,
                                          int lowerBound,
                                          boolean fallback,
                                          List<List<String>> islands,
                                          boolean provablyOptimal) {

        long cashFlowAfterMinor = 0L;
        for (Settlement settlement : settlements) {
            cashFlowAfterMinor = Math.addExact(cashFlowAfterMinor,
                    settlement.amount().movePointRight(MoneyMath.SCALE).longValueExact());
        }

        int initial = problem.initialTransactionCount();
        int optimized = settlements.size();

        return new SettlementPlan(
                List.copyOf(settlements),
                initial,
                optimized,
                SettlementPlan.reductionPercentage(initial, optimized),
                MoneyMath.fromMinorUnits(problem.totalCashFlow()),
                MoneyMath.fromMinorUnits(cashFlowAfterMinor),
                elapsedNanos,
                algorithm,
                Map.copyOf(problem.netBalances()),
                provablyOptimal,
                fallback,
                problem.netBalances().size(),
                groupCount,
                lowerBound,
                List.copyOf(islands));
    }

    /** Total cash moved by a settlement list, in major units. */
    public static BigDecimal totalCashFlow(List<Settlement> settlements) {
        long minor = 0L;
        for (Settlement settlement : settlements) {
            minor = Math.addExact(minor,
                    settlement.amount().movePointRight(MoneyMath.SCALE).longValueExact());
        }
        return MoneyMath.fromMinorUnits(minor);
    }
}