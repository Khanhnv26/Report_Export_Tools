package com.report.tool.helper;

import com.report.tool.service.ReportCancellation;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import java.sql.ResultSet;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public final class ReportQueries {
    private ReportQueries() { }
    public static <T> List<T> query(NamedParameterJdbcTemplate jdbc, String sql, SqlParameterSource params, RowMapper<T> mapper) {
        return query("database", "database", jdbc, sql, params, mapper);
    }
    public static <T> List<T> query(String sourceId, String source, NamedParameterJdbcTemplate jdbc, String sql,
                                   SqlParameterSource params, RowMapper<T> mapper) {
        return DatabaseReadRetry.read(sourceId, source, () -> executeOnce(jdbc, sql, params, mapper));
    }
    private static <T> List<T> executeOnce(NamedParameterJdbcTemplate jdbc, String sql, SqlParameterSource params, RowMapper<T> mapper) {
        return jdbc.execute(sql, params, statement -> {
            Connection connection = null;
            try {
                connection = statement.getConnection();
                ReportCancellation.watch(statement);
                List<T> result = new ArrayList<>();
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        ReportCancellation.check();
                        result.add(mapper.mapRow(rows, result.size()));
                    }
                }
                return result;
            } catch (SQLException | RuntimeException failure) {
                DatabaseReadRetry.discardBrokenConnection(jdbc.getJdbcTemplate().getDataSource(), connection, failure);
                throw failure;
            } finally { ReportCancellation.unwatch(); }
        });
    }
}
