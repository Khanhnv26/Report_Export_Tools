package com.report.tool;

import com.report.tool.config.AccessControlFilter;
import com.report.tool.service.ReportEngineService;
import com.report.tool.controller.DatabaseTypeController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

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
                .run(context -> {
                    assertThat(context).hasNotFailed()
                        .hasSingleBean(ReportEngineService.class).hasSingleBean(AccessControlFilter.class)
                        .hasSingleBean(DatabaseTypeController.class);
                    MockMvc mvc = MockMvcBuilders.webAppContextSetup(context.getSourceApplicationContext()).build();
                    mvc.perform(get("/api/databases//tables")).andExpect(status().isMethodNotAllowed())
                            .andExpect(jsonPath("$.status").value(405))
                            .andExpect(header().string("Allow", "DELETE"));
                    mvc.perform(get("/missing.js")).andExpect(status().isNotFound())
                            .andExpect(jsonPath("$.message").value("Không tìm thấy đường dẫn được yêu cầu."));
                    byte[] favicon = mvc.perform(get("/favicon.ico")).andExpect(status().isOk())
                            .andReturn().getResponse().getContentAsByteArray();
                    assertThat(favicon).startsWith((byte) 0, (byte) 0, (byte) 1, (byte) 0);
                });
    }
}
