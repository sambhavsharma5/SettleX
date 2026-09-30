package com.settlex.engine;

import com.settlex.dto.OptimizationStrategy;
import com.settlex.dto.SettlementPlan;
import com.settlex.dto.Transaction;

import java.util.List;

/**
 * Contract every settlement algorithm implements.
 *
 * <p>Implementations are required to be <strong>pure and deterministic</strong>: the same
 * transaction list must always yield an identical settlement list. That property is what
 * allows two engines to be benchmarked against each other and what makes the plan safe to
 * cache or diff.</p>
 *
 * <p>Implementations must be safe for concurrent use.</p>
 */
public interface SettlementEngine {

    /**
     * Computes a minimal set of transfers that discharges every obligation.
     *
     * @param transactions the raw obligation graph; must be non-empty, positively valued
     *                      and balanced
     * @param strategy     the strategy the caller wants applied; must equal
     *                      {@link #supportedStrategy()}
     * @return an immutable {@link com.settlex.dto.SettlementPlan}
     * @throws com.settlex.exception.UnsupportedStrategyException if the strategy is not this
     *                                                               engine's own
     * @throws com.settlex.exception.UnbalancedLedgerException    if debits and credits differ
     * @throws com.settlex.exception.CurrencyPrecisionException  if an amount cannot be
     *                                                               represented exactly
     */
    SettlementPlan minimize(List<Transaction> transactions, OptimizationStrategy strategy);

    /** The single strategy this engine implements. */
    OptimizationStrategy supportedStrategy();

    /** Stable, human-readable identifier reported as {@code algorithmUsed}. */
    default String name() {
        return getClass().getSimpleName();
    }
}