package com.xushi.api;

import com.xushi.common.Json;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class RequestGuard extends OncePerRequestFilter {

  private final Set<String> origins;
  private final Json json;

  public RequestGuard(
    @Value("${schedule.allowed-origins}") String origins,
    Json json
  ) {
    this.origins = new HashSet<>(Arrays.asList(origins.split(",")));
    this.json = json;
  }

  protected void doFilterInternal(
    HttpServletRequest r,
    HttpServletResponse response,
    FilterChain chain
  ) throws ServletException, IOException {
    String rid = UUID.randomUUID().toString();
    r.setAttribute("request_id", rid);
    response.setHeader("X-Request-ID", rid);
    response.setHeader("X-Content-Type-Options", "nosniff");
    response.setHeader("Cache-Control", "no-store");
    if (r.getRequestURI().startsWith("/api/") && !r.getMethod().equals("GET")) {
      String origin = r.getHeader("Origin");
      if (origin != null && !origins.contains(origin)) {
        reject(response, 403, "请求来源不匹配");
        return;
      }
      if (
        r.getContentType() == null ||
        !r.getContentType().startsWith("application/json")
      ) {
        reject(response, 415, "请求必须为JSON");
        return;
      }
      if (r.getContentLengthLong() > 512000) {
        reject(response, 413, "请求内容过大");
        return;
      }
    }
    chain.doFilter(r, response);
  }

  private void reject(HttpServletResponse r, int status, String message)
    throws IOException {
    r.setStatus(status);
    r.setContentType("application/json;charset=UTF-8");
    r.getWriter().write(json.write(Map.of("error", message)));
  }
}
