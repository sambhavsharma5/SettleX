package com.settlex.controller;

import com.settlex.dto.ApiError;
import com.settlex.exception.CurrencyPrecisionException;
import com.settlex.exception.UnbalancedLedgerException;
import com.settlex.exception.UnsupportedStrategyException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Direct unit coverage of the error envelope.
 *
 * <p>Two of these branches are defensive invariants that well-formed input cannot trigger —
 * {@link UnbalancedLedgerException} because the transaction model is bilateral, and
 * {@link IllegalStateException} because it only fires on an internal engine defect. Testing
 * the advice directly is the only way to prove those paths produce a safe response instead
 * of leaking a stack trace.</p>
 */
@DisplayName("GlobalExceptionHandler error envelope")
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private final MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/settlements/optimize");

    @Test
    @DisplayName("maps an unbalanced ledger to 400 and reports the residual")
    void mapsUnbalancedLedger() {
        ResponseEntity<ApiError> response =
                handler.handleUnbalanced(new UnbalancedLedgerException(3L), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ApiError body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.code()).isEqualTo("UNBALANCED_LEDGER");
        assertThat(body.message()).isEqualTo("Total debts must equal total credits");
        assertThat(body.path()).isEqualTo("/api/v1/settlements/optimize");
        assertThat(body.violations()).singleElement().satisfies(violation -> {
            assertThat(violation.field()).isEqualTo("transactions");
            assertThat(violation.message()).contains("0.03");
        });
        assertThat(body.timestamp()).isNotNull();
    }

    @Test
    @DisplayName("maps a currency precision failure to 400 with the offending index")
    void mapsPrecisionFailure() {
        CurrencyPrecisionException cause = new CurrencyPrecisionException(
                4, "amount", "0.004", "amount is below the smallest representable currency unit");

        ResponseEntity<ApiError> response = handler.handlePrecision(cause, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ApiError body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.code()).isEqualTo("CURRENCY_PRECISION_ERROR");
        assertThat(body.violations()).singleElement().satisfies(violation -> {
            assertThat(violation.field()).isEqualTo("transactions[4].amount");
            assertThat(violation.rejected()).isEqualTo("0.004");
        });
    }

    @Test
    @DisplayName("maps an engine strategy mismatch to 400")
    void mapsUnsupportedStrategy() {
        ResponseEntity<ApiError> response = handler.handleUnsupportedStrategy(
                new UnsupportedStrategyException("Greedy", "OPTIMAL_SUBSET", "GREEDY"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("UNSUPPORTED_STRATEGY");
        assertThat(response.getBody().message()).contains("OPTIMAL_SUBSET");
    }

    @Test
    @DisplayName("maps an engine invariant violation to 500 without leaking internals")
    void mapsEngineInvariantToServerError() {
        ResponseEntity<ApiError> response = handler.handleIllegalState(
                new IllegalStateException("secret internal detail: node 42 exploded"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        ApiError body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.code()).isEqualTo("ENGINE_INVARIANT_VIOLATION");
        assertThat(body.message())
                .as("the internal cause must not reach the client")
                .doesNotContain("node 42");
        assertThat(body.message()).doesNotContain("secret internal detail");
    }

    @Test
    @DisplayName("maps an illegal argument to 400")
    void mapsIllegalArgument() {
        ResponseEntity<ApiError> response = handler.handleIllegalArgument(
                new IllegalArgumentException("At least one transaction is required"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("ILLEGAL_ARGUMENT");
        assertThat(response.getBody().message()).contains("At least one transaction");
    }

    @Test
    @DisplayName("maps any other unexpected failure to 500 without leaking internals")
    void mapsUnexpectedToServerError() {
        ResponseEntity<ApiError> response = handler.handleUnexpected(
                new RuntimeException("connection string jdbc:postgres://user:hunter2@db"),
                request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        ApiError body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.code()).isEqualTo("INTERNAL_ERROR");
        assertThat(body.message()).doesNotContain("hunter2");
        assertThat(body.violations()).isEmpty();
    }

    @Test
    @DisplayName("honours the status carried by Spring's own 404 exceptions")
    void preservesNotFoundStatus() {
        ResponseEntity<ApiError> response = handler.handleUnexpected(
                new org.springframework.web.servlet.resource.NoResourceFoundException(
                        HttpMethod.GET, "/missing.html"),
                request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("NOT_FOUND");
        assertThat(response.getBody().message()).contains("No endpoint is mapped");
    }

    @Test
    @DisplayName("always produces an immutable, non-null violation list")
    void violationListIsAlwaysPresent() {
        ApiError error = ApiError.of(400, "CODE", "Bad Request", "message", "/path", null);

        assertThat(error.violations()).isNotNull().isEmpty();
        assertThatThrownByImmutability(error.violations());
    }

    private static void assertThatThrownByImmutability(java.util.List<ApiError.Violation> violations) {
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> violations.add(new ApiError.Violation("f", "m", "r")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("residual amounts render as plain decimal text")
    void residualRendersAsPlainText() {
        UnbalancedLedgerException exception = new UnbalancedLedgerException(-12345L);

        assertThat(exception.residual()).isEqualByComparingTo(new BigDecimal("-123.45"));
        assertThat(exception.residualMinorUnits()).isEqualTo(-12345L);
        assertThat(exception).hasMessageContaining("-123.45");
    }
}