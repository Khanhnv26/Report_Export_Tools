package com.report.tool.service;

import com.report.tool.dto.request.ReportConfigRequest;
import com.report.tool.dto.request.ReportConfigRequest.BaseTable;
import com.report.tool.dto.request.ReportConfigRequest.JoinTable;
import com.report.tool.exception.InvalidReportConfigException;
import com.report.tool.helper.DataSourceRegistry;
import com.report.tool.helper.SqlBuilder;
import com.report.tool.exception.DatabaseReadConnectionException;
import org.springframework.dao.DataAccessResourceFailureException;
import java.sql.SQLRecoverableException;
import java.util.concurrent.atomic.AtomicInteger;
import com.report.tool.database.DatabaseAdapters;
import com.report.tool.service.impl.ReportEngineServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCallback;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.stream.IntStream;
import org.springframework.jdbc.core.RowMapper;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.*;

class ReportEngineServiceTest {
    @TempDir Path directory;
    private JdbcTemplate orders;
    private JdbcTemplate customers;
    private ReportEngineServiceImpl engine;
    private final Map<String, NamedParameterJdbcTemplate> databases = new HashMap<>();
    private final Map<String, String> databaseTypes = new HashMap<>();

    @BeforeEach
    void setup() {
        DataSourceRegistry registry = new DataSourceRegistry(directory.resolve("connections.json").toString(), new DatabaseAdapters()) {
            @Override public NamedParameterJdbcTemplate getJdbc(String id) { return databases.get(id); }
            @Override public String getDatabaseType(String id) { return databaseTypes.get(id); }
            @Override public String getDatabaseSchema(String id) { return null; }
        };
        orders = database("PostgreSQL");
        customers = database("MySQL");
        databases.put("orders_db", new NamedParameterJdbcTemplate(orders));
        databases.put("crm_db", new NamedParameterJdbcTemplate(customers));
        databaseTypes.put("orders_db", "POSTGRESQL");
        databaseTypes.put("crm_db", "MYSQL");
        engine = new ReportEngineServiceImpl(registry, new SqlBuilder(new DatabaseAdapters()));
        orders.execute("CREATE TABLE orders (id BIGINT, customer_id BIGINT, created_at TIMESTAMP)");
        customers.execute("CREATE TABLE users (id BIGINT, manager_id BIGINT, name VARCHAR(100))");
    }

    private JdbcTemplate database(String mode) {
        return new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
                + ";DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;MODE=" + mode, "sa", ""));
    }

    private ReportConfigRequest request() {
        return ReportConfigRequest.builder().baseTable(BaseTable.builder().dbId("orders_db")
                .tableName("orders").columns(List.of("id")).build()).build();
    }

    private JoinTable users(String alias, String sourceKey) {
        return JoinTable.builder().dbId("crm_db").tableName("users").alias(alias)
                .sourceKey(sourceKey).targetKey("id").columns(List.of("name")).build();
    }

    @Test
    void expandsAllMatchesAndKeepsUnmatchedRows() {
        orders.update("INSERT INTO orders VALUES (1, 7, NULL), (2, 8, NULL), (3, NULL, NULL)");
        customers.update("INSERT INTO users VALUES (7, NULL, 'A'), (7, NULL, 'B')");
        ReportConfigRequest request = request();
        request.setJoins(List.of(users("crm", "customer_id")));
        List<Map<String, Object>> rows = engine.executeReport(request);
        assertThat(rows).hasSize(4);
        assertThat(rows).filteredOn(row -> Long.valueOf(1).equals(row.get("id")))
                .extracting(row -> row.get("crm.name")).containsExactlyInAnyOrder("A", "B");
        assertThat(rows).filteredOn(row -> !Long.valueOf(1).equals(row.get("id")))
                .allSatisfy(row -> assertThat(row).containsEntry("crm.name", null));
    }

    @Test
    void supportsChainedJoinsAndDuplicateTableNamesWithAliases() {
        orders.update("INSERT INTO orders VALUES (1, 7, NULL)");
        customers.update("INSERT INTO users VALUES (7, 9, 'Employee'), (9, NULL, 'Manager')");
        ReportConfigRequest request = request();
        request.setJoins(List.of(users("employee", "orders.customer_id"), users("manager", "employee.manager_id")));
        assertThat(engine.executeReport(request)).singleElement().satisfies(row ->
                assertThat(row).containsEntry("employee.name", "Employee").containsEntry("manager.name", "Manager"));
    }

    @Test
    void rejectsDuplicateAliasesInsteadOfOverwritingData() {
        ReportConfigRequest request = request();
        request.setJoins(List.of(users("crm", "customer_id"), users("crm", "customer_id")));
        assertThatThrownBy(() -> engine.executeReport(request)).isInstanceOf(InvalidReportConfigException.class)
                .hasMessageContaining("trùng");
    }

    @Test
    void rejectsForwardJoinReference() {
        ReportConfigRequest request = request();
        request.setJoins(List.of(users("employee", "manager.id"), users("manager", "customer_id")));
        assertThatThrownBy(() -> engine.executeReport(request)).isInstanceOf(InvalidReportConfigException.class);
    }

    @Test
    void includesLastDayAndExcludesFollowingMidnight() {
        orders.update("INSERT INTO orders VALUES (1, NULL, '2026-11-05 23:59:59'),"
                + " (2, NULL, '2026-11-06 00:00:00'), (3, NULL, '2026-11-06 23:59:59.999'),"
                + " (4, NULL, '2026-11-07 00:00:00')");
        ReportConfigRequest request = request();
        request.getBaseTable().setFilterColumn("created_at");
        request.getBaseTable().setFromDate("2026-11-06");
        request.getBaseTable().setToDate("2026-11-06");
        assertThat(engine.executeReport(request)).extracting(row -> row.get("id")).containsExactlyInAnyOrder(2L, 3L);
    }

    @Test
    void rejectsPartialAndReversedDates() {
        ReportConfigRequest request = request();
        request.getBaseTable().setFromDate("2026-11-06");
        assertThatThrownBy(() -> engine.executeReport(request)).isInstanceOf(InvalidReportConfigException.class);
        request.getBaseTable().setFilterColumn("created_at");
        request.getBaseTable().setToDate("2026-11-05");
        assertThatThrownBy(() -> engine.executeReport(request)).isInstanceOf(InvalidReportConfigException.class);
    }

    @Test
    void previewLimitsTheDatabaseQueryBeforeLoadingRows() {
        for (int i = 0; i < 21; i++) orders.update("INSERT INTO orders VALUES (?, NULL, NULL)", i);
        ReflectionTestUtils.setField(engine, "maxRows", 20);
        assertThat(engine.previewReport(request())).hasSize(20);
        assertThatThrownBy(() -> engine.executeReport(request())).isInstanceOf(InvalidReportConfigException.class)
                .hasMessageContaining("giới hạn");
    }

    @Test
    void previewLimitsFanoutButExportDoesNotSilentlyTruncate() {
        orders.update("INSERT INTO orders VALUES (1, 7, NULL), (2, 7, NULL)");
        for (int i = 0; i < 11; i++) customers.update("INSERT INTO users VALUES (7, NULL, ?)", "User" + i);
        ReflectionTestUtils.setField(engine, "maxRows", 20);
        ReportConfigRequest request = request();
        request.setJoins(List.of(users("crm", "customer_id")));
        assertThat(engine.previewReport(request)).hasSize(20);
        assertThatThrownBy(() -> engine.executeReport(request)).isInstanceOf(InvalidReportConfigException.class);
    }

    @Test
    void unmatchedNullSourceStillHasMappedColumns() {
        orders.update("INSERT INTO orders VALUES (1, NULL, NULL)");
        ReportConfigRequest request = request();
        request.setJoins(List.of(users("crm", "customer_id")));
        assertThat(engine.executeReport(request)).singleElement().satisfies(row ->
                assertThat(row).containsEntry("crm.name", null));
    }

    @Test
    void supportsSqlServerTopForBaseAndJoinedQueries() {
        JdbcTemplate mssql = database("MSSQLServer");
        mssql.execute("CREATE TABLE orders (id BIGINT, customer_id BIGINT)");
        mssql.execute("CREATE TABLE users (id BIGINT, name VARCHAR(100))");
        mssql.update("INSERT INTO orders VALUES (1, 7)");
        mssql.update("INSERT INTO users VALUES (7, 'SQL Server User')");
        databases.put("orders_db", new NamedParameterJdbcTemplate(mssql));
        databases.put("crm_db", new NamedParameterJdbcTemplate(mssql));
        databaseTypes.put("orders_db", "SQLSERVER");
        databaseTypes.put("crm_db", "MSSQL");
        ReportConfigRequest request = request();
        request.setJoins(List.of(users("crm", "customer_id")));
        assertThat(engine.previewReport(request)).singleElement().satisfies(row ->
                assertThat(row).containsEntry("crm.name", "SQL Server User"));
    }

    @Test
    void joinsPostgresToOracleWithNumericKeysInSafeBatches() {
        JdbcTemplate oracle = database("Oracle");
        oracle.execute("CREATE TABLE users (id NUMBER(19, 0), name VARCHAR2(100))");
        orders.batchUpdate("INSERT INTO orders VALUES (?, ?, NULL)", IntStream.range(0, 1001)
                .mapToObj(id -> new Object[]{id, id}).toList());
        oracle.batchUpdate("INSERT INTO users VALUES (?, ?)", IntStream.range(0, 1001)
                .mapToObj(id -> new Object[]{id, "User" + id}).toList());
        List<Integer> batchSizes = new ArrayList<>();
        databases.put("crm_db", new NamedParameterJdbcTemplate(oracle) {
            @Override
            public <T> T execute(String sql, SqlParameterSource params,
                                 PreparedStatementCallback<T> action) {
                batchSizes.add(((List<?>) params.getValue("keys")).size());
                return super.execute(sql, params, action);
            }
        });
        databaseTypes.put("crm_db", "ORACLE");
        ReportConfigRequest request = request();
        request.setJoins(List.of(users("crm", "customer_id")));
        assertThat(engine.executeReport(request)).hasSize(1001)
                .allSatisfy(row -> assertThat(row.get("crm.name")).isNotNull());
        assertThat(batchSizes).containsExactly(1000, 1);
    }

    @Test
    void retriesOnlyTheFailedJoinBatchWithoutReplayingEarlierBatches() {
        orders.batchUpdate("INSERT INTO orders VALUES (?, ?, NULL)", IntStream.range(0, 1001).mapToObj(id -> new Object[]{id, id}).toList());
        customers.batchUpdate("INSERT INTO users VALUES (?, NULL, ?)", IntStream.range(0, 1001).mapToObj(id -> new Object[]{id, "User" + id}).toList());
        AtomicInteger baseQueries = new AtomicInteger(); List<Integer> batches = new ArrayList<>();
        databases.put("orders_db", new NamedParameterJdbcTemplate(orders) {
            @Override public <T> T execute(String sql, SqlParameterSource params, PreparedStatementCallback<T> callback) {
                baseQueries.incrementAndGet(); return super.execute(sql, params, callback);
            }
        });
        databases.put("crm_db", new NamedParameterJdbcTemplate(customers) {
            @Override public <T> T execute(String sql, SqlParameterSource params, PreparedStatementCallback<T> callback) {
                batches.add(((List<?>) params.getValue("keys")).size());
                if (batches.size() == 2) throw new DataAccessResourceFailureException("Lost", new SQLRecoverableException("Closed", "08006"));
                return super.execute(sql, params, callback);
            }
        });
        ReportConfigRequest config = request(); config.setJoins(List.of(users("crm", "customer_id")));
        assertThat(engine.executeReport(config)).hasSize(1001).allSatisfy(row -> assertThat(row.get("crm.name")).isNotNull());
        assertThat(baseQueries.get()).isEqualTo(1); assertThat(batches).containsExactly(1000, 1, 1);
    }

    @Test
    void reportCannotRetryAJoinAfterBaseQueryAlreadyConsumedItsRetry() {
        orders.update("INSERT INTO orders VALUES (1, 7, NULL)");
        AtomicInteger baseQueries = new AtomicInteger(); AtomicInteger joins = new AtomicInteger();
        databases.put("orders_db", new NamedParameterJdbcTemplate(orders) {
            @Override public <T> T execute(String sql, SqlParameterSource params, PreparedStatementCallback<T> callback) {
                if (baseQueries.incrementAndGet() == 1) throw new DataAccessResourceFailureException("Lost", new SQLRecoverableException("Closed", "08006"));
                return super.execute(sql, params, callback);
            }
        });
        databases.put("crm_db", new NamedParameterJdbcTemplate(customers) {
            @Override public <T> T execute(String sql, SqlParameterSource params, PreparedStatementCallback<T> callback) {
                joins.incrementAndGet(); throw new DataAccessResourceFailureException("Lost", new SQLRecoverableException("Closed", "08006"));
            }
        });
        ReportConfigRequest config = request(); config.setJoins(List.of(users("crm", "customer_id")));
        assertThatThrownBy(() -> engine.executeReport(config)).isInstanceOf(DatabaseReadConnectionException.class);
        assertThat(baseQueries.get()).isEqualTo(2); assertThat(joins.get()).isEqualTo(1);
    }
}
