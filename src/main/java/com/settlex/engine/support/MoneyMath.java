package com.settlex.engine.support;

import com.settlex.exception.CurrencyPrecisionException;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Central authority for every currency operation performed by the engines.
 *
 * <p>All settlement arithmetic is done in {@code long} minor units (cents) so that the
 * zero-sum detection in the bitmask solver is <em>exact</em> — no {@code BigDecimal}
 * comparison, no floating-point drift. {@link BigDecimal} is used only at the API boundary
 * for parsing and rendering.</p>
 *
 * <p>Scale is fixed at {@value #SCALE} decimals with {@link RoundingMode#HALF_EVEN}, the
 * banker's rounding mode, so that ties land on the nearest even cent and repeated
 * aggregation of half-cent values does not systematically drift upward.</p>
 */
public final class MoneyMath {

    /** Number of decimal places retained for all monetary values. */
    public static final int SCALE = 2;

    /** Rounding mode applied to every currency computation. */
    public static final RoundingMode ROUNDING_MODE = RoundingMode.HALF_EVEN;

    /** Multiplier converting major units to minor units. */
    static final BigDecimal MINOR_UNIT_SCALE = BigDecimal.valueOf(100L);

    /**
     * Upper bound on a single normalized amount, in major units. Chosen so that the minor
     * unit representation ({@code amount * 100}) stays comfortably inside {@code long}
     * range even after summing thousands of obligations.
     */
    public static final BigDecimal MAX_AMOUNT = new BigDecimal("1000000000000.00"); // 1e12

    /** Upper bound on the aggregate absolute value of a ledger, in major units. */
    public static final BigDecimal MAX_LEDGER_TOTAL = new BigDecimal("900000000000000.00"); // 9e14

    /** {@link #MAX_LEDGER_TOTAL} pre-converted to minor units for hot-loop range checks. */
    static final long MAX_LEDGER_TOTAL_MINOR = MAX_LEDGER_TOTAL.movePointRight(SCALE).longValueExact();

    private MoneyMath() {
        throw new AssertionError("Utility class");
    }

    /**
     * Rounds an amount onto the canonical currency scale using {@code HALF_EVEN}.
     *
     * @param amount value to normalize; must not be {@code null}
     * @return amount at two decimal places
     */
    public static BigDecimal normalize(BigDecimal amount) {
        return amount.setScale(SCALE, ROUNDING_MODE);
    }

    /**
     * Converts a monetary amount to exact minor units (cents).
     *
     * @throws CurrencyPrecisionException if the value cannot be represented without silent
     *                                     loss of precision or overflows {@code long}
     */
    public static long toMinorUnits(BigDecimal amount, int index, String field) {
        BigDecimal normalized = normalize(amount);

        if (normalized.signum() == 0) {
            throw new CurrencyPrecisionException(index, field, amount.toPlainString(),
                    "amount is below the smallest representable currency unit ("
                            + "0." + "0".repeat(SCALE) + ") once rounded with " + ROUNDING_MODE);
        }
        if (normalized.abs().compareTo(MAX_AMOUNT) > 0) {
            throw new CurrencyPrecisionException(index, field, amount.toPlainString(),
                    "amount exceeds the supported maximum of " + MAX_AMOUNT.toPlainString());
        }

        BigDecimal minor = normalized.movePointRight(SCALE);
        if (minor.abs().compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) > 0) {
            throw new CurrencyPrecisionException(index, field, amount.toPlainString(),
                    "amount overflows the 64-bit minor-unit representation");
        }
        return minor.longValueExact();
    }

    /** Renders minor units (cents) back into a two-decimal-place {@link BigDecimal}. */
    public static BigDecimal fromMinorUnits(long minorUnits) {
        return BigDecimal.valueOf(minorUnits, SCALE);
    }

    /** Renders minor units as plain text, e.g. {@code -12345 -> "-123.45"}. */
    public static String formatMinorUnits(long minorUnits) {
        return fromMinorUnits(minorUnits).toPlainString();
    }

    /** Converts nanoseconds to microseconds with two decimal places. */
    public static double nanosToMicros(long nanos) {
        return BigDecimal.valueOf(nanos)
                .divide(BigDecimal.valueOf(1_000L))
                .setScale(SCALE, ROUNDING_MODE)
                .doubleValue();
    }
}