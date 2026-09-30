package com.settlex.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Request payload for {@code POST /api/v1/settlements/optimize} and {@code /benchmark}.
 *
 * @param transactions the raw debt obligations; must be non-empty and itself balanced
 *                     (sum of all amounts credited equals sum of all amounts debited)
 * @param strategy     which optimization algorithm to apply
 */
public record SettlementRequest(

        @NotEmpty(message = "transactions must contain at least one obligation")
        @Size(max = 5000, message = "transactions must not exceed 5000 entries")
        @Valid
        List<Transaction> transactions,

        @NotNull(message = "strategy must not be null (expected one of [GREEDY, OPTIMAL_SUBSET])")
        OptimizationStrategy strategy
) {

    /** Convenience factory for the greedy heuristic. */
    public static SettlementRequest greedy(List<Transaction> transactions) {
        return new SettlementRequest(List.copyOf(transactions), OptimizationStrategy.GREEDY);
    }

    /** Convenience factory for the exact zero-sum partition solver. */
    public static SettlementRequest optimal(List<Transaction> transactions) {
        return new SettlementRequest(List.copyOf(transactions), OptimizationStrategy.OPTIMAL_SUBSET);
    }
}