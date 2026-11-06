package com.report.tool.service;

import java.sql.Statement;
import java.sql.SQLException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

public final class ReportCancellation {
    private static final ThreadLocal<ReportCancellation> CURRENT = new ThreadLocal<>();
    private final AtomicReference<Statement> statement = new AtomicReference<>();
    private volatile boolean cancelled;

    public void cancel() {
        cancelled = true;
        Statement running = statement.get();
        if (running != null) try { running.cancel(); } catch (SQLException ignored) { }
    }
    public boolean isCancelled() { return cancelled; }
    public static void check() {
        ReportCancellation control = CURRENT.get();
        if (Thread.currentThread().isInterrupted() || control != null && control.cancelled) throw new CancellationException();
    }
    public static void watch(Statement running) {
        ReportCancellation control = CURRENT.get();
        if (control != null) control.statement.set(running);
        check();
    }
    public static void unwatch() {
        ReportCancellation control = CURRENT.get();
        if (control != null) control.statement.set(null);
    }
    public <T> T run(Supplier<T> action) {
        CURRENT.set(this);
        try { check(); return action.get(); }
        finally { CURRENT.remove(); }
    }
}
