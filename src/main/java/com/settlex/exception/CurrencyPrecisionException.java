package com.settlex.exception;

/**
 * Thrown when a monetary amount cannot be represented exactly on the fixed currency scale
 * used by the engines (two decimal places, {@code HALF_EVEN}) or exceeds the supported
 * magnitude.
 *
 * <p>Two distinct failure modes are reported:</p>
 * <ul>
 *   <li>sub-cent precision loss: an amount that rounds to {@code 0.00} at scale two, which
 *       would silently vanish from the ledger;</li>
 *   <li>range overflow: an amount whose minor-unit representation does not fit in a
 *       {@code long}, which would break the bitmask solver's exactness guarantee.</li>
 * </ul>
 */
public class CurrencyPrecisionException extends RuntimeException {

    private final int transactionIndex;
    private final String field;
    private final String rejected;
    private final String reason;

    public CurrencyPrecisionException(int transactionIndex, String field, String rejected, String reason) {
        super("transactions[" + transactionIndex + "]." + field + ": " + reason
                + " (received '" + rejected + "')");
        this.transactionIndex = transactionIndex;
        this.field = field;
        this.rejected = rejected;
        this.reason = reason;
    }

    public int transactionIndex() {
        return transactionIndex;
    }

    public String field() {
        return field;
    }

    public String rejected() {
        return rejected;
    }

    public String reason() {
        return reason;
    }
}