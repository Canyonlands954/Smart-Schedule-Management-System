package com.xushi.api;

import static com.xushi.common.Fields.*;

import com.xushi.auth.AuthService;
import com.xushi.collaboration.StateService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class CollaborationController {

  private final AuthService auth;
  private final StateService state;

  public CollaborationController(AuthService auth, StateService state) {
    this.auth = auth;
    this.state = state;
  }

  @GetMapping("/state")
  public Object state(
    @RequestParam(required = false) String start,
    @RequestParam(required = false) String end,
    HttpServletRequest r
  ) {
    return state.state(auth.require(r), start, end);
  }

  @GetMapping("/team-busy")
  public Object busy(
    @RequestParam(name = "team_id", required = false) Long team,
    @RequestParam(required = false) String start,
    @RequestParam(required = false) String end,
    HttpServletRequest r
  ) {
    var u = auth.require(r);
    return state.busy(
      u,
      team == null ? (u.teamId() == null ? 0 : u.teamId()) : team,
      start,
      end
    );
  }

  @PostMapping("/notifications/read")
  public Object read(@RequestBody Map<String, Object> d, HttpServletRequest r) {
    state.read(auth.require(r), d);
    return map("ok", true);
  }

  @GetMapping("/audit")
  public Object audit(HttpServletRequest r) {
    return state.audit(auth.require(r));
  }

  @GetMapping("/conflict-report")
  public Object report(HttpServletRequest r) {
    return state.report(auth.require(r));
  }

  @GetMapping("/metrics")
  public Object metrics(HttpServletRequest r) {
    return state.metrics(auth.require(r));
  }
}
