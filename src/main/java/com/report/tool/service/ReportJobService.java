package com.report.tool.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.report.tool.dto.request.ReportConfigRequest;
import com.report.tool.exception.AppException;
import com.report.tool.exception.InvalidReportConfigException;
import com.report.tool.exception.ReportBusyException;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

@Service
public class ReportJobService {
    public record Status(String id, String state, String message, int rowCount, List<String> warnings, Instant createdAt) { }
    private static final long TTL_MILLIS = 60 * 60 * 1000L;
    private final ReportEngineService engine;
    private final ReportOutputService output;
    private final ReportWorkbookService workbooks;
    private final ObjectMapper mapper;
    private final Path directory;
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(4), runnable -> { Thread thread = new Thread(runnable, "report-export"); thread.setDaemon(true); return thread; });

    public ReportJobService(ReportEngineService engine, ReportOutputService output, ReportWorkbookService workbooks,
                            ObjectMapper mapper, @Value("${report.output-directory:exports}") String directory) throws IOException {
        this.engine = engine; this.output = output; this.workbooks = workbooks; this.mapper = mapper;
        this.directory = Path.of(directory).toAbsolutePath().normalize();
        Files.createDirectories(this.directory);
        try (Stream<Path> files = Files.list(this.directory)) {
            for (Path file : files.filter(path -> path.getFileName().toString().matches("report-[a-f0-9-]{36}\\.xlsx")).toList()) {
                if (Files.getLastModifiedTime(file).toMillis() < System.currentTimeMillis() - TTL_MILLIS) Files.deleteIfExists(file);
            }
        }
    }

    public synchronized Status start(ReportConfigRequest request) {
        cleanup();
        if (jobs.values().stream().filter(job -> !job.terminal()).count() >= 6) throw new ReportBusyException();
        if (jobs.size() >= 20) {
            jobs.values().stream().filter(Job::terminal).min(Comparator.comparingLong(job -> job.accessed))
                    .ifPresent(job -> { delete(job); jobs.remove(job.id); });
        }
        ReportConfigRequest snapshot = mapper.convertValue(request, ReportConfigRequest.class);
        Job job = new Job();
        jobs.put(job.id, job);
        try {
            job.future = workers.submit(() -> execute(job, snapshot));
        } catch (RejectedExecutionException ex) {
            jobs.remove(job.id);
            throw new ReportBusyException();
        }
        return job.status();
    }

    public Status status(String id) { return find(id).status(); }

    public Status cancel(String id) {
        Job job = find(id);
        synchronized (job) {
            job.control.cancel();
            if (job.future != null) {
                job.future.cancel(true);
                workers.remove((Runnable) job.future);
            }
            if (!job.terminal()) {
                job.state = "CANCELLED";
                job.message = "Đã hủy báo cáo.";
            }
            delete(job);
        }
        return job.status();
    }

    public Path download(String id, boolean acknowledged) {
        Job job = find(id);
        synchronized (job) {
            if (!"READY".equals(job.state)) throw new InvalidReportConfigException("Báo cáo chưa sẵn sàng để tải xuống.");
            if (!acknowledged && !job.warnings.isEmpty()) {
                throw new InvalidReportConfigException("Báo cáo có cảnh báo cần xác nhận trước khi tải: " + String.join("; ", job.warnings));
            }
            if (job.file == null || !Files.exists(job.file)) throw new InvalidReportConfigException("File báo cáo đã bị xóa hoặc hết hạn.");
            job.accessed = System.currentTimeMillis();
            return job.file;
        }
    }

    private void execute(Job job, ReportConfigRequest request) {
        try {
            job.control.run(() -> {
                phase(job, "QUERYING", "Đang đọc và ghép dữ liệu…");
                List<Map<String, Object>> rows = engine.executeReport(request);
                phase(job, "PREPARING", "Đang kiểm tra, lọc và định dạng đầu ra…");
                ReportOutputService.PreparedReport prepared = output.prepare(request, rows);
                phase(job, "WRITING", "Đang ghi file Excel…");
                job.rowCount = prepared.rows().size();
                job.warnings = List.copyOf(prepared.warnings());
                try {
                    job.file = directory.resolve("report-" + job.id + ".xlsx");
                    try (OutputStream out = Files.newOutputStream(job.file, StandardOpenOption.CREATE_NEW)) { workbooks.write(request, prepared, out); }
                } catch (IOException ex) { throw new IllegalStateException("Không thể ghi file báo cáo.", ex); }
                ReportCancellation.check();
                phase(job, "READY", "File đã sẵn sàng.");
                return null;
            });
        } catch (Exception ex) {
            synchronized (job) {
                if (job.control.isCancelled() || ex instanceof CancellationException) {
                    job.state = "CANCELLED"; job.message = "Đã hủy báo cáo.";
                } else {
                    job.state = "FAILED";
                    job.message = ex instanceof AppException ? ex.getMessage() : "Không thể tạo báo cáo. Vui lòng kiểm tra dữ liệu và thử lại.";
                }
                delete(job);
            }
        }
    }

    private void phase(Job job, String state, String message) {
        synchronized (job) {
            ReportCancellation.check(); job.state = state; job.message = message; job.accessed = System.currentTimeMillis();
        }
    }

    private Job find(String id) {
        cleanup();
        Job job = jobs.get(id);
        if (job == null) throw new InvalidReportConfigException("Tác vụ không tồn tại hoặc đã hết hạn. Hãy tạo lại báo cáo.");
        job.accessed = System.currentTimeMillis();
        return job;
    }

    private synchronized void cleanup() {
        long before = System.currentTimeMillis() - TTL_MILLIS;
        jobs.values().removeIf(job -> { if (job.terminal() && job.accessed < before) { delete(job); return true; } return false; });
    }

    private void delete(Job job) {
        if (job.file != null) try { Files.deleteIfExists(job.file); } catch (IOException ignored) { }
    }

    @PreDestroy
    public void close() {
        jobs.values().forEach(job -> job.control.cancel()); workers.shutdownNow();
        try { workers.awaitTermination(5, TimeUnit.SECONDS); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
        jobs.values().forEach(this::delete);
    }

    private static final class Job {
        final String id = UUID.randomUUID().toString();
        final Instant created = Instant.now();
        final ReportCancellation control = new ReportCancellation();
        volatile String state = "QUEUED", message = "Đang chờ đến lượt…";
        volatile int rowCount;
        volatile List<String> warnings = List.of();
        volatile long accessed = System.currentTimeMillis();
        volatile Path file;
        volatile Future<?> future;
        boolean terminal() { return Set.of("READY", "FAILED", "CANCELLED").contains(state); }
        synchronized Status status() { return new Status(id, state, message, rowCount, warnings, created); }
    }
}
