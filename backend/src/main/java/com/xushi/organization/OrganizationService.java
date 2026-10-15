package com.xushi.organization;

import static com.xushi.common.Fields.*;

import com.xushi.auth.*;
import com.xushi.common.*;
import com.xushi.data.Database;
import com.xushi.schedule.EventRepository;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrganizationService {

  private final Database db;
  private final AuthService auth;
  private final Passwords passwords;
  private final EventRepository events;

  public OrganizationService(
    Database db,
    AuthService auth,
    Passwords passwords,
    EventRepository events
  ) {
    this.db = db;
    this.auth = auth;
    this.passwords = passwords;
    this.events = events;
  }

  public Long team(Object value) {
    Long id = nullableId(value);
    if (
      id != null && db.count("SELECT count(*) FROM teams WHERE id=?", id) == 0
    ) throw new Problem("团队不存在");
    return id;
  }

  @Transactional
  public long createUser(User u, Map<String, Object> d) {
    db.lock();
    auth.admin(auth.refresh(u));
    String role = Objects.toString(d.getOrDefault("role", "member"));
    if (!Set.of("admin", "member").contains(role)) throw new Problem(
      "角色不正确"
    );
    long id = auth.createUser(d, role, team(d.get("team_id")));
    db.audit(u.id(), "user.create", map("user_id", id));
    return id;
  }

  @Transactional
  public void editUser(User u, long target, Map<String, Object> d) {
    db.lock();
    auth.admin(auth.refresh(u));
    var old = db.one("SELECT * FROM users WHERE id=?", target);
    if (old == null) throw new Problem("用户不存在", 404);
    String role = Objects.toString(d.getOrDefault("role", old.get("role")));
    long active = id(d.getOrDefault("active", old.get("active")));
    if (
      !Set.of("admin", "member").contains(role) || active > 1
    ) throw new Problem("角色或状态无效");
    if (
      s(old, "role").equals("admin") &&
      (!role.equals("admin") || active == 0) &&
      db.count("SELECT count(*) FROM users WHERE role='admin' AND active=1") <=
        1
    ) throw new Problem("必须保留至少一位启用的管理员");
    Long team = team(d.getOrDefault("team_id", old.get("team_id")));
    String name = text(
      d.getOrDefault("name", old.get("name")),
      "姓名",
      50,
      true
    );
    boolean moved = !same(team, old.get("team_id"));
    if (moved || active == 0) db.update(
      "UPDATE teams SET leader_id=NULL WHERE leader_id=?",
      target
    );
    db.update(
      "UPDATE users SET name=?,role=?,team_id=?,active=? WHERE id=?",
      name,
      role,
      team,
      active,
      target
    );
    if (
      active == 0 || !s(old, "role").equals(role) || !s(d, "password").isEmpty()
    ) db.update("DELETE FROM sessions WHERE user_id=?", target);
    if (!s(d, "password").isEmpty()) db.update(
      "UPDATE users SET password=? WHERE id=?",
      passwords.encode(d.get("password")),
      target
    );
    if (moved || !same(active, old.get("active"))) for (var e : events.list(
      " WHERE e.target_id=? AND e.status IN ('pending','accepted') AND e.phase<>'completed'",
      target
    ))
      db.notify(
        id(e.get("creator_id")),
        id(e.get("id")),
        "接收人组织信息变更",
        name + "的团队或启用状态已调整，请检查：" + e.get("title")
      );
    db.audit(u.id(), "user.update", map("user_id", target));
  }

  @Transactional
  public long createTeam(User u, Map<String, Object> d) {
    db.lock();
    auth.admin(auth.refresh(u));
    long id = db.insert(
      "INSERT INTO teams(name,description) VALUES(?,?)",
      text(d.get("name"), "团队名称", 50, true),
      text(d.getOrDefault("description", ""), "团队描述", 500, false)
    );
    db.audit(u.id(), "team.create", map("team_id", id));
    return id;
  }

  @Transactional
  public void editTeam(User u, long id, Map<String, Object> d) {
    db.lock();
    auth.admin(auth.refresh(u));
    var old = db.one("SELECT * FROM teams WHERE id=?", id);
    if (old == null) throw new Problem("团队不存在", 404);
    Long leader = nullableId(d.getOrDefault("leader_id", old.get("leader_id")));
    if (
      leader != null &&
      db.count(
        "SELECT count(*) FROM users WHERE id=? AND team_id=? AND active=1",
        leader,
        id
      ) == 0
    ) throw new Problem("负责人必须是团队内启用的成员");
    db.update(
      "UPDATE teams SET name=?,description=?,leader_id=? WHERE id=?",
      text(d.getOrDefault("name", old.get("name")), "团队名称", 50, true),
      text(
        d.getOrDefault("description", old.get("description")),
        "描述",
        500,
        false
      ),
      leader,
      id
    );
    db.audit(u.id(), "team.update", map("team_id", id));
  }

  @Transactional
  public void deleteTeam(User u, long id) {
    db.lock();
    auth.admin(auth.refresh(u));
    team(id);
    if (
      db.count("SELECT count(*) FROM users WHERE team_id=?", id) > 0
    ) throw new Problem("请先将团队成员转移至其他团队");
    if (
      events
        .list(
          " WHERE e.status IN ('pending','accepted') AND e.phase<>'completed'"
        )
        .stream()
        .anyMatch(e -> same(e.get("organize_team_id"), id))
    ) throw new Problem("团队仍有关联的有效组织安排");
    db.update("DELETE FROM teams WHERE id=?", id);
    db.audit(u.id(), "team.delete", map("team_id", id));
  }

  @Transactional
  public long createRoom(User u, Map<String, Object> d) {
    db.lock();
    auth.admin(auth.refresh(u));
    long capacity = id(d.getOrDefault("capacity", 10));
    if (capacity < 1 || capacity > 1000) throw new Problem("容量须为1–1000");
    long id = db.insert(
      "INSERT INTO rooms(name,capacity) VALUES(?,?)",
      text(d.get("name"), "会议室名称", 100, true),
      capacity
    );
    db.audit(u.id(), "room.create", map("room_id", id));
    return id;
  }

  @Transactional
  public void editRoom(User u, long id, Map<String, Object> d) {
    db.lock();
    auth.admin(auth.refresh(u));
    var old = db.one("SELECT * FROM rooms WHERE id=?", id);
    if (old == null) throw new Problem("会议室不存在", 404);
    String name = text(
      d.getOrDefault("name", old.get("name")),
      "会议室名称",
      100,
      true
    );
    long cap = id(d.getOrDefault("capacity", old.get("capacity")));
    if (cap < 1 || cap > 1000) throw new Problem("容量须为1–1000");
    db.update("UPDATE rooms SET name=?,capacity=? WHERE id=?", name, cap, id);
    if (!name.equals(old.get("name"))) for (var e : events.list(
      " WHERE (e.room_id=? OR e.location=?) AND e.status IN ('pending','accepted') AND e.phase<>'completed'",
      id,
      old.get("name")
    )) {
      long eid = id(e.get("id"));
      events.snapshot(u.id(), eid, "before_room_rename");
      db.update(
        "UPDATE events SET location=?,room_id=?,updated_at=clock_timestamp() WHERE id=?",
        name,
        id,
        eid
      );
      db.notify(
        id(e.get("target_id")),
        eid,
        "会议室名称已更新",
        old.get("name") + "已更名为" + name
      );
      events.snapshot(u.id(), eid, "room_renamed");
    }
    db.audit(u.id(), "room.update", map("room_id", id));
  }

  @Transactional
  public void deleteRoom(User u, long id) {
    db.lock();
    auth.admin(auth.refresh(u));
    if (
      db.count(
        "SELECT count(*) FROM events WHERE room_id=? AND end_at>now() AND status IN ('pending','accepted') AND phase<>'completed'",
        id
      ) > 0
    ) throw new Problem("会议室仍有未结束的有效日程");
    db.update("UPDATE events SET room_id=NULL WHERE room_id=?", id);
    if (db.update("DELETE FROM rooms WHERE id=?", id) == 0) throw new Problem(
      "会议室不存在",
      404
    );
    db.audit(u.id(), "room.delete", map("room_id", id));
  }
}
