package com.settlex.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * A single directed obligation: {@code debtor} owes {@code amount} to {@code creditor}.
 *
 * <p>This is the atomic unit of input for the whole engine. Amounts are normalised to
 * two decimal places using {@link java.math.RoundingMode#HALF_EVEN} before any arithmetic
 * is performed, so the ledger is always exact.</p>
 *
 * @param debtor   the party that owes money
 * @param creditor the party that is owed money
 * @param amount   strictly positive monetary value, must round to at least 0.01
 */
public record Transaction(
        @NotBlank(message = "debtor must not be blank")
        @Size(max = 64, message = "debtor must not exceed 64 characters")
        String debtor,

        @NotBlank(message = "creditor must not be blank")
        @Size(max = 64, message = "creditor must not exceed 64 characters")
        String creditor,

        @DecimalMin(value = "0.01", message = "amount must be greater than zero")
        @Digits(integer = 12, fraction = 6, message = "amount must have at most 12 integer and 6 fraction digits")
        BigDecimal amount
) {
}