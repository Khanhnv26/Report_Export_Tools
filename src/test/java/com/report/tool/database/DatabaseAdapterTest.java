package com.report.tool.database;

import com.report.tool.dto.request.DBConnectionRequest;
import com.report.tool.exception.DatabaseConnectionException;
import com.report.tool.exception.InvalidIdentifierException;
import com.report.tool.helper.SqlBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Date;
import java.sql.Driver;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class DatabaseAdapterTest {
    private final DatabaseAdapters adapters = new DatabaseAdapters();
    private final SqlBuilder builder = new SqlBuilder(adapters);

    @ParameterizedTest
    @ValueSource(strings = {"MYSQL", "POSTGRESQL", "SQLSERVER", "ORACLE"})
    void bundledDriverAcceptsGeneratedUrl(String type) throws Exception {
        DatabaseAdapter adapter = adapters.get(type);
        DBConnectionRequest request = DBConnectionRequest.builder().host("localhost").port(0)
                .database(type.equals("ORACLE") ? "orclpdb.company.local" : "reports").build();
        Driver driver = (Driver) Class.forName(adapter.type().driverClassName()).getDeclaredConstructor().newInstance();
        assertThat(driver.acceptsURL(adapter.jdbcUrl(request))).isTrue();
        assertThat(adapter.effectivePort(request)).isEqualTo(adapter.type().defaultPort());
        request.setPort(19000);
        assertThat(adapter.jdbcUrl(request)).contains(":19000");
        request.setHost("::1");
        assertThat(adapter.jdbcUrl(request)).contains("[::1]");
    }

    @ParameterizedTest
    @CsvSource({"POSTGRESQL,PostgreSQL", "MYSQL,MySQL", "SQLSERVER,MSSQLServer", "ORACLE,Oracle"})
    void executesQuotedSchemaNamesDateFiltersAndLimitedJoins(String type, String mode) {
        DriverManagerDataSource source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
                + ";DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=FALSE;MODE=" + mode, "sa", "");
        NamedParameterJdbcTemplate jdbc = new NamedParameterJdbcTemplate(source);
        DatabaseAdapter adapter = adapters.get(type);
        String schema = "Report_Schema";
        String table = "Order Details";
        String key = "select";
        String name = "Customer Name";
        String date = "created_at";
        String reference = adapter.tableReference(schema, table);
        jdbc.getJdbcTemplate().execute("CREATE SCHEMA " + adapter.quoteIdentifier(schema));
        jdbc.getJdbcTemplate().execute("CREATE TABLE " + reference + " (" + adapter.quoteIdentifier(key)
                + " INTEGER, " + adapter.quoteIdentifier(name) + " VARCHAR(100), "
                + adapter.quoteIdentifier(date) + " TIMESTAMP)");
        jdbc.getJdbcTemplate().update("INSERT INTO " + reference
                + " VALUES (1, 'Alice', '2026-11-06 23:59:59'), (2, 'Bob', '2026-11-07 00:00:00')");
        String base = builder.buildBaseQuery(type, schema, table, List.of(key, name), date, true);
        List<Map<String, Object>> rows = jdbc.query(base, Map.of("rowLimit", 1, "fromDate", Date.valueOf("2026-11-06"),
                "toDateExclusive", Date.valueOf("2026-11-07")), builder.rowMapper(type));
        assertThat(rows).singleElement().satisfies(row -> assertThat(row).containsEntry(name, "Alice"));
        String join = builder.buildJoinQuery(type, schema, table, List.of(name), key);
        assertThat(jdbc.query(join, Map.of("rowLimit", 1, "keys", List.of(1, 2)), builder.rowMapper(type)))
                .hasSize(1).allSatisfy(row -> assertThat(row).containsKeys(key, name));
    }

    @Test
    void resolvesLegacyTypeAndRejectsUnknownTypesAndInjectedIdentifiers() {
        assertThat(adapters.get("mssql").type().id()).isEqualTo("SQLSERVER");
        assertThat(adapters.types()).extracting(DatabaseType::id)
                .containsExactlyInAnyOrder("MYSQL", "POSTGRESQL", "SQLSERVER", "ORACLE");
        assertThatThrownBy(() -> adapters.get("unknown")).isInstanceOf(DatabaseConnectionException.class);
        assertThatThrownBy(() -> builder.buildBaseQuery("ORACLE", "APP", "users; DROP TABLE users",
                List.of("id"), null, false)).isInstanceOf(InvalidIdentifierException.class);
        assertThatThrownBy(() -> builder.buildJoinQuery("MYSQL", null, "users", List.of("id"), "id) OR 1=1"))
                .isInstanceOf(InvalidIdentifierException.class);
    }
}
