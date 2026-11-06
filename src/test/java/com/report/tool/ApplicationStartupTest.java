package com.report.tool;

import com.report.tool.config.AccessControlFilter;
import com.report.tool.service.ReportEngineService;
import com.report.tool.controller.DatabaseTypeController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

class ApplicationStartupTest {
    @TempDir Path directory;

    @Test
    void startsWithIsolatedStorageAndLocalAccess() {
        new WebApplicationContextRunner().withUserConfiguration(ReportToolApplication.class)
                .withPropertyValues("report.connections-file=" + directory.resolve("connections.json"),
                        "report.template-directory=" + directory.resolve("templates"),
                        "report.output-directory=" + directory.resolve("exports"),
                        "report.open-browser=false", "server.address=127.0.0.1", "report.security.password=")
                .run(context -> assertThat(context).hasNotFailed()
                        .hasSingleBean(ReportEngineService.class).hasSingleBean(AccessControlFilter.class)
                        .hasSingleBean(DatabaseTypeController.class));
    }
}
