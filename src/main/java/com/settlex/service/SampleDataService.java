package com.settlex.service;

import com.settlex.dto.OptimizationStrategy;
import com.settlex.dto.SampleGraph;
import com.settlex.dto.Transaction;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Curated edge-case ledgers served by {@code GET /api/v1/settlements/samples}.
 *
 * <p>Each sample isolates one structural property of the algorithms rather than merely looking
 * plausible: a pure deadlock that nets to nothing, an island structure, the specific balance
 * configuration where greedy provably loses, a hub-and-spoke topology, a
 * half-even-rounding trap, and the degenerate single-edge case. Every sample is asserted
 * balanced and its documented outcome verified by the test suite.</p>
 */
@Service
public class SampleDataService {

    private final List<SampleGraph> samples = buildSamples();

    /** Immutable list of pre-baked graphs, in presentation order. */
    public List<SampleGraph> all() {
        return samples;
    }

    /** Looks up one sample by id, case-insensitively. */
    public SampleGraph byId(String id) {
        return samples.stream()
                .filter(sample -> sample.id().equalsIgnoreCase(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown sample id '" + id + "'; available ids are "
                                + samples.stream().map(SampleGraph::id).toList()));
    }

    private static List<SampleGraph> buildSamples() {
        List<SampleGraph> graphs = new ArrayList<>();

        graphs.add(new SampleGraph(
                "trip-5",
                "5-Person Trip",
                "Five friends splitting a weekend: a dense ten-obligation web in which every "
                        + "participant both owes and is owed.",
                "Nets to 5 active positions and settles in exactly 4 transfers — the N-1 bound. "
                        + "No proper zero-sum subset exists, so greedy is already provably optimal.",
                OptimizationStrategy.GREEDY,
                List.of(
                        tx("Ana", "Ben", "120.00"),
                        tx("Ana", "Chloe", "75.50"),
                        tx("Ben", "Chloe", "45.25"),
                        tx("Ben", "Diego", "60.00"),
                        tx("Chloe", "Diego", "30.75"),
                        tx("Chloe", "Elif", "55.00"),
                        tx("Diego", "Elif", "40.00"),
                        tx("Elif", "Ana", "90.00"),
                        tx("Ana", "Elif", "25.50"),
                        tx("Diego", "Ana", "35.00"))));

        graphs.add(new SampleGraph(
                "circular-deadlock",
                "Circular 4-Party Deadlock",
                "Ana to Ben to Chloe to Diego to Ana at equal amounts. Every obligation is "
                        + "individually real yet collectively meaningless: all four net to zero.",
                "0 transfers. Nobody pays anybody; the entire cycle is economically dead weight. "
                        + "All four participants are eliminated during netting.",
                OptimizationStrategy.OPTIMAL_SUBSET,
                List.of(
                        tx("Ana", "Ben", "100.00"),
                        tx("Ben", "Chloe", "100.00"),
                        tx("Chloe", "Diego", "100.00"),
                        tx("Diego", "Ana", "100.00"))));

        graphs.add(new SampleGraph(
                "disjoint-islands",
                "Two Independent Families",
                "Two groups with no obligation crossing between them: {Ravi, Sana, Imran} and "
                        + "{Mia, Noah}. Each group nets to zero internally, so no money should ever "
                        + "move between them.",
                "Bitmask DP finds k=2 independent zero-sum islands and settles in 3 transfers "
                        + "(the provable N-k = 5-2), down from 7 obligations.",
                OptimizationStrategy.OPTIMAL_SUBSET,
                List.of(
                        tx("Ravi", "Sana", "30.00"),
                        tx("Ravi", "Imran", "20.00"),
                        tx("Sana", "Imran", "5.00"),
                        tx("Imran", "Sana", "5.00"),
                        tx("Mia", "Noah", "80.00"),
                        tx("Imran", "Mia", "10.00"),
                        tx("Mia", "Imran", "10.00"))));

        graphs.add(new SampleGraph(
                "greedy-defeated",
                "Greedy Trap: Island Structure Greedy Destroys",
                "Net positions are Quinn +3.00, Priya +4.00 against Sam -3.00, Omar -2.00, Raj -2.00. "
                        + "The exact optimum is two islands — {Quinn, Sam} and {Priya, Omar, Raj} — "
                        + "but greedy's largest-with-largest pairing forces Quinn to pay through "
                        + "Sam before Priya is even touched.",
                "Bitmask DP emits 3 transfers and proves 3 is minimal; the greedy heuristic needs 4. "
                        + "This is the sample where the exact solver earns its keep.",
                OptimizationStrategy.OPTIMAL_SUBSET,
                List.of(
                        tx("Omar", "Priya", "2.00"),
                        tx("Raj", "Priya", "2.00"),
                        tx("Sam", "Quinn", "3.00"),
                        tx("Quinn", "Omar", "5.00"),
                        tx("Omar", "Quinn", "5.00"),
                        tx("Priya", "Raj", "3.00"),
                        tx("Raj", "Sam", "3.00"),
                        tx("Sam", "Priya", "3.00"))));

        graphs.add(new SampleGraph(
                "star-topology",
                "Star Topology: One Hub Debtor",
                "A single spendthrill owes five creditors five different amounts. Exercises the "
                        + "path where the debtor heap is drained against a creditor heap that never "
                        + "empties until the last round.",
                "Exactly 5 transfers and a 0% reduction — the input is already minimal, and a "
                        + "0% reduction is reported honestly rather than hidden.",
                OptimizationStrategy.GREEDY,
                List.of(
                        tx("Kai", "Lena", "220.00"),
                        tx("Kai", "Mateo", "175.25"),
                        tx("Kai", "Nina", "98.75"),
                        tx("Kai", "Omar", "64.00"),
                        tx("Kai", "Tess", "41.99"))));

        graphs.add(new SampleGraph(
                "fractional-cents",
                "Precision Stress: Half-Even Rounding",
                "Amounts carrying sub-cent digits that must be snapped by HALF_EVEN before any "
                        + "arithmetic happens: 10.005 rounds to 10.00 (half to even, ties down) "
                        + "while 20.015 rounds to 20.02 (ties up to the even digit).",
                "Every amount lands on an exact cent, the ledger still balances to 0.00, and six "
                        + "obligations collapse to three transfers with no rounding residue.",
                OptimizationStrategy.GREEDY,
                List.of(
                        tx("Ada", "Grace", "10.005"),
                        tx("Ada", "Alan", "20.015"),
                        tx("Grace", "Alan", "10.005"),
                        tx("Alan", "Ada", "10.005"),
                        tx("Grace", "Alan", "10.005"),
                        tx("Ada", "Grace", "20.005"))));

        graphs.add(new SampleGraph(
                "single-transaction",
                "Single Transaction (Idempotency)",
                "The degenerate case: exactly one obligation, which is already minimal.",
                "1 transfer and a 0% reduction. The optimizer reports that it cannot beat the "
                        + "input it was given instead of inventing work.",
                OptimizationStrategy.GREEDY,
                List.of(tx("Ivy", "Jon", "42.50"))));

        return List.copyOf(graphs);
    }

    private static Transaction tx(String debtor, String creditor, String amount) {
        return new Transaction(debtor, creditor, new BigDecimal(amount));
    }
}