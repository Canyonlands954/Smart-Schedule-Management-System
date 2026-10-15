package com.xushi.collaboration;

import static com.xushi.common.Fields.*;

import com.xushi.ai.ModelConfiguration;
import com.xushi.auth.*;
import com.xushi.common.*;
import com.xushi.data.Database;
import com.xushi.schedule.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StateService {

  private final Database db;
  private final AuthService auth;
  private final Permissions permissions;
  private final EventRepository events;
  private final ConflictService conflicts;
  private final ModelConfiguration configuration;

  public StateService(
    Database db,
    AuthService auth,
    Permissions permissions,
    EventRepository events,
    ConflictService conflicts,
    ModelConfiguration configuration
  ) {
    this.db = db;
    this.auth = auth;
    this.permissions = permissions;
    this.events = events;
    this.conflicts = conflicts;
    this.configuration = configuration;
  }

  private String range(String start, String end, List<Object> args) {
    if (start == null && end == null) return "";
    if (start == null || end == null) throw new Problem(
      "日期范围需要同时指定开始和结束"
    );
    var a = time(start);
    var b = time(end);
    if (
      !b.isAfter(a) || Duration.between(a, b).toDays() > 366
    ) throw new Problem("日期范围须为一年以内");
    args.add(b);
    args.add(a);
    return " AND e.start_at<? AND e.end_at>?";
  }

  @Transactional(readOnly = true)
  public Object state(User u, String start, String end) {
    u = auth.refresh(u);
    List<Object> args = new ArrayList<>();
    String condition = " WHERE 1=1";
    if (!u.admin()) {
      condition +=
        " AND (e.target_id=? OR e.creator_id=?" +
        (u.leader() ? " OR u.team_id=?" : "") +
        ")";
      args.add(u.id());
      args.add(u.id());
      if (u.leader()) args.add(u.teamId());
    }
    condition += range(start, end, args);
    final User user = u;
    var ev = events
      .list(condition + " ORDER BY e.start_at,e.id", args.toArray())
      .stream()
      .filter(e -> permissions.view(user, e))
      .toList();
    return map(
      "user",
      u.publicView(),
      "users",
      db.rows(
        "SELECT id,username,name,role,team_id,active FROM users ORDER BY id"
      ),
      "teams",
      db.rows("SELECT * FROM teams ORDER BY id"),
      "rooms",
      db.rows("SELECT * FROM rooms ORDER BY id"),
      "events",
      ev,
      "notifications",
      db.rows(
        "SELECT * FROM notifications WHERE user_id=? ORDER BY id DESC LIMIT 200",
        u.id()
      ),
      "unread_count",
      db.count(
        "SELECT count(*) FROM notifications WHERE user_id=? AND is_read=0",
        u.id()
      ),
      "ai_mode",
      configuration.enabled(u) ? "model" : "rules"
    );
  }

  @Transactional(readOnly = true)
  public Object busy(User u, long team, String start, String end) {
    u = auth.refresh(u);
    if (!u.admin() && !same(u.teamId(), team)) throw new Problem(
      "只能查看所属团队忙闲",
      403
    );
    List<Object> args = new ArrayList<>();
    args.add(team);
    String q =
      "SELECT e.target_id,u.name,e.start_at start,e.end_at AS end FROM events e JOIN users u ON e.target_id=u.id WHERE u.team_id=? AND e.status IN ('pending','accepted') AND e.phase<>'completed'";
    q += range(start, end, args);
    return map("busy", db.rows(q + " ORDER BY e.start_at", args.toArray()));
  }

  @Transactional
  public void read(User u, Map<String, Object> d) {
    if (d.get("id") == null) db.update(
      "UPDATE notifications SET is_read=1 WHERE user_id=?",
      u.id()
    );
    else db.update(
      "UPDATE notifications SET is_read=1 WHERE user_id=? AND id=?",
      u.id(),
      id(d.get("id"))
    );
  }

  public Object audit(User u) {
    auth.admin(u);
    return map(
      "items",
      db.rows(
        "SELECT a.*,u.name actor_name FROM audit a LEFT JOIN users u ON a.actor_id=u.id ORDER BY a.id DESC LIMIT 200"
      )
    );
  }

  @Transactional(readOnly = true)
  public Object report(User u) {
    List<Map<String, Object>> pairs = new ArrayList<>(),
      advisories = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (var e : events.list(
      " WHERE e.status IN ('pending','accepted') AND e.phase<>'completed' ORDER BY e.start_at"
    )) {
      if (!permissions.view(u, e)) continue;
      for (var c : conflicts.find(e, id(e.get("id")))) {
        long a = id(e.get("id")),
          b = id(c.get("event_id"));
        String key = Math.min(a, b) + ":" + Math.max(a, b);
        if (seen.add(key)) pairs.add(
          map("event", e, "conflict", conflicts.sanitized(u, List.of(c)).get(0))
        );
      }
      var advisory = conflicts.advisory(u, e);
      if (!((List<?>) advisory.get("warnings")).isEmpty()) {
        var result = new LinkedHashMap<>(advisory);
        result.put("event", e);
        advisories.add(result);
      }
    }
    return map("pairs", pairs, "advisories", advisories);
  }

  @Transactional(readOnly = true)
  public Object metrics(User u) {
    auth.admin(u);
    var all = db.rows(
      "SELECT e.*,u.username target_username,v.username creator_username FROM events e JOIN users u ON e.target_id=u.id JOIN users v ON e.creator_id=v.id"
    );
    var real = all
      .stream()
      .filter(e -> !demo(e))
      .toList();
    var drafts = db.rows(
      "SELECT d.*,u.username owner_username FROM ai_drafts d LEFT JOIN users u ON d.owner_id=u.id"
    );
    var realDrafts = drafts
      .stream()
      .filter(d -> !s(d, "owner_username").startsWith("demo_"))
      .toList();
    Set<Long> ids = new HashSet<>();
    real.forEach(e -> ids.add(id(e.get("id"))));
    Map<Long, Map<String, Object>> previous = new HashMap<>();
    Map<Long, OffsetDateTime> pending = new HashMap<>();
    List<Double> durations = new ArrayList<>();
    for (var row : db.rows(
      "SELECT * FROM event_history ORDER BY event_id,id"
    )) {
      long eid = id(row.get("event_id"));
      if (!ids.contains(eid)) continue;
      var snap = object(row.get("snapshot"));
      var old = previous.get(eid);
      String action = s(row, "action");
      if (
        Set.of("created", "modified").contains(action) &&
        s(snap, "status").equals("pending") &&
        !same(snap.get("target_id"), snap.get("creator_id"))
      ) {
        boolean changed =
          old == null ||
          !s(old, "status").equals("pending") ||
          List.of(
            "target_id",
            "start",
            "end",
            "location",
            "organize_team_id",
            "buffer_before",
            "buffer_after",
            "travel_minutes",
            "checklist"
          )
            .stream()
            .anyMatch(k -> !same(old.get(k), snap.get(k)));
        if (changed) pending.put(
          eid,
          time(snap.getOrDefault("updated_at", row.get("created_at")))
        );
      } else if (
        Set.of("accepted", "rejected").contains(action) &&
        same(row.get("actor_id"), snap.get("target_id")) &&
        old != null &&
        s(old, "status").equals("pending")
      ) {
        var dispatched = pending.remove(eid);
        if (dispatched != null) {
          double minutes =
            Duration.between(
              dispatched,
              time(row.get("created_at"))
            ).toMillis() / 60000.0;
          if (minutes >= 0) durations.add(minutes);
        }
      } else if (
        Set.of("cancelled", "rejected").contains(s(snap, "status"))
      ) pending.remove(eid);
      previous.put(eid, snap);
    }
    return map(
      "event_count",
      real.size(),
      "completed",
      real
        .stream()
        .filter(
          e ->
            s(e, "phase").equals("completed") &&
            s(e, "status").equals("accepted")
        )
        .count(),
      "pending",
      real
        .stream()
        .filter(e -> s(e, "status").equals("pending"))
        .count(),
      "parse_count",
      realDrafts.size(),
      "complete_drafts",
      realDrafts
        .stream()
        .filter(d -> n(d, "complete") == 1)
        .count(),
      "sent_drafts",
      realDrafts
        .stream()
        .filter(d -> d.get("sent_event_id") != null)
        .count(),
      "corrected_drafts",
      realDrafts
        .stream()
        .filter(d -> d.get("sent_event_id") != null && n(d, "corrected") == 1)
        .count(),
      "average_confirmation_minutes",
      durations.isEmpty()
        ? null
        : Math.round(
            durations
              .stream()
              .mapToDouble(Double::doubleValue)
              .average()
              .orElse(0) * 10
          ) / 10.0,
      "confirmation_samples",
      durations.size(),
      "rule_samples",
      realDrafts
        .stream()
        .filter(d -> s(d, "engine").equals("rules"))
        .count(),
      "model_samples",
      realDrafts
        .stream()
        .filter(d -> s(d, "engine").equals("model"))
        .count(),
      "demo_event_count",
      all.size() - real.size(),
      "demo_parse_count",
      drafts.size() - realDrafts.size(),
      "demo_completed",
      all
        .stream()
        .filter(e -> demo(e) && s(e, "phase").equals("completed"))
        .count()
    );
  }

  private boolean demo(Map<String, Object> e) {
    return (
      s(e, "target_username").startsWith("demo_") ||
      s(e, "creator_username").startsWith("demo_") ||
      s(e, "title").startsWith("[模拟]")
    );
  }
}
