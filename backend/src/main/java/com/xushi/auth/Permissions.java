package com.xushi.auth;

import static com.xushi.common.Fields.*;

import com.xushi.common.*;
import com.xushi.data.Database;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class Permissions {

  private final Database db;

  public Permissions(Database db) {
    this.db = db;
  }

  public boolean assign(User u, long target) {
    return (
      u.admin() ||
      u.id() == target ||
      (u.leader() &&
        db.count(
          "SELECT count(*) FROM users WHERE id=? AND team_id=? AND active=1",
          target,
          u.teamId()
        ) > 0)
    );
  }

  public boolean view(User u, Map<String, Object> e) {
    return (
      u.admin() ||
      id(e.get("target_id")) == u.id() ||
      (id(e.get("creator_id")) == u.id() &&
        assign(u, id(e.get("target_id")))) ||
      (u.leader() &&
        db.count(
          "SELECT count(*) FROM users WHERE id=? AND team_id=?",
          e.get("target_id"),
          u.teamId()
        ) > 0)
    );
  }

  public boolean edit(User u, Map<String, Object> e) {
    return (
      u.admin() ||
      (id(e.get("creator_id")) == u.id() && assign(u, id(e.get("target_id"))))
    );
  }

  public void editable(User u, Map<String, Object> e) {
    if (!edit(u, e)) throw new Problem(
      "收到的安排只能确认或拒绝，请联系通知人修改",
      403
    );
  }
}
