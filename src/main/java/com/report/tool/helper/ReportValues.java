package com.report.tool.helper;

import java.math.BigDecimal;

public final class ReportValues {
    private ReportValues() {}

    public static Object preservePrecision(Object value) {
        if (value instanceof Number number) {
            try {
                BigDecimal decimal = new BigDecimal(number.toString()).stripTrailingZeros();
                int digits = Math.max(decimal.precision(), decimal.precision() - decimal.scale());
                double approximate = number.doubleValue();
                if (digits > 15 || !Double.isFinite(approximate)
                        || BigDecimal.valueOf(approximate).compareTo(decimal) != 0) return number.toString();
            } catch (NumberFormatException e) {
                return number.toString();
            }
        }
        return value;
    }
}
