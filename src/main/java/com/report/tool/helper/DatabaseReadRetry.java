package com.report.tool.helper;

import com.report.tool.exception.DatabaseReadConnectionException;
import com.report.tool.service.ReportCancellation;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.QueryTimeoutException;
import javax.sql.DataSource;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTimeoutException;
import java.sql.SQLInvalidAuthorizationSpecException;
import java.sql.SQLSyntaxErrorException;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;
import java.util.function.Supplier;

@Slf4j
public final class DatabaseReadRetry {
    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();
    private static final Set<String> CONNECTION_STATES = Set.of("08000", "08001", "08003", "08006", "08S01", "57P01", "57P02", "57P03", "57P05");
    private static final Set<Integer> NON_RETRYABLE_CODES = Set.of(1017, 1031, 28000, 28001, 28009, 28032, 12505, 12514,
            1044, 1045, 1049, 1064, 1142, 1143, 18452, 18456, 4060, 911, 229, 230, 262);
    public record Notice(String sourceId, String source, boolean recovered) { }
    private DatabaseReadRetry() { }

    public static <T> T scope(Supplier<T> action) { return withListener(ignored -> { }, action); }

    public static <T> T withListener(Consumer<Notice> listener, Supplier<T> action) {
        if (CURRENT.get() != null) return action.get();
        CURRENT.set(new Scope(listener));
        try { ReportCancellation.check(); return action.get(); }
        finally { CURRENT.remove(); }
    }

    public static List<String> recoveryMessages() {
        Scope scope = CURRENT.get();
        return scope == null ? List.of() : List.copyOf(scope.recovered);
    }

    public static <T> T read(String sourceId, String source, Supplier<T> operation) {
        return scope(() -> {
            ReportCancellation.check();
            try { return operation.get(); }
            catch (RuntimeException first) {
                ReportCancellation.check();
                if (!isConnectionFailure(first)) throw first;
                Scope budget = CURRENT.get();
                if (budget.used) throw new DatabaseReadConnectionException(source, first);
                budget.used = true;
                log.warn("Database read retry: sourceId={}, attempt=1/1", sourceId);
                budget.listener.accept(new Notice(sourceId, source, false));
                waitBeforeRetry();
                T result;
                try { result = operation.get(); }
                catch (RuntimeException second) {
                    ReportCancellation.check();
                    if (isConnectionFailure(second)) throw new DatabaseReadConnectionException(source, second);
                    throw second;
                }
                ReportCancellation.check();
                budget.recovered.add("Đã tự kết nối lại nguồn “" + source + "” và đọc lại truy vấn bị gián đoạn (1/1).");
                budget.listener.accept(new Notice(sourceId, source, true));
                return result;
            }
        });
    }

    public static boolean isConnectionFailure(Throwable failure) {
        boolean connectionFailure = false;
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<Throwable> pending = new ArrayDeque<>(); pending.add(failure);
        while (!pending.isEmpty()) {
            Throwable current = pending.removeFirst();
            if (!seen.add(current)) continue;
            if (current instanceof CancellationException || current instanceof InterruptedException
                    || current instanceof SQLTimeoutException || current instanceof QueryTimeoutException
                    || current instanceof SQLInvalidAuthorizationSpecException || current instanceof SQLSyntaxErrorException) return false;
            if (current instanceof SQLException sql) {
                if (NON_RETRYABLE_CODES.contains(sql.getErrorCode())) return false;
                String state = sql.getSQLState();
                if (state != null) {
                    if (state.startsWith("28") || state.startsWith("42") || state.startsWith("22") || state.startsWith("S000")
                            || state.startsWith("3D") || state.startsWith("3F") || state.startsWith("0A")
                            || Set.of("57014", "HYT00", "HYT01", "08004", "08P01", "08007").contains(state)) return false;
                    if (CONNECTION_STATES.contains(state)) connectionFailure = true;
                }
                if (current instanceof SQLRecoverableException) connectionFailure = true;
                if (sql.getNextException() != null) pending.add(sql.getNextException());
            }
            if (current instanceof ConnectException || current instanceof NoRouteToHostException || current instanceof SocketException) connectionFailure = true;
            if (current.getCause() != null) pending.add(current.getCause());
        }
        return connectionFailure;
    }

    public static void discardBrokenConnection(DataSource source, Connection connection, Throwable failure) {
        if (connection == null || !isConnectionFailure(failure)) return;
        try {
            if (source instanceof HikariDataSource hikari) hikari.evictConnection(connection);
            else connection.close();
        } catch (SQLException | RuntimeException ignored) {
            log.debug("Failed connection was already closed or evicted.");
        }
    }

    private static void waitBeforeRetry() {
        long end = System.nanoTime() + 500_000_000L;
        do {
            ReportCancellation.check();
            try { Thread.sleep(50); }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new CancellationException(); }
        } while (System.nanoTime() < end);
        ReportCancellation.check();
    }

    private static final class Scope {
        boolean used;
        final Consumer<Notice> listener;
        final List<String> recovered = new ArrayList<>();
        Scope(Consumer<Notice> listener) { this.listener = listener; }
    }
}
