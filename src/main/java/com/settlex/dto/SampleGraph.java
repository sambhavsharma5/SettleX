package com.settlex.dto;

import jakarta.validation.Valid;

import java.util.List;

/**
 * A pre-baked edge-case ledger offered by {@code GET /api/v1/settlements/samples} so the
 * dashboard can populate its transaction builder with one click.
 *
 * @param id                 stable machine key, used by the UI dropdown
 * @param name               display title
 * @param description        one-line explanation of the structural property being exercised
 * @param expectedOutcome    what a correct engine should demonstrate for this graph
 * @param suggestedStrategy  the strategy most likely to show an interesting result
 * @param transactions       the obligation graph itself
 */
public record SampleGraph(
        String id,
        String name,
        String description,
        String expectedOutcome,
        OptimizationStrategy suggestedStrategy,
        @Valid List<Transaction> transactions
) {
}