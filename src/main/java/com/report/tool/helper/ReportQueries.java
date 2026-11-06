package com.report.tool.helper;

import com.report.tool.service.ReportCancellation;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

public final class ReportQueries {
    private ReportQueries() { }
    public static <T> List<T> query(NamedParameterJdbcTemplate jdbc, String sql, SqlParameterSource params, RowMapper<T> mapper) {
        return jdbc.execute(sql, params, statement -> {
            try {
                ReportCancellation.watch(statement);
                List<T> result = new ArrayList<>();
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        ReportCancellation.check();
                        result.add(mapper.mapRow(rows, result.size()));
                    }
                }
                return result;
            } finally { ReportCancellation.unwatch(); }
        });
    }
}
