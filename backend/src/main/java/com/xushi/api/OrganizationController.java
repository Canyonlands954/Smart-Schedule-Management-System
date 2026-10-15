package com.xushi.api;

import static com.xushi.common.Fields.*;

import com.xushi.auth.AuthService;
import com.xushi.organization.OrganizationService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class OrganizationController {

  private final AuthService auth;
  private final OrganizationService org;

  public OrganizationController(AuthService auth, OrganizationService org) {
    this.auth = auth;
    this.org = org;
  }

  @PostMapping("/users")
  public ResponseEntity<?> user(
    @RequestBody Map<String, Object> d,
    HttpServletRequest r
  ) {
    return ResponseEntity.status(201).body(
      map("id", org.createUser(auth.require(r), d))
    );
  }

  @PutMapping("/users/{id}")
  public Object user(
    @PathVariable long id,
    @RequestBody Map<String, Object> d,
    HttpServletRequest r
  ) {
    org.editUser(auth.require(r), id, d);
    return map("ok", true);
  }

  @PostMapping("/teams")
  public ResponseEntity<?> team(
    @RequestBody Map<String, Object> d,
    HttpServletRequest r
  ) {
    return ResponseEntity.status(201).body(
      map("id", org.createTeam(auth.require(r), d))
    );
  }

  @PutMapping("/teams/{id}")
  public Object team(
    @PathVariable long id,
    @RequestBody Map<String, Object> d,
    HttpServletRequest r
  ) {
    org.editTeam(auth.require(r), id, d);
    return map("ok", true);
  }

  @DeleteMapping("/teams/{id}")
  public Object team(@PathVariable long id, HttpServletRequest r) {
    org.deleteTeam(auth.require(r), id);
    return map("ok", true);
  }

  @PostMapping("/rooms")
  public ResponseEntity<?> room(
    @RequestBody Map<String, Object> d,
    HttpServletRequest r
  ) {
    return ResponseEntity.status(201).body(
      map("id", org.createRoom(auth.require(r), d))
    );
  }

  @PutMapping("/rooms/{id}")
  public Object room(
    @PathVariable long id,
    @RequestBody Map<String, Object> d,
    HttpServletRequest r
  ) {
    org.editRoom(auth.require(r), id, d);
    return map("ok", true);
  }

  @DeleteMapping("/rooms/{id}")
  public Object room(@PathVariable long id, HttpServletRequest r) {
    org.deleteRoom(auth.require(r), id);
    return map("ok", true);
  }
}
