package com.settlex.exception;

/**
 * Thrown when a ledger's net positions do not sum to zero, which means no settlement of any
 * kind can discharge it.
 *
 * <p><strong>Why this is an invariant rather than a routine validation error.</strong> The
 * {@link com.settlex.dto.Transaction} model is bilateral: every obligation debits one
 * participant and credits another for exactly the same amount. Gross debits therefore always
 * equal gross credits, and a client cannot express an unbalanced ledger. This exception
 * exists so that a future change to the netting or normalization logic cannot quietly break
 * that guarantee — the one class of bug that would make every downstream settlement silently
 * wrong.</p>
 */
public class UnbalancedLedgerException extends RuntimeException {

    private final long residualMinorUnits;

    public UnbalancedLedgerException(long residualMinorUnits) {
        super("Ledger is unbalanced: net positions leave a residual of "
                + com.settlex.engine.support.MoneyMath.formatMinorUnits(residualMinorUnits)
                + " instead of 0.00");
        this.residualMinorUnits = residualMinorUnits;
    }

    /**
     * Signed {@code Σ(net balances)} in minor units. Non-zero indicates the netting logic is
     * producing impossible results.
     */
    public long residualMinorUnits() {
        return residualMinorUnits;
    }

    /** The residual rendered as a two-decimal-place monetary amount. */
    public java.math.BigDecimal residual() {
        return com.settlex.engine.support.MoneyMath.fromMinorUnits(residualMinorUnits);
    }
}