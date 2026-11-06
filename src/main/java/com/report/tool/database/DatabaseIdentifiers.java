package com.report.tool.database;

import com.report.tool.exception.InvalidIdentifierException;
import java.util.regex.Pattern;

public final class DatabaseIdentifiers {
    private static final Pattern NAME = Pattern.compile("[\\p{L}\\p{N}_$# -]{1,128}");
    private DatabaseIdentifiers() { }

    public static String validate(String value) {
        if (value == null || value.isBlank() || !value.equals(value.strip()) || !NAME.matcher(value).matches()) {
            throw new InvalidIdentifierException(value);
        }
        return value;
    }
}
