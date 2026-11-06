package com.report.tool.database;

import org.springframework.jdbc.core.RowMapper;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

public final class DatabaseRowMapper implements RowMapper<Map<String, Object>> {
    private final DatabaseAdapter adapter;

    public DatabaseRowMapper(DatabaseAdapter adapter) {
        this.adapter = adapter;
    }

    @Override
    public Map<String, Object> mapRow(ResultSet result, int rowNumber) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        ResultSetMetaData metadata = result.getMetaData();
        for (int column = 1; column <= metadata.getColumnCount(); column++) {
            row.put(metadata.getColumnLabel(column), adapter.readValue(result, column));
        }
        return row;
    }
}
