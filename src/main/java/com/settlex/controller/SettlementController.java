package com.settlex.controller;

import com.settlex.dto.BenchmarkResult;
import com.settlex.dto.SampleGraph;
import com.settlex.dto.SettlementPlan;
import com.settlex.dto.SettlementRequest;
import com.settlex.service.SampleDataService;
import com.settlex.service.SettlementService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Public API surface, versioned under {@code /api/v1}.
 *
 * <p>All endpoints return {@code 200} on success and delegate every failure to
 * {@link GlobalExceptionHandler} so error shape is uniform across the whole surface.</p>
 */
@RestController
@RequestMapping(path = "/api/v1/settlements", produces = MediaType.APPLICATION_JSON_VALUE)
public class SettlementController {

    private final SettlementService settlementService;
    private final SampleDataService sampleDataService;

    public SettlementController(SettlementService settlementService,
                                SampleDataService sampleDataService) {
        this.settlementService = settlementService;
        this.sampleDataService = sampleDataService;
    }

    /**
     * Optimizes a debt graph with the requested strategy.
     *
     * <p>Request body:</p>
     * <pre>{@code
     * {
     *   "strategy": "OPTIMAL_SUBSET",
     *   "transactions": [
     *     { "debtor": "Ana", "creditor": "Ben", "amount": 120.00 },
     *     { "debtor": "Ben",  "creditor": "Ana", "amount":  40.00 }
     *   ]
     * }
     * }</pre>
     *
     * <p>The ledger must balance: the sum of every {@code amount} credited must equal the sum
     * debited. Violations are reported as {@code 400} with field-level detail.</p>
     */
    @PostMapping(path = "/optimize", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SettlementPlan> optimize(@Valid @RequestBody SettlementRequest request) {
        return ResponseEntity.ok(settlementService.optimize(request));
    }

    /**
     * Runs the greedy heuristic and the exact bitmask solver concurrently over the same
     * ledger and returns a head-to-head comparison of transfer count, cash flow and runtime.
     *
     * <p>The {@code strategy} field of the body is ignored here — both engines always run.</p>
     */
    @PostMapping(path = "/benchmark", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<BenchmarkResult> benchmark(@Valid @RequestBody SettlementRequest request) {
        return ResponseEntity.ok(settlementService.benchmark(request));
    }

    /**
     * Pre-baked edge-case graphs for one-click dashboard loading.
     *
     * <p>Each entry carries a suggested strategy and an explanation of the structural property
     * it exercises, so the UI can label what the user is looking at.</p>
     */
    @GetMapping("/samples")
    public ResponseEntity<List<SampleGraph>> samples() {
        return ResponseEntity.ok(sampleDataService.all());
    }
}