package com.acme.salary.common.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;

/**
 * The single place where monetary scale and rounding are defined (ADR-006).
 *
 * <p>Every amount in the system is a {@link BigDecimal} with scale 2, rounded HALF_UP.
 * Rounding is applied per component <em>before</em> summation, so a payslip's printed
 * lines always add up to its printed total (NFR-3.3). {@code double} and {@code float}
 * must not appear anywhere in this codebase.
 */
public final class Money {

    public static final int SCALE = 2;
    public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;
    public static final BigDecimal ZERO = BigDecimal.ZERO.setScale(SCALE);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private Money() {
    }

    /** Normalises any amount to the canonical scale and rounding. */
    public static BigDecimal normalize(BigDecimal amount) {
        if (amount == null) {
            throw new IllegalArgumentException("amount must not be null");
        }
        return amount.setScale(SCALE, ROUNDING);
    }

    /** Parses a decimal string into a canonical amount. Accepts what the API accepts. */
    public static BigDecimal of(String amount) {
        return normalize(new BigDecimal(amount));
    }

    public static BigDecimal of(long amount) {
        return normalize(BigDecimal.valueOf(amount));
    }

    /** Sums already-rounded amounts. Never rounds the total again. */
    public static BigDecimal sum(Collection<BigDecimal> amounts) {
        BigDecimal total = ZERO;
        for (BigDecimal amount : amounts) {
            total = total.add(normalize(amount));
        }
        return total;
    }

    /**
     * Prorates an amount by paid days over total days in the period (FR-5.4).
     *
     * @throws IllegalArgumentException if the day counts are not a sane pair
     */
    public static BigDecimal prorate(BigDecimal amount, int paidDays, int totalDays) {
        if (totalDays <= 0) {
            throw new IllegalArgumentException("totalDays must be positive, was " + totalDays);
        }
        if (paidDays < 0 || paidDays > totalDays) {
            throw new IllegalArgumentException(
                    "paidDays must be between 0 and " + totalDays + ", was " + paidDays);
        }
        if (paidDays == totalDays) {
            return normalize(amount);
        }
        return normalize(amount)
                .multiply(BigDecimal.valueOf(paidDays))
                .divide(BigDecimal.valueOf(totalDays), SCALE, ROUNDING);
    }

    /** Applies a percentage, e.g. {@code percentOf(basic, 12)} for 12% of basic. */
    public static BigDecimal percentOf(BigDecimal base, BigDecimal percent) {
        return normalize(base).multiply(percent).divide(HUNDRED, SCALE, ROUNDING);
    }

    /** Value equality that ignores scale, unlike {@link BigDecimal#equals}. */
    public static boolean eq(BigDecimal a, BigDecimal b) {
        return a != null && b != null && a.compareTo(b) == 0;
    }

    public static boolean isNegative(BigDecimal amount) {
        return amount.signum() < 0;
    }

    public static boolean isZero(BigDecimal amount) {
        return amount.signum() == 0;
    }

    /**
     * Renders an amount as Indian-convention words for a payslip (FR-6.3), for example
     * {@code "Rupees Sixty Four Thousand and Fifty Paise Only"}.
     *
     * @throws IllegalArgumentException for negative amounts, which a payslip never carries
     */
    public static String inWords(BigDecimal amount) {
        BigDecimal value = normalize(amount);
        if (isNegative(value)) {
            throw new IllegalArgumentException("cannot render a negative amount in words");
        }
        long rupees = value.setScale(0, RoundingMode.DOWN).longValueExact();
        int paise = value.subtract(BigDecimal.valueOf(rupees)).movePointRight(SCALE).intValueExact();

        StringBuilder words = new StringBuilder("Rupees ");
        words.append(rupees == 0 ? "Zero" : indianWords(rupees));
        if (paise > 0) {
            words.append(" and ").append(indianWords(paise)).append(" Paise");
        }
        return words.append(" Only").toString();
    }

    private static final String[] UNITS = {
            "", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine", "Ten",
            "Eleven", "Twelve", "Thirteen", "Fourteen", "Fifteen", "Sixteen", "Seventeen",
            "Eighteen", "Nineteen"
    };
    private static final String[] TENS = {
            "", "", "Twenty", "Thirty", "Forty", "Fifty", "Sixty", "Seventy", "Eighty", "Ninety"
    };

    /** Groups by crore / lakh / thousand / hundred, as Indian currency convention expects. */
    private static String indianWords(long n) {
        StringBuilder out = new StringBuilder();
        appendGroup(out, n / 10_000_000, "Crore");
        appendGroup(out, (n / 100_000) % 100, "Lakh");
        appendGroup(out, (n / 1_000) % 100, "Thousand");
        appendGroup(out, (n / 100) % 10, "Hundred");
        long rest = n % 100;
        if (rest > 0) {
            append(out, belowHundred(rest));
        }
        return out.toString();
    }

    private static void appendGroup(StringBuilder out, long count, String scaleName) {
        if (count > 0) {
            append(out, belowHundred(count));
            append(out, scaleName);
        }
    }

    private static String belowHundred(long n) {
        if (n < 20) {
            return UNITS[(int) n];
        }
        String tens = TENS[(int) (n / 10)];
        long unit = n % 10;
        return unit == 0 ? tens : tens + " " + UNITS[(int) unit];
    }

    private static void append(StringBuilder out, String word) {
        if (!out.isEmpty()) {
            out.append(' ');
        }
        out.append(word);
    }
}
