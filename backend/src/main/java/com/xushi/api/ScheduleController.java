package com.xushi.api;

import static com.xushi.common.Fields.*;

import com.xushi.auth.AuthService;
import com.xushi.schedule.ScheduleService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/events")
public class ScheduleController {

  private final AuthService auth;
  private final ScheduleService service;

  public ScheduleController(AuthService auth, ScheduleService service) {
    this.auth = auth;
    this.service = service;
  }

  @PostMapping
  public ResponseEntity<?> create(
    @RequestBody Map<String, Object> d,
    HttpServletRequest r
  ) {
    var result = service.create(auth.require(r), d);
    return ResponseEntity.status(
      Boolean.TRUE.equals(result.get("replayed")) ? 200 : 201
    ).body(result);
  }

  @PostMapping("/check")
  public Object check(
    @RequestBody Map<String, Object> d,
    HttpServletRequest r
  ) {
    return service.check(auth.require(r), d);
  }

  @PutMapping("/{id}")
  public Object edit(
    @PathVariable long id,
    @RequestBody Map<String, Object> d,
    HttpServletRequest r
  ) {
    return service.edit(auth.require(r), id, d);
  }

  @DeleteMapping("/{id}")
  public Object cancel(
    @PathVariable long id,
    @RequestBody Map<String, Object> d,
    HttpServletRequest r
  ) {
    service.cancel(auth.require(r), id, d);
    return map("ok", true);
  }

  @PostMapping("/{id}/respond")
  public Object respond(
    @PathVariable long id,
    @RequestBody Map<String, Object> d,
    HttpServletRequest r
  ) {
    service.respond(auth.require(r), id, d);
    return map("ok", true);
  }

  @PostMapping("/{id}/{op:progress|checklist|reschedule}")
  public Object action(
    @PathVariable long id,
    @PathVariable String op,
    @RequestBody Map<String, Object> d,
    HttpServletRequest r
  ) {
    return service.action(auth.require(r), id, op, d);
  }

  @GetMapping("/{id}/history")
  public Object history(@PathVariable long id, HttpServletRequest r) {
    return service.history(auth.require(r), id);
  }
}
