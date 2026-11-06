package com.report.tool.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.*;

class AccessControlFilterTest {
    @Test
    void refusesNetworkBindingWithoutPassword() {
        assertThatThrownBy(() -> new AccessControlFilter("0.0.0.0", "report", ""))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void localModeRejectsRemoteClientsAndRebindingHostnames() throws Exception {
        AccessControlFilter filter = new AccessControlFilter("127.0.0.1", "report", "");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/databases");
        request.setRemoteAddr("192.168.1.10");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(403);
        request = new MockHttpServletRequest("GET", "/api/databases");
        request.setServerName("attacker.example");
        response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void requiresValidCredentialsForSharedMode() throws Exception {
        AccessControlFilter filter = new AccessControlFilter("0.0.0.0", "report", "test-only-password");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/databases");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("WWW-Authenticate")).startsWith("Basic");
        request = new MockHttpServletRequest("GET", "/api/databases");
        request.addHeader("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                "report:test-only-password".getBytes(StandardCharsets.UTF_8)));
        response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        assertThat(chain.getRequest()).isSameAs(request);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    }

    @Test
    void blocksCrossOriginWritesEvenInLocalMode() throws Exception {
        AccessControlFilter filter = new AccessControlFilter("127.0.0.1", "report", "");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/templates");
        request.addHeader("Origin", "https://attacker.example");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(403);
        request = new MockHttpServletRequest("POST", "/api/templates");
        request.addHeader("Origin", "http://localhost");
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        assertThat(chain.getRequest()).isSameAs(request);
    }
}
