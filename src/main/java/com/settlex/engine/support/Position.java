package com.settlex.engine.support;

import java.math.BigDecimal;
import java.util.Comparator;

/**
 * A participant's net position, expressed in exact minor units (cents).
 *
 * <p>Sign convention follows the specification: {@code Net = Σ(credits) - Σ(debits)}.
 * A positive balance means the participant is a <em>creditor</em> awaiting money; a
 * negative balance means a <em>debtor</em> who must pay.</p>
 *
 * <p>Participants whose net balance settles to exactly zero are eliminated entirely — they
 * neither pay nor receive, so including them would only inflate the theoretical minimum.</p>
 *
 * @param participant display name of the party
 * @param balance     signed net position in minor units; never zero
 */
public record Position(String participant, long balance) {

    /**
     * Orders by descending absolute exposure, breaking ties alphabetically. Using a total
     * order is what makes both engines fully deterministic: two runs over identical input
     * always produce byte-identical settlement lists.
     */
    public static final Comparator<Position> BY_MAGNITUDE_DESC = Comparator
            .comparingLong((Position p) -> Math.abs(p.balance()))
            .thenComparing(Position::participant)
            .reversed();

    public Position {
        if (balance == 0L) {
            throw new IllegalArgumentException(
                    "Position for '" + participant + "' has a zero balance; zero-balance "
                            + "participants must be excluded before constructing positions");
        }
    }

    public boolean isCreditor() {
        return balance > 0L;
    }

    public boolean isDebtor() {
        return balance < 0L;
    }

    /** Amount this participant is owed, in minor units. Zero for debtors. */
    public long credit() {
        return Math.max(balance, 0L);
    }

    /** Amount this participant owes, in minor units, always non-negative. */
    public long debt() {
        return Math.abs(Math.min(balance, 0L));
    }

    /**
     * Returns this position with {@code settled} minor units knocked off, or {@code null}
     * when the residual is exactly zero. Callers must handle {@code null} by simply not
     * re-queueing the participant — there is no remaining exposure to settle.
     *
     * <p>Implemented by subtracting from the magnitude and then restoring the original sign.
     * That framing matters: a creditor's balance is {@code +c} and settles to {@code c - s},
     * whereas a debtor's balance is {@code -d} and settles to {@code -(d - s)}. Writing it
     * as a plain {@code balance - settled} is correct for the first case and silently
     * doubles the debt in the second, so the sign is reapplied explicitly instead.</p>
     *
     * @param settled minor units to remove; must be strictly positive and must not exceed the
     *                magnitude of {@link #balance()}
     * @throws IllegalArgumentException if {@code settled} is not positive or exceeds the
     *                                  outstanding exposure
     * @throws ArithmeticException     if the caller attempts to settle more than is owed,
     *                                  which would flip the participant to the wrong side
     */
    public Position reduce(long settled) {
        if (settled <= 0L) {
            // Settling nothing is never meaningful and would hand back an identical position.
            // A greedy caller that re-queues such a position loops forever, so this is an
            // invariant of the algorithm rather than an input concern.
            throw new IllegalArgumentException(
                    "Cannot settle a non-positive amount: " + settled);
        }

        long magnitude = Math.abs(balance);
        if (settled > magnitude) {
            throw new IllegalArgumentException(
                    "Cannot settle " + MoneyMath.formatMinorUnits(settled)
                            + " against an outstanding exposure of only "
                            + MoneyMath.formatMinorUnits(magnitude)
                            + " for '" + participant + "'");
        }

        long residual = magnitude - settled;
        if (residual == 0L) {
            return null;
        }
        return new Position(participant, isCreditor() ? residual : -residual);
    }

    /** This position rendered as a two-decimal-place {@link BigDecimal}. */
    public BigDecimal asMoney() {
        return MoneyMath.fromMinorUnits(balance);
    }

    @Override
    public String toString() {
        return participant + "=" + MoneyMath.formatMinorUnits(balance);
    }
}