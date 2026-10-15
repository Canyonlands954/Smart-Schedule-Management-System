package com.xushi.api;

import static com.xushi.common.Fields.*;

import com.xushi.auth.*;
import com.xushi.common.*;
import jakarta.servlet.http.*;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class AuthController {

  private final AuthService auth;

  public AuthController(AuthService auth) {
    this.auth = auth;
  }

  @GetMapping("/health")
  public Object health() {
    return map("ok", true, "architecture", "spring-boot-postgresql");
  }

  @GetMapping("/auth/status")
  public Object status(HttpServletRequest r) {
    Object u = null;
    try {
      u = auth.require(r).publicView();
    } catch (Problem ignored) {}
    return map("initialized", auth.initialized(), "user", u);
  }

  @PostMapping({ "/auth/register", "/auth/setup" })
  public ResponseEntity<?> register(
    @RequestBody Map<String, Object> d,
    HttpServletRequest r
  ) {
    auth.register(d, r.getRemoteAddr(), r.getRequestURI().endsWith("setup"));
    return ResponseEntity.status(201).body(map("ok", true));
  }

  @PostMapping("/auth/login")
  public Object login(
    @RequestBody Map<String, Object> d,
    HttpServletRequest r,
    HttpServletResponse response
  ) {
    return auth.login(d, r.getRemoteAddr(), response);
  }

  @PostMapping("/auth/logout")
  public Object logout(HttpServletRequest r, HttpServletResponse response) {
    auth.logout(r, response);
    return map("ok", true);
  }

  @PostMapping("/auth/password")
  public Object password(
    @RequestBody Map<String, Object> d,
    HttpServletRequest r,
    HttpServletResponse response
  ) {
    auth.changePassword(auth.require(r), d, response);
    return map("ok", true);
  }

  @PostMapping("/admin-invitations")
  public ResponseEntity<?> invite(HttpServletRequest r) {
    return ResponseEntity.status(201).body(auth.invite(auth.require(r)));
  }
}
