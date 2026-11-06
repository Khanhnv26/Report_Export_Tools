package com.report.tool.database;

import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.SQLException;
import java.sql.SQLXML;
import java.sql.Time;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;

final class JdbcValues {
    private static final int MAX_VALUE_SIZE = 1024 * 1024;

    private JdbcValues() { }

    static Object normalize(Object value) throws SQLException {
        if (value instanceof Clob clob) {
            try {
                checkSize(clob.length());
                return clob.length() == 0 ? "" : clob.getSubString(1, (int) clob.length());
            } finally {
                clob.free();
            }
        }
        if (value instanceof Blob blob) {
            try {
                checkSize(blob.length());
                return blob.length() == 0 ? "" : Base64.getEncoder().encodeToString(blob.getBytes(1, (int) blob.length()));
            } finally {
                blob.free();
            }
        }
        if (value instanceof byte[] bytes) {
            checkSize(bytes.length);
            return Base64.getEncoder().encodeToString(bytes);
        }
        if (value instanceof SQLXML xml) {
            try {
                return xml.getString();
            } finally {
                xml.free();
            }
        }
        if (value instanceof Array array) {
            try {
                Object contents = array.getArray();
                String text = Arrays.deepToString(new Object[]{contents});
                return text.substring(1, text.length() - 1);
            } finally {
                array.free();
            }
        }
        if (value instanceof Time time) {
            return time.toLocalTime().toString();
        }
        if (value == null || value instanceof Number || value instanceof Boolean || value instanceof String
                || value instanceof Date || value instanceof LocalDate
                || value instanceof LocalDateTime) {
            return value;
        }
        return value.toString();
    }

    private static void checkSize(long length) throws SQLException {
        if (length > MAX_VALUE_SIZE) {
            throw new SQLException("Trường binary/LOB vượt giới hạn 1 MiB; hãy bỏ cột này khỏi báo cáo.");
        }
    }
}
