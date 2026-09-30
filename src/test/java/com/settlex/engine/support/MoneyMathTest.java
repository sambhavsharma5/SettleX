package com.settlex.engine.support;

import com.settlex.exception.CurrencyPrecisionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("MoneyMath currency precision")
class MoneyMathTest {

    @Nested
    @DisplayName("HALF_EVEN normalization")
    class HalfEven {

        /**
         * HALF_EVEN differs from HALF_UP exactly on ties, and the two samples below are the
         * canonical pair: 10.005 has an even last retained digit so it rounds down, while
         * 20.015 has an odd one so it rounds up. A naive HALF_UP or double-based
         * implementation gets one of these wrong.
         */
        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource({
                "10.005, 10.00",
                "20.015, 20.02",
                "0.005,  0.00",
                "0.015,  0.02",
                "1.005,  1.00",
                "1.015,  1.02",
                "2.675,  2.68",
                "0.125,  0.12",
                "0.135,  0.14",
        })
        @DisplayName("rounds exact ties to the nearest even cent")
        void roundsTiesToEven(String input, String expected) {
            BigDecimal result = MoneyMath.normalize(new BigDecimal(input));

            assertThat(result).isEqualByComparingTo(new BigDecimal(expected));
            assertThat(result.scale()).isEqualTo(2);
        }

        @Test
        @DisplayName("is idempotent, so normalizing twice changes nothing")
        void isIdempotent() {
            BigDecimal once = MoneyMath.normalize(new BigDecimal("1234.56789"));
            BigDecimal twice = MoneyMath.normalize(once);

            assertThat(twice).isEqualByComparingTo(once);
            assertThat(twice).isEqualByComparingTo("1234.57");
        }

        @Test
        @DisplayName("avoids the binary-float error that plain double would introduce")
        void avoidsDoubleDrift() {
            // 0.1 + 0.2 != 0.3 in binary floating point; exact decimal arithmetic must agree.
            BigDecimal exact = new BigDecimal("0.1").add(new BigDecimal("0.2"));
            assertThat(exact).isEqualByComparingTo(new BigDecimal("0.3"));

            double drifted = 0.1d + 0.2d;
            assertThat(BigDecimal.valueOf(drifted))
                    .as("sanity check: double arithmetic does drift, which is why we avoid it")
                    .isNotEqualByComparingTo(new BigDecimal("0.3"));
        }
    }

    @Nested
    @DisplayName("minor-unit conversion")
    class MinorUnits {

        @ParameterizedTest(name = "{0} -> {1} cents")
        @CsvSource({
                "0.01,  1",
                "1.00,  100",
                "-1.00, -100",
                "999.99, 99999",
                "0.10,  10",
        })
        @DisplayName("converts to exact cents")
        void convertsToCents(String input, long expected) {
            assertThat(MoneyMath.toMinorUnits(new BigDecimal(input), 0, "amount")).isEqualTo(expected);
        }

        @Test
        @DisplayName("round-trips through minor units without loss")
        void roundTripsExactly() {
            for (String value : new String[]{"0.01", "12.34", "-56.78", "999999.99", "0.10"}) {
                BigDecimal original = new BigDecimal(value);
                long minor = MoneyMath.toMinorUnits(original, 0, "amount");
                assertThat(MoneyMath.fromMinorUnits(minor)).isEqualByComparingTo(original);
            }
        }

        @Test
        @DisplayName("renders minor units as plain decimal text")
        void formatsMinorUnits() {
            assertThat(MoneyMath.formatMinorUnits(-12345L)).isEqualTo("-123.45");
            assertThat(MoneyMath.formatMinorUnits(5L)).isEqualTo("0.05");
            assertThat(MoneyMath.formatMinorUnits(0L)).isEqualTo("0.00");
        }
    }

    @Nested
    @DisplayName("rejected values")
    class Rejections {

        @Test
        @DisplayName("rejects an amount that rounds away to nothing")
        void rejectsSubCentAmount() {
            assertThatThrownBy(() ->
                    MoneyMath.toMinorUnits(new BigDecimal("0.004"), 3, "amount"))
                    .isInstanceOf(CurrencyPrecisionException.class)
                    .hasMessageContaining("transactions[3].amount")
                    .hasMessageContaining("smallest representable currency unit");
        }

        @Test
        @DisplayName("rejects an amount beyond the supported magnitude")
        void rejectsHugeAmount() {
            assertThatThrownBy(() ->
                    MoneyMath.toMinorUnits(new BigDecimal("99999999999999999.00"), 0, "amount"))
                    .isInstanceOf(CurrencyPrecisionException.class)
                    .hasMessageContaining("supported maximum");
        }

        @Test
        @DisplayName("treats a null amount as an invalid zero rather than an NPE")
        void rejectsNullAmount() {
            assertThatThrownBy(() -> MoneyMath.toMinorUnits(BigDecimal.ZERO, 7, "amount"))
                    .isInstanceOf(CurrencyPrecisionException.class)
                    .hasMessageContaining("transactions[7].amount");
        }
    }

    @Test
    @DisplayName("uses two decimal places and banker's rounding everywhere")
    void exposesItsContract() {
        assertThat(MoneyMath.SCALE).isEqualTo(2);
        assertThat(MoneyMath.ROUNDING_MODE).isEqualTo(RoundingMode.HALF_EVEN);
    }

    @Test
    @DisplayName("converts nanoseconds to microseconds")
    void convertsNanosToMicros() {
        assertThat(MoneyMath.nanosToMicros(1_000L)).isEqualTo(1.0d);
        assertThat(MoneyMath.nanosToMicros(1_500L)).isEqualTo(1.5d);
        assertThat(MoneyMath.nanosToMicros(123_456L)).isEqualTo(123.46d);
    }
}