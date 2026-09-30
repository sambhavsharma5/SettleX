package com.settlex.controller;

import com.settlex.dto.ApiError;
import com.settlex.exception.CurrencyPrecisionException;
import com.settlex.exception.UnbalancedLedgerException;
import com.settlex.exception.UnsupportedStrategyException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Translates every failure into the same {@link ApiError} envelope.
 *
 * <p>Three rules govern this class:</p>
 * <ul>
 *   <li><strong>Never leak a stack trace.</strong> Clients get a stable code plus field-level
 *       detail; the full cause is logged server-side against the request path.</li>
 *   <li><strong>Bean-validation failures are 400 with field paths.</strong> A client sending
 *       {@code amount: -5} learns exactly which element of which list failed.</li>
 *   <li><strong>Domain failures are 400 with the arithmetic exposed.</strong> An unbalanced
 *       ledger reports the debit total, the credit total and the gap, because a client
 *       debugging a settlement needs the discrepancy, not just a rejection.</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Bean validation on a {@code @Valid @RequestBody} argument failed. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleBeanValidation(MethodArgumentNotValidException ex,
                                                         HttpServletRequest request) {
        List<ApiError.Violation> violations = new ArrayList<>();
        ex.getBindingResult().getAllErrors().forEach(error -> {
            String field = error instanceof org.springframework.validation.FieldError fieldError
                    ? fieldError.getField()
                    : error.getObjectName();
            String rejected = error instanceof org.springframework.validation.FieldError fieldError
                    ? String.valueOf(fieldError.getRejectedValue())
                    : null;
            violations.add(new ApiError.Violation(field, error.getDefaultMessage(), rejected));
        });
        violations.sort(Comparator.comparing(ApiError.Violation::field));

        return build(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                "Request validation failed", request, violations);
    }

    /** Bean validation on individual method parameters (for example {@code @RequestParam}). */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleConstraintViolation(ConstraintViolationException ex,
                                                              HttpServletRequest request) {
        List<ApiError.Violation> violations = new ArrayList<>();
        for (ConstraintViolation<?> violation : ex.getConstraintViolations()) {
            violations.add(new ApiError.Violation(
                    String.valueOf(violation.getPropertyPath()),
                    violation.getMessage(),
                    String.valueOf(violation.getInvalidValue())));
        }
        violations.sort(Comparator.comparing(ApiError.Violation::field));

        return build(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                "Request validation failed", request, violations);
    }

    /**
     * A ledger whose net positions do not cancel. Unreachable from well-formed input because
     * the transaction model is bilateral; kept as a 400 because the failure is still driven
     * entirely by the data the caller supplied, and a caller hitting it has a real bug.
     */
    @ExceptionHandler(UnbalancedLedgerException.class)
    public ResponseEntity<ApiError> handleUnbalanced(UnbalancedLedgerException ex,
                                                     HttpServletRequest request) {
        log.warn("Ledger invariant violated on {}: {}", request.getRequestURI(), ex.getMessage());

        return build(HttpStatus.BAD_REQUEST, "UNBALANCED_LEDGER",
                "Total debts must equal total credits", request,
                List.of(new ApiError.Violation(
                        "transactions",
                        "Net positions leave a residual of " + plain(ex.residual())
                                + " instead of 0.00; every cent owed must be owed by somebody",
                        plain(ex.residual()))));
    }

    /**
     * A monetary value could not be represented on the two-decimal currency scale, either
     * because it rounds to zero or because it is too large. Reported distinctly from ordinary
     * validation so clients can tell "you sent a bad number" apart from "you sent too many digits".
     */
    @ExceptionHandler(CurrencyPrecisionException.class)
    public ResponseEntity<ApiError> handlePrecision(CurrencyPrecisionException ex,
                                                    HttpServletRequest request) {
        log.debug("Rejected imprecise amount on {}: {}", request.getRequestURI(), ex.getMessage());

        return build(HttpStatus.BAD_REQUEST, "CURRENCY_PRECISION_ERROR",
                "Monetary amount cannot be represented exactly", request,
                List.of(new ApiError.Violation(
                        "transactions[" + ex.transactionIndex() + "]." + ex.field(),
                        ex.reason(), ex.rejected())));
    }

    /** An engine was handed a strategy it does not implement. */
    @ExceptionHandler(UnsupportedStrategyException.class)
    public ResponseEntity<ApiError> handleUnsupportedStrategy(UnsupportedStrategyException ex,
                                                              HttpServletRequest request) {
        log.warn("Strategy mismatch on {}: {}", request.getRequestURI(), ex.getMessage());
        return build(HttpStatus.BAD_REQUEST, "UNSUPPORTED_STRATEGY", ex.getMessage(), request, List.of());
    }

    /** Malformed JSON, an unparsable {@code BigDecimal}, or an unknown enum constant. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadableBody(HttpMessageNotReadableException ex,
                                                         HttpServletRequest request) {
        log.debug("Unreadable body on {}: {}", request.getRequestURI(), ex.getMessage());
        return build(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST",
                "Request body is missing or malformed; check that every field is present, "
                        + "amounts are valid decimals and strategy is one of [GREEDY, OPTIMAL_SUBSET]",
                request, List.of());
    }

    /** Path or query parameter of the wrong type. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException ex,
                                                       HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "TYPE_MISMATCH",
                "Parameter '" + ex.getName() + "' has an unusable value", request,
                List.of(new ApiError.Violation(ex.getName(),
                        "expected type " + (ex.getRequiredType() == null
                                ? "unknown" : ex.getRequiredType().getSimpleName()),
                        ex.getValue() == null ? null : String.valueOf(ex.getValue()))));
    }

    /** A required request parameter is absent. */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> handleMissingParam(MissingServletRequestParameterException ex,
                                                       HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "MISSING_PARAMETER",
                "Required parameter '" + ex.getParameterName() + "' is missing", request,
                List.of(new ApiError.Violation(ex.getParameterName(), "is required", null)));
    }

    /** Wrong HTTP verb on a known path. */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex,
                                                             HttpServletRequest request) {
        return build(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", ex.getMessage(), request, List.of());
    }

    /** Illegal arguments raised by the service layer, such as an unknown sample id. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArgument(IllegalArgumentException ex,
                                                          HttpServletRequest request) {
        log.debug("Illegal argument on {}: {}", request.getRequestURI(), ex.getMessage());
        return build(HttpStatus.BAD_REQUEST, "ILLEGAL_ARGUMENT", ex.getMessage(), request, List.of());
    }

    /**
     * Any engine invariant violation. These are server defects, so they surface as
     * {@code 500} and are logged with the full stack trace rather than summarised.
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiError> handleIllegalState(IllegalStateException ex,
                                                       HttpServletRequest request) {
        log.error("Engine invariant violated on {}", request.getRequestURI(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "ENGINE_INVARIANT_VIOLATION",
                "An internal engine invariant was violated; the ledger could not be settled "
                        + "reliably", request, List.of());
    }

    /** Last-resort catch-all so no stack trace ever reaches a client. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest request) {
        /*
         * Spring's own exceptions (NoResourceFoundException, NoHandlerFoundException, ...)
         * carry the HTTP status they intend to produce. Swallowing them into a blanket 500
         * would turn every 404 into a 500, so they are honoured verbatim first.
         */
        if (ex instanceof org.springframework.web.ErrorResponse errorResponse) {
            HttpStatus status = HttpStatus.resolve(errorResponse.getStatusCode().value());
            HttpStatus resolved = status == null ? HttpStatus.INTERNAL_SERVER_ERROR : status;
            if (resolved.is5xxServerError()) {
                log.error("Server-side failure on {}", request.getRequestURI(), ex);
            } else {
                log.debug("Client-side failure on {}: {}", request.getRequestURI(), ex.getMessage());
            }
            return build(resolved, codeFor(resolved), messageFor(resolved, ex),
                    request, List.of());
        }

        log.error("Unhandled failure on {}", request.getRequestURI(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "An unexpected internal error occurred", request, List.of());
    }

    private static String codeFor(HttpStatus status) {
        return switch (status) {
            case NOT_FOUND -> "NOT_FOUND";
            case METHOD_NOT_ALLOWED -> "METHOD_NOT_ALLOWED";
            case UNSUPPORTED_MEDIA_TYPE -> "UNSUPPORTED_MEDIA_TYPE";
            case BAD_REQUEST -> "BAD_REQUEST";
            default -> status.is5xxServerError() ? "INTERNAL_ERROR" : "REQUEST_REJECTED";
        };
    }

    private static String messageFor(HttpStatus status, Exception ex) {
        if (status == HttpStatus.NOT_FOUND) {
            return "No endpoint is mapped to the requested path";
        }
        return ex.getMessage() == null ? status.getReasonPhrase() : ex.getMessage();
    }

    private ResponseEntity<ApiError> build(HttpStatus status, String code, String message,
                                           HttpServletRequest request,
                                           List<ApiError.Violation> violations) {
        ApiError body = ApiError.of(
                status.value(),
                code,
                status.getReasonPhrase(),
                message,
                request.getRequestURI(),
                violations);
        return ResponseEntity.status(status).body(body);
    }

    private static String plain(BigDecimal value) {
        return value == null ? "0.00" : value.toPlainString();
    }
}