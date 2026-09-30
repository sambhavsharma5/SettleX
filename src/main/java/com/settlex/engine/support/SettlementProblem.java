package com.settlex.engine.support;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * An immutable, pre-validated settlement problem handed to an engine.
 *
 * <p>Building one of these is where every precondition is enforced — balance check,
 * self-obligation rejection, currency precision. Once a {@code SettlementProblem} exists,
 * an engine can assume the ledger is well-posed and work purely in minor units.</p>
 *
 * @param positions            every participant with a non-zero net balance, in first-seen
 *                             order (deterministic iteration for both engines)
 * @param creditors            the creditor half, as a stable snapshot list
 * @param debtors              the debtor half, as a stable snapshot list
 * @param netBalances          participant to net position in major units, including
 *                             participants that netted out to exactly zero
 * @param initialTransactionCount number of raw obligations
 * @param totalCashFlow        sum of all obligation amounts in minor units
 */
public record SettlementProblem(
        List<Position> positions,
        List<Position> creditors,
        List<Position> debtors,
        Map<String, BigDecimal> netBalances,
        int initialTransactionCount,
        long totalCashFlow
) {

    public SettlementProblem {
        positions = List.copyOf(positions);
        creditors = List.copyOf(creditors);
        debtors = List.copyOf(debtors);
        netBalances = Map.copyOf(netBalances);
    }

    /** Number of participants carrying a non-zero net balance. */
    public int activeParticipantCount() {
        return positions.size();
    }

    /** Number of participants that netted out to exactly zero. */
    public int settledParticipantCount() {
        return netBalances.size() - positions.size();
    }

    /** Total exposure owed by debtors, in minor units. Equals total creditor credit. */
    public long totalOutstandingMinor() {
        long total = 0L;
        for (Position debtor : debtors) {
            total += debtor.debt();
        }
        return total;
    }
}