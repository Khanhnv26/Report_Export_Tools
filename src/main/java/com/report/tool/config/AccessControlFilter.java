package com.report.tool.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Set;


@Component
public class AccessControlFilter extends OncePerRequestFilter {
    private static final Set<String> LOCAL_HOSTS = Set.of("127.0.0.1", "::1", "[::1]", "0:0:0:0:0:0:0:1", "localhost");
    private final byte[] credentials;

    public AccessControlFilter(@Value("${server.address:127.0.0.1}") String address,
                               @Value("${report.security.username:report}") String username,
                               @Value("${report.security.password:}") String password) {
        if (password.isBlank() && !LOCAL_HOSTS.contains(address)) {
            throw new IllegalStateException("Đặt REPORT_TOOL_PASSWORD trước khi mở tool qua mạng.");
        }
        if (!password.isBlank() && (username.isBlank() || username.contains(":"))) {
            throw new IllegalStateException("Tên đăng nhập không hợp lệ.");
        }
        credentials = password.isBlank() ? null : (username + ":" + password).getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Cache-Control", "no-store");
        if (credentials == null && (!LOCAL_HOSTS.contains(request.getRemoteAddr())
                || !LOCAL_HOSTS.contains(request.getServerName()))) {
            response.sendError(403, "Chế độ nội bộ chỉ cho phép truy cập từ máy hiện tại.");
            return;
        }
        if ("cross-site".equals(request.getHeader("Sec-Fetch-Site")) || !sameOrigin(request)) {
            response.sendError(403, "Yêu cầu phải đến từ giao diện của tool.");
            return;
        }
        if (credentials != null && !authenticated(request)) {
            response.setHeader("WWW-Authenticate", "Basic realm=\"Report Tool\", charset=\"UTF-8\"");
            response.sendError(401, "Cần đăng nhập.");
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean authenticated(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || header.length() > 8192 || !header.regionMatches(true, 0, "Basic ", 0, 6)) return false;
        try {
            return MessageDigest.isEqual(credentials, Base64.getDecoder().decode(header.substring(6)));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private boolean sameOrigin(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        if (origin == null) return true; // Non-browser clients may omit Origin.
        try {
            URI uri = URI.create(origin);
            if (uri.getHost() == null || uri.getScheme() == null) return false;
            int port = uri.getPort() == -1 ? ("https".equals(uri.getScheme()) ? 443 : 80) : uri.getPort();
            return uri.getScheme().equals(request.getScheme()) && uri.getHost().equalsIgnoreCase(request.getServerName())
                    && port == request.getServerPort();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
