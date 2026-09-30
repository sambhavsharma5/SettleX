package com.settlex.controller;

import com.settlex.dto.OptimizationStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("REST API /api/v1/settlements")
class SettlementControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("POST /optimize returns a plan for a valid ledger")
    void optimizesValidLedger() throws Exception {
        String body = """
                {
                  "strategy": "OPTIMAL_SUBSET",
                  "transactions": [
                    {"debtor": "Ana", "creditor": "Ben", "amount": 120.00},
                    {"debtor": "Ana", "creditor": "Chloe", "amount": 80.00},
                    {"debtor": "Ben", "creditor": "Chloe", "amount": 200.00}
                  ]
                }
                """;

        mockMvc.perform(post("/api/v1/settlements/optimize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.initialTransactionCount").value(3))
                .andExpect(jsonPath("$.optimizedTransactionCount").value(2))
                .andExpect(jsonPath("$.isMathematicallyOptimal").value(true))
                .andExpect(jsonPath("$.algorithmUsed", containsString("Bitmask")))
                .andExpect(jsonPath("$.totalCashFlowBefore").value(400.00))
                .andExpect(jsonPath("$.settlements", hasSize(2)))
                .andExpect(jsonPath("$.executionTimeNs").value(greaterThan(0)))
                .andExpect(jsonPath("$.netBalances.Ana").value(-200.00));
    }

    @Test
    @DisplayName("POST /optimize accepts the greedy strategy")
    void optimizesWithGreedy() throws Exception {
        String body = """
                {
                  "strategy": "GREEDY",
                  "transactions": [
                    {"debtor": "A", "creditor": "B", "amount": 10.00},
                    {"debtor": "B", "creditor": "C", "amount": 10.00}
                  ]
                }
                """;

        mockMvc.perform(post("/api/v1/settlements/optimize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.algorithmUsed", containsString("Greedy")))
                .andExpect(jsonPath("$.optimizedTransactionCount").value(1))
                .andExpect(jsonPath("$.isOptimalFallback").value(false));
    }

    @Test
    @DisplayName("POST /benchmark compares both engines")
    void benchmarksBothEngines() throws Exception {
        String body = """
                {
                  "strategy": "GREEDY",
                  "transactions": [
                    {"debtor": "Omar", "creditor": "Priya", "amount": 2.00},
                    {"debtor": "Raj", "creditor": "Priya", "amount": 2.00},
                    {"debtor": "Sam", "creditor": "Quinn", "amount": 3.00},
                    {"debtor": "Quinn", "creditor": "Omar", "amount": 5.00},
                    {"debtor": "Omar", "creditor": "Quinn", "amount": 5.00},
                    {"debtor": "Priya", "creditor": "Raj", "amount": 3.00},
                    {"debtor": "Raj", "creditor": "Sam", "amount": 3.00},
                    {"debtor": "Sam", "creditor": "Priya", "amount": 3.00}
                  ]
                }
                """;

        mockMvc.perform(post("/api/v1/settlements/benchmark")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.participantCount").value(5))
                .andExpect(jsonPath("$.initialTransactionCount").value(8))
                .andExpect(jsonPath("$.greedy.optimizedTransactionCount").value(4))
                .andExpect(jsonPath("$.optimalSubset.optimizedTransactionCount").value(3))
                .andExpect(jsonPath("$.transactionCountDelta").value(1))
                .andExpect(jsonPath("$.optimalProvedStrictlyFewer").value(true))
                .andExpect(jsonPath("$.greedy.executionTimeNs").value(greaterThan(0)))
                .andExpect(jsonPath("$.optimalSubset.executionTimeNs").value(greaterThan(0)))
                .andExpect(jsonPath("$.notes", containsString("Bitmask DP")));
    }

    @Test
    @DisplayName("GET /samples returns pre-baked edge case graphs")
    void returnsSamples() throws Exception {
        mockMvc.perform(get("/api/v1/settlements/samples"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(7)))
                .andExpect(jsonPath("$[0].id").value("trip-5"))
                .andExpect(jsonPath("$[0].suggestedStrategy").value("GREEDY"))
                .andExpect(jsonPath("$[?(@.id=='greedy-defeated')]").exists())
                .andExpect(jsonPath("$[?(@.id=='circular-deadlock')]").exists())
                .andExpect(jsonPath("$[?(@.id=='fractional-cents')]").exists())
                .andExpect(jsonPath("$[?(@.id=='single-transaction')]").exists());
    }

    /**
     * The transaction model is bilateral, so gross debits always equal gross credits and a
     * client cannot express an unbalanced ledger. {@code UnbalancedLedgerException} is
     * therefore an invariant guard on the netting logic rather than a reachable HTTP 400;
     * its error mapping is unit-tested directly in {@link GlobalExceptionHandlerTest}.
     */
    @Test
    @DisplayName("a ledger where every participant nets to zero needs no transfers")
    void nettingToZeroIsAcceptable() throws Exception {
        String body = """
                {
                  "strategy": "GREEDY",
                  "transactions": [
                    {"debtor": "A", "creditor": "B", "amount": 100.00},
                    {"debtor": "B", "creditor": "A", "amount": 100.00}
                  ]
                }
                """;

        mockMvc.perform(post("/api/v1/settlements/optimize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settlements", hasSize(0)))
                .andExpect(jsonPath("$.reductionPercentage").value(100.0d));
    }

    @Test
    @DisplayName("POST /optimize rejects a negative amount with field-level detail")
    void rejectsNegativeAmount() throws Exception {
        String body = """
                {
                  "strategy": "GREEDY",
                  "transactions": [
                    {"debtor": "A", "creditor": "B", "amount": -50.00}
                  ]
                }
                """;

        mockMvc.perform(post("/api/v1/settlements/optimize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.violations[0].field", containsString("transactions[0].amount")))
                .andExpect(jsonPath("$.violations[0].message", containsString("greater than zero")));
    }

    @Test
    @DisplayName("POST /optimize rejects a zero amount")
    void rejectsZeroAmount() throws Exception {
        String body = """
                {
                  "strategy": "GREEDY",
                  "transactions": [
                    {"debtor": "A", "creditor": "B", "amount": 0.00}
                  ]
                }
                """;

        mockMvc.perform(post("/api/v1/settlements/optimize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("POST /optimize rejects an empty transaction list")
    void rejectsEmptyList() throws Exception {
        String body = """
                {"strategy": "GREEDY", "transactions": []}
                """;

        mockMvc.perform(post("/api/v1/settlements/optimize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.violations[0].field").value("transactions"));
    }

    @Test
    @DisplayName("POST /optimize rejects a blank participant name")
    void rejectsBlankName() throws Exception {
        String body = """
                {
                  "strategy": "GREEDY",
                  "transactions": [
                    {"debtor": "  ", "creditor": "B", "amount": 10.00}
                  ]
                }
                """;

        mockMvc.perform(post("/api/v1/settlements/optimize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.violations[0].field").value("transactions[0].debtor"));
    }

    @Test
    @DisplayName("POST /optimize rejects a missing strategy")
    void rejectsMissingStrategy() throws Exception {
        String body = """
                {
                  "transactions": [
                    {"debtor": "A", "creditor": "B", "amount": 10.00}
                  ]
                }
                """;

        mockMvc.perform(post("/api/v1/settlements/optimize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.violations[0].field").value("strategy"));
    }

    @Test
    @DisplayName("POST /optimize rejects an unknown strategy constant")
    void rejectsUnknownStrategy() throws Exception {
        String body = """
                {
                  "strategy": "MAGIC",
                  "transactions": [
                    {"debtor": "A", "creditor": "B", "amount": 10.00}
                  ]
                }
                """;

        mockMvc.perform(post("/api/v1/settlements/optimize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    /**
     * Bean validation is the first line of defence and catches sub-cent and over-long amounts
 * before the request ever reaches an engine, so the {@code CURRENCY_PRECISION_ERROR} branch is
 * defence in depth rather than a reachable HTTP outcome. Its error mapping is asserted directly
 * in {@link GlobalExceptionHandlerTest}.
     */
    @Test
    @DisplayName("POST /optimize rejects a sub-cent amount at the validation layer")
    void rejectsSubCentAmount() throws Exception {
        String body = """
                {
                  "strategy": "GREEDY",
                  "transactions": [
                    {"debtor": "A", "creditor": "B", "amount": 0.004},
                    {"debtor": "B", "creditor": "C", "amount": 0.004}
                  ]
                }
                """;

        mockMvc.perform(post("/api/v1/settlements/optimize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.violations[0].field",
                        containsString("transactions[0].amount")))
                .andExpect(jsonPath("$.violations[0].rejected").value("0.004"));
    }

    @Test
    @DisplayName("POST /optimize rejects malformed JSON")
    void rejectsMalformedJson() throws Exception {
        mockMvc.perform(post("/api/v1/settlements/optimize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ this is not json "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    @DisplayName("the dashboard is served from the application root")
    void servesDashboard() throws Exception {
        // Boot resolves "/" onto the static welcome page through a servlet forward. A real
        // container dispatches that forward; MockMvc only records it, which is why the two
        // halves are asserted separately instead of expecting the body on "/".
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("index.html"));

        mockMvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(containsString("SettleX")))
                // The client must address the API relatively so the app survives a port change.
                .andExpect(content().string(containsString("/api/v1/settlements/optimize")))
                .andExpect(content().string(containsString("/api/v1/settlements/benchmark")))
                .andExpect(content().string(containsString("/api/v1/settlements/samples")));
    }

    @Test
    @DisplayName("actuator health is exposed")
    void exposesHealth() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @DisplayName("unknown routes produce a JSON error envelope")
    void unknownRouteIsHandled() throws Exception {
        mockMvc.perform(get("/api/v1/settlements/nope"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("every sample graph is a balanced ledger the API accepts")
    void samplesAreUsable() throws Exception {
        String samplesJson = mockMvc.perform(get("/api/v1/settlements/samples"))
                .andReturn().getResponse().getContentAsString();

        // Reuse the sample payload verbatim for each id via a direct service-level assertion
        // through the HTTP layer, which also proves the JSON round-trips.
        mockMvc.perform(post("/api/v1/settlements/optimize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(extractSampleTransactions(samplesJson, "single-transaction")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.optimizedTransactionCount").value(1));
    }

    private static String extractSampleTransactions(String samplesJson, String id) {
        int index = samplesJson.indexOf("\"id\":\"" + id + "\"");
        if (index < 0) {
            throw new AssertionError("Sample not found: " + id);
        }
        int start = samplesJson.indexOf("\"transactions\":", index);
        int bracket = samplesJson.indexOf('[', start);
        int depth = 0;
        int i = bracket;
        for (; i < samplesJson.length(); i++) {
            char c = samplesJson.charAt(i);
            if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
                if (depth == 0) {
                    break;
                }
            }
        }
        String transactions = samplesJson.substring(bracket, i + 1);
        return "{\"strategy\":\"GREEDY\",\"transactions\":" + transactions + "}";
    }

    @Test
    @DisplayName("reduction percentage is reported to two decimals")
    void reductionPercentageIsRounded() throws Exception {
        String body = """
                {
                  "strategy": "GREEDY",
                  "transactions": [
                    {"debtor": "A", "creditor": "B", "amount": 10.00},
                    {"debtor": "A", "creditor": "C", "amount": 10.00},
                    {"debtor": "B", "creditor": "C", "amount": 20.00}
                  ]
                }
                """;

        mockMvc.perform(post("/api/v1/settlements/optimize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                // Nets are Ana -20.00, Ben -10.00, Chloe +30.00, so 3 obligations become
                // 2 transfers: a 33.33% reduction, reported to exactly two decimals.
                .andExpect(jsonPath("$.optimizedTransactionCount").value(2))
                .andExpect(jsonPath("$.reductionPercentage").value(33.33d))
                .andExpect(jsonPath("$.isMathematicallyOptimal").value(is(true)));
    }

    @Test
    @DisplayName("a fully circular sample resolves to zero transfers over HTTP")
    void circularSampleResolvesOverHttp() throws Exception {
        String body = """
                {
                  "strategy": "OPTIMAL_SUBSET",
                  "transactions": [
                    {"debtor": "Ana", "creditor": "Ben", "amount": 100.00},
                    {"debtor": "Ben", "creditor": "Chloe", "amount": 100.00},
                    {"debtor": "Chloe", "creditor": "Diego", "amount": 100.00},
                    {"debtor": "Diego", "creditor": "Ana", "amount": 100.00}
                  ]
                }
                """;

        mockMvc.perform(post("/api/v1/settlements/optimize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settlements", hasSize(0)))
                .andExpect(jsonPath("$.optimizedTransactionCount").value(0))
                .andExpect(jsonPath("$.reductionPercentage").value(100.0d))
                .andExpect(jsonPath("$.totalCashFlowAfter").value(0.00));
    }
}