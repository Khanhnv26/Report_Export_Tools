package com.report.tool.helper;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.PreparedStatementCallback;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import com.report.tool.exception.DatabaseReadConnectionException;
import com.report.tool.exception.DatabaseConnectionException;
import com.report.tool.database.DatabaseAdapters;
import com.report.tool.service.ReportCancellation;
import com.report.tool.service.impl.MetadataServiceImpl;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.lang.reflect.*;
import java.sql.*;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.sql.SQLRecoverableException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class ReportReadRetryTest {
    @TempDir Path directory;
    @Test
    void retriesLostConnectionOnceUsingAFreshQueryAttempt() {
        AtomicInteger attempts = new AtomicInteger();
        NamedParameterJdbcTemplate jdbc = new NamedParameterJdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:retry_repro", "sa", "")) {
            @Override public <T> T execute(String sql, SqlParameterSource params, PreparedStatementCallback<T> action) {
                if (attempts.incrementAndGet() == 1) {
                    throw new DataAccessResourceFailureException("Simulated closed session", new SQLRecoverableException("Session closed", "08006"));
                }
                return super.execute(sql, params, action);
            }
        };
        List<Integer> rows = ReportQueries.query(jdbc, "SELECT 7", new MapSqlParameterSource(), (result, index) -> result.getInt(1));
        assertThat(rows).containsExactly(7);
        assertThat(attempts.get()).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"28P01", "28000", "42501", "42601", "3D000", "57014", "HYT00", "08004", "08P01", "08007"})
    void doesNotRetryAuthenticationPermissionsSyntaxOrTimeouts(String state) {
        AtomicInteger attempts = new AtomicInteger();
        RuntimeException failure = new DataAccessResourceFailureException("Simulated failure", new SQLException("Hidden driver message", state));
        NamedParameterJdbcTemplate jdbc = failing(attempts, failure, false);
        assertThatThrownBy(() -> query(jdbc)).isSameAs(failure);
        assertThat(attempts.get()).isEqualTo(1);
    }

    @Test
    void prioritizesDeniedCausesAndDoesNotGuessFromMessages() {
        SQLException mixed = new SQLRecoverableException("Connection wrapper", "08006");
        mixed.setNextException(new SQLException("Authentication denied", "28P01"));
        assertThat(DatabaseReadRetry.isConnectionFailure(mixed)).isFalse();
        assertThat(DatabaseReadRetry.isConnectionFailure(new SQLException("Connection closed password wrong"))).isFalse();
        assertThat(DatabaseReadRetry.isConnectionFailure(new SQLTransientConnectionException("Pool exhausted"))).isFalse();
        assertThat(DatabaseReadRetry.isConnectionFailure(new SQLTimeoutException("Query took too long", "08006"))).isFalse();
        assertThat(DatabaseReadRetry.isConnectionFailure(new SQLInvalidAuthorizationSpecException("Denied"))).isFalse();
        assertThat(DatabaseReadRetry.isConnectionFailure(new SQLRecoverableException("Oracle closed connection", "99999"))).isTrue();
        assertThat(DatabaseReadRetry.isConnectionFailure(new SQLException("Session expired", "57P05"))).isTrue();
        assertThat(DatabaseReadRetry.isConnectionFailure(new SQLRecoverableException("Expired Oracle password", "08006", 28001))).isFalse();
        assertThat(DatabaseReadRetry.isConnectionFailure(new SQLRecoverableException("Unknown Oracle service", "08006", 12514))).isFalse();
        assertThat(DatabaseReadRetry.isConnectionFailure(new SQLRecoverableException("SQL Server denied login", "08006", 18456))).isFalse();
    }

    @Test
    void sharesExactlyOneRetryAcrossQueriesAndClearsTheBudgetAfterFailure() {
        AtomicInteger first = new AtomicInteger(); AtomicInteger next = new AtomicInteger();
        RuntimeException lost = new DataAccessResourceFailureException("Lost", new SQLRecoverableException("Disconnected", "08006"));
        assertThatThrownBy(() -> DatabaseReadRetry.scope(() -> {
            assertThat(query(failing(first, lost, true))).containsExactly(7);
            return query(failing(next, lost, false));
        })).isInstanceOf(DatabaseReadConnectionException.class);
        assertThat(first.get()).isEqualTo(2); assertThat(next.get()).isEqualTo(1);
        AtomicInteger newRequest = new AtomicInteger();
        assertThat(query(failing(newRequest, lost, true))).containsExactly(7);
        assertThat(newRequest.get()).isEqualTo(2);
    }

    @Test
    void secondFailureStopsAndCancellationDuringBackoffDoesNotStartAnotherAttempt() {
        RuntimeException lost = new DataAccessResourceFailureException("Lost", new SQLRecoverableException("Disconnected", "08006"));
        AtomicInteger attempts = new AtomicInteger();
        assertThatThrownBy(() -> query(failing(attempts, lost, false))).isInstanceOf(DatabaseReadConnectionException.class);
        assertThat(attempts.get()).isEqualTo(2);
        AtomicInteger cancelledAttempts = new AtomicInteger(); ReportCancellation control = new ReportCancellation();
        assertThatThrownBy(() -> control.run(() -> DatabaseReadRetry.withListener(notice -> control.cancel(),
                () -> query(failing(cancelledAttempts, lost, false))))).isInstanceOf(CancellationException.class);
        assertThat(cancelledAttempts.get()).isEqualTo(1);
        ReportCancellation.check();
    }

    @Test
    void discardsPartiallyReadRowsAndEvictsOnlyTheBrokenPhysicalConnection() throws Exception {
        DriverManagerDataSource actual = database();
        new JdbcTemplate(actual).execute("CREATE TABLE sample(id INTEGER); INSERT INTO sample VALUES (1), (2)");
        AtomicInteger physicalConnections = new AtomicInteger(); AtomicBoolean injected = new AtomicBoolean(); AtomicBoolean failedClosed = new AtomicBoolean();
        AbstractDataSource faulty = new AbstractDataSource() {
            @Override public Connection getConnection() throws SQLException {
                Connection delegate = actual.getConnection(); int physicalId = physicalConnections.incrementAndGet();
                return proxy(Connection.class, (connectionProxy, method, args) -> {
                    if (method.getName().equals("close") && physicalId == 1) failedClosed.set(true);
                    Object result = invoke(method, delegate, args);
                    if (!method.getName().equals("prepareStatement")) return result;
                    PreparedStatement prepared = (PreparedStatement) result;
                    return proxy(PreparedStatement.class, (statementProxy, statementMethod, statementArgs) -> {
                        Object query = invoke(statementMethod, prepared, statementArgs);
                        if (!statementMethod.getName().equals("executeQuery")) return query;
                        ResultSet rows = (ResultSet) query; AtomicInteger read = new AtomicInteger();
                        return proxy(ResultSet.class, (resultProxy, resultMethod, resultArgs) -> {
                            if (resultMethod.getName().equals("next") && read.incrementAndGet() == 2 && injected.compareAndSet(false, true)) {
                                throw new SQLRecoverableException("Disconnected mid-result", "08006");
                            }
                            return invoke(resultMethod, rows, resultArgs);
                        });
                    });
                });
            }
            @Override public Connection getConnection(String user, String password) throws SQLException { return getConnection(); }
        };
        HikariConfig config = new HikariConfig(); config.setDataSource(faulty); config.setMaximumPoolSize(1);
        config.setMinimumIdle(0); config.setInitializationFailTimeout(-1);
        try (HikariDataSource pool = new HikariDataSource(config)) {
            List<Integer> result = ReportQueries.query("db", "Orders", new NamedParameterJdbcTemplate(pool),
                    "SELECT id FROM sample ORDER BY id", new MapSqlParameterSource(), (row, index) -> row.getInt(1));
            assertThat(result).containsExactly(1, 2); assertThat(injected).isTrue(); assertThat(failedClosed).isTrue();
            assertThat(physicalConnections.get()).isGreaterThanOrEqualTo(2);
            assertThat(pool.isClosed()).isFalse();
            assertThat(new JdbcTemplate(pool).queryForObject("SELECT COUNT(*) FROM sample", Integer.class)).isEqualTo(2);
        }
    }

    @Test
    void metadataAlsoBorrowsANewConnectionAndDoesNotRetryBadCredentials() {
        DriverManagerDataSource actual = database(); new JdbcTemplate(actual).execute("CREATE TABLE users(id INTEGER)");
        AtomicInteger connections = new AtomicInteger();
        AbstractDataSource stale = new AbstractDataSource() {
            @Override public Connection getConnection() throws SQLException {
                if (connections.incrementAndGet() == 1) throw new SQLRecoverableException("Closed session", "08003");
                return actual.getConnection();
            }
            @Override public Connection getConnection(String user, String password) throws SQLException { return getConnection(); }
        };
        assertThat(metadata(stale).getTables("db")).containsExactly("users"); assertThat(connections.get()).isEqualTo(2);
        AtomicInteger deniedAttempts = new AtomicInteger();
        AbstractDataSource denied = new AbstractDataSource() {
            @Override public Connection getConnection() throws SQLException { deniedAttempts.incrementAndGet(); throw new SQLException("Wrong password", "28P01"); }
            @Override public Connection getConnection(String user, String password) throws SQLException { return getConnection(); }
        };
        assertThatThrownBy(() -> metadata(denied).getColumns("db", "users")).isInstanceOf(DatabaseConnectionException.class);
        assertThat(deniedAttempts.get()).isEqualTo(1);
    }

    private MetadataServiceImpl metadata(javax.sql.DataSource source) {
        DataSourceRegistry registry = new DataSourceRegistry(directory.resolve("connections.json").toString(), new DatabaseAdapters()) {
            @Override public javax.sql.DataSource getDataSource(String id) { return source; }
            @Override public String getDatabaseType(String id) { return "POSTGRESQL"; }
            @Override public String getDatabaseSchema(String id) { return "public"; }
        };
        return new MetadataServiceImpl(registry, new DatabaseAdapters());
    }
    private DriverManagerDataSource database() { return new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
            + ";DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE", "sa", ""); }
    private NamedParameterJdbcTemplate failing(AtomicInteger attempts, RuntimeException failure, boolean recover) {
        return new NamedParameterJdbcTemplate(database()) {
            @Override public <T> T execute(String sql, SqlParameterSource params, PreparedStatementCallback<T> action) {
                if (attempts.incrementAndGet() == 1 || !recover) throw failure;
                return super.execute(sql, params, action);
            }
        };
    }
    private List<Integer> query(NamedParameterJdbcTemplate jdbc) { return ReportQueries.query(jdbc, "SELECT 7", new MapSqlParameterSource(), (row, index) -> row.getInt(1)); }
    private static <T> T proxy(Class<T> type, InvocationHandler handler) { return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler)); }
    private static Object invoke(Method method, Object target, Object[] args) throws Throwable {
        try { return method.invoke(target, args); } catch (InvocationTargetException ex) { throw ex.getCause(); }
    }
}
