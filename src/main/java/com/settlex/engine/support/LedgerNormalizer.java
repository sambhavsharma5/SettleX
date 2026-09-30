package com.settlex.engine.support;

import com.settlex.dto.Transaction;
import com.settlex.exception.CurrencyPrecisionException;
import com.settlex.exception.UnbalancedLedgerException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a raw {@code List<Transaction>} into a validated {@link SettlementProblem}.
 *
 * <p>This is the single place where {@link Transaction} values become trusted numbers.
 * Both engines delegate here, which guarantees they cannot drift apart in how they
 * interpret input.</p>
 *
 * <p>The normalisation pipeline is:</p>
 * <ol>
 *   <li>reject blank participant names;</li>
 *   <li>round every amount to two decimals with {@code HALF_EVEN} and convert to exact
 *       minor units;</li>
 *   <li>assert the ledger balances to exactly zero;</li>
 *   <li>net every participant, dropping those whose balance lands on zero.</li>
 * </ol>
 *
 * <p>Self-referential obligations ({@code A owes A}) are deliberately <em>accepted</em>
 * rather than rejected: they debit and credit the same participant, so they net to exactly
 * zero and vanish during step four. Rejecting them would be over-restrictive, because such
 * edges are a common artefact of imperfect bookkeeping and are economically inert.</p>
 */
public final class LedgerNormalizer {

    private static final BigDecimal ZERO_AMOUNT = BigDecimal.ZERO;

    private LedgerNormalizer() {
        throw new AssertionError("Utility class");
    }

    /**
     * Validates and nets out a raw transaction list.
     *
     * @throws IllegalArgumentException    if the list is null or empty, or a participant name is blank
     * @throws CurrencyPrecisionException  if an amount is sub-cent, out of range, or the aggregate overflows
     * @throws UnbalancedLedgerException    if debits and credits do not agree exactly
     */
    public static SettlementProblem normalize(List<Transaction> transactions) {
        if (transactions == null || transactions.isEmpty()) {
            throw new IllegalArgumentException("At least one transaction is required");
        }

        // LinkedHashMap: first-seen participant order, so every downstream artifact is stable.
        Map<String, Long> balances = new LinkedHashMap<>();
        long totalDebits = 0L;
        long totalCredits = 0L;

        for (int i = 0; i < transactions.size(); i++) {
            Transaction tx = transactions.get(i);
            String debtor = requireParticipant(tx.debtor(), i, "debtor");
            String creditor = requireParticipant(tx.creditor(), i, "creditor");

            long minor = MoneyMath.toMinorUnits(
                    tx.amount() == null ? BigDecimal.ZERO : tx.amount(), i, "amount");

            final int index = i;
            final BigDecimal amount = tx.amount();

            if (totalDebits > MoneyMath.MAX_LEDGER_TOTAL_MINOR) {
                throw new CurrencyPrecisionException(index, "amount",
                        amount.toPlainString(),
                        "aggregate ledger value exceeds the supported maximum of "
                                + MoneyMath.MAX_LEDGER_TOTAL.toPlainString());
            }

            totalDebits = addExact(totalDebits, minor, index, amount);
            totalCredits = addExact(totalCredits, minor, index, amount);

            // Net = Σ(credits) - Σ(debits)
            balances.merge(debtor, -minor, (a, b) -> addExact(a, b, index, amount));
            balances.merge(creditor, minor, (a, b) -> addExact(a, b, index, amount));
        }

        /*
         * Invariant guard. The Transaction model is bilateral, so gross debits always equal
         * gross credits and the net positions must cancel exactly. A non-zero residual here
         * would mean the netting logic is broken, which would silently corrupt every plan,
         * so it is asserted rather than assumed.
         */
        long residual = 0L;
        for (Long balance : balances.values()) {
            residual = addExact(residual, balance, 0, ZERO_AMOUNT);
        }
        if (residual != 0L) {
            throw new UnbalancedLedgerException(residual);
        }

        List<Position> positions = new ArrayList<>(balances.size());
        List<Position> creditors = new ArrayList<>();
        List<Position> debtors = new ArrayList<>();
        Map<String, BigDecimal> netBalances = new LinkedHashMap<>();

        for (Map.Entry<String, Long> entry : balances.entrySet()) {
            long balance = entry.getValue();
            netBalances.put(entry.getKey(), MoneyMath.fromMinorUnits(balance));
            if (balance != 0L) {
                Position position = new Position(entry.getKey(), balance);
                positions.add(position);
                if (position.isCreditor()) {
                    creditors.add(position);
                } else {
                    debtors.add(position);
                }
            }
        }

        return new SettlementProblem(positions, creditors, debtors, netBalances,
                transactions.size(), totalDebits);
    }

    private static long addExact(long accumulator, long delta, int index, BigDecimal amount) {
        try {
            return Math.addExact(accumulator, delta);
        } catch (ArithmeticException overflow) {
            throw new CurrencyPrecisionException(index, "amount",
                    String.valueOf(amount),
                    "aggregate ledger value overflows the supported magnitude");
        }
    }

    private static String requireParticipant(String value, int index, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "transactions[" + index + "]." + field + " must not be blank");
        }
        return value.trim();
    }
}