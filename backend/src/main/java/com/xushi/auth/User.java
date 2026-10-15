package com.xushi.auth;

import static com.xushi.common.Fields.*;

import java.util.*;

public record User(
  long id,
  String username,
  String name,
  String role,
  Long teamId,
  boolean leader
) {
  public boolean admin() {
    return role.equals("admin");
  }

  public boolean manager() {
    return admin() || leader;
  }

  public Map<String, Object> publicView() {
    return map(
      "id",
      id,
      "username",
      username,
      "name",
      name,
      "role",
      role,
      "team_id",
      teamId,
      "active",
      1,
      "is_leader",
      leader
    );
  }
}
