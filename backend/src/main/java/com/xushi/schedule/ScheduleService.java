package com.xushi.schedule;

import static com.xushi.common.Fields.*;

import com.xushi.auth.*;
import com.xushi.common.*;
import com.xushi.data.Database;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ScheduleService {

  private final Database db;
  private final AuthService auth;
  private final Permissions permissions;
  private final EventRepository events;
  private final ConflictService conflicts;
  private final Json json;
  private final SchedulingPlanner planner;

  public ScheduleService(
    Database db,
    AuthService auth,
    Permissions permissions,
    EventRepository events,
    ConflictService conflicts,
    Json json,
    SchedulingPlanner planner
  ) {
    this.db = db;
    this.auth = auth;
    this.permissions = permissions;
    this.events = events;
    this.conflicts = conflicts;
    this.json = json;
    this.planner = planner;
  }

  public Map<String, Object> validate(User u, Map<String, Object> data) {
    long target = id(data.getOrDefault("target_id", u.id()));
    if (!permissions.assign(u, target)) throw new Problem(
      "只能给自己或授权团队内成员安排日程",
      403
    );
    if (
      db.count("SELECT count(*) FROM users WHERE id=? AND active=1", target) ==
      0
    ) throw new Problem("接收人不存在或已停用");
    String start = stamp(data.get("start")),
      end = stamp(data.get("end"));
    long duration = Duration.between(time(start), time(end)).toSeconds();
    if (duration <= 0 || duration > 604800) throw new Problem(
      "结束时间须晚于开始，单项日程不能超过7天"
    );
    return map(
      "title",
      text(data.get("title"), "日程标题", 100, true),
      "target_id",
      target,
      "start",
      start,
      "end",
      end,
      "location",
      text(data.getOrDefault("location", ""), "地点", 100, false),
      "notes",
      text(data.getOrDefault("notes", ""), "备注", 2000, false)
    );
  }

  public boolean changed(
    Map<String, Object> old,
    Map<String, Object> item,
    Map<String, Object> attrs
  ) {
    for (String k : List.of(
      "target_id",
      "start",
      "end",
      "location",
      "organize_team_id",
      "buffer_before",
      "buffer_after",
      "travel_minutes"
    ))
      if (
        !same(old.get(k), attrs.containsKey(k) ? attrs.get(k) : item.get(k))
      ) return true;
    return !objects(old.get("checklist"))
      .stream()
      .map(x -> x.get("label"))
      .toList()
      .equals(
        objects(attrs.get("checklist"))
          .stream()
          .map(x -> x.get("label"))
          .toList()
      );
  }

  public Map<String, Object> metadata(
    User u,
    Map<String, Object> d,
    Map<String, Object> item,
    Map<String, Object> old
  ) {
    Map<String, Object> a = map(
      "priority",
      d.getOrDefault("priority", old.getOrDefault("priority", "normal"))
    );
    if (
      !Set.of("normal", "high", "urgent").contains(s(a, "priority"))
    ) throw new Problem("优先级无效");
    for (String k : List.of(
      "buffer_before",
      "buffer_after",
      "travel_minutes"
    )) {
      long n = id(d.getOrDefault(k, old.getOrDefault(k, 0)));
      if (n > 180) throw new Problem("准备、收尾与通勤时长须为0–180分钟");
      a.put(k, n);
    }
    Long team = nullableId(
      d.getOrDefault("organize_team_id", old.get("organize_team_id"))
    );
    if (team != null) {
      if (
        db.count(
          "SELECT count(*) FROM users WHERE id=? AND team_id=?",
          item.get("target_id"),
          team
        ) == 0
      ) throw new Problem("接收人必须属于要组织的团队");
      if (!u.admin() && !same(u.teamId(), team)) throw new Problem(
        "只能组织所属团队",
        403
      );
    }
    a.put("organize_team_id", team);
    Object raw = d.getOrDefault(
      "checklist",
      old.getOrDefault("checklist", List.of())
    );
    if (!(raw instanceof List<?> list) || list.size() > 12) throw new Problem(
      "准备清单最多12项"
    );
    Map<String, Boolean> prior = new HashMap<>();
    objects(old.get("checklist")).forEach(x ->
      prior.put(s(x, "label"), Boolean.TRUE.equals(x.get("done")))
    );
    List<Map<String, Object>> checks = new ArrayList<>();
    Set<String> labels = new HashSet<>();
    for (Object x : list) {
      String label = text(
        x instanceof String ? x : object(x).get("label"),
        "清单项",
        100,
        true
      );
      if (!labels.add(label)) throw new Problem("准备清单不能包含重复项目");
      checks.add(map("label", label, "done", prior.getOrDefault(label, false)));
    }
    if (
      d.containsKey("checklist") && !prior.isEmpty() && checks.isEmpty()
    ) throw new Problem("已有准备清单不能直接清空，请逐项调整要求");
    if (team != null) for (String label : List.of(
      "已通知全组成员",
      "会议资料已准备"
    ))
      if (labels.add(label)) checks.add(
        map("label", label, "done", prior.getOrDefault(label, false))
      );
    if (checks.size() > 12) throw new Problem("团队准备清单合计最多12项");
    a.put("checklist", checks);
    a.put("completion_report", old.getOrDefault("completion_report", ""));
    a.put("reschedule_request", old.get("reschedule_request"));
    Long draft = nullableId(d.getOrDefault("draft_id", old.get("draft_id")));
    if (draft != null && draft <= 0) throw new Problem("AI草稿编号不正确");
    if (!old.isEmpty() && !same(draft, old.get("draft_id"))) throw new Problem(
      "已发送安排不能更换关联的AI草稿"
    );
    a.put("draft_id", draft);
    if (!old.isEmpty() && changed(old, item, a)) checks.forEach(x ->
      x.put("done", false)
    );
    return a;
  }

  @Transactional(readOnly = true)
  public Map<String, Object> check(User u, Map<String, Object> d) {
    u = auth.refresh(u);
    var item = validate(u, d);
    long exclude = id(d.getOrDefault("exclude_id", 0));
    Map<String, Object> old = exclude > 0 ? events.get(exclude) : map();
    if (exclude > 0) permissions.editable(u, old);
    item.putAll(metadata(u, d, item, old));
    var result = map(
      "conflicts",
      conflicts.sanitized(u, conflicts.find(item, exclude)),
      "suggestions",
      planner.suggest(item, exclude)
    );
    result.putAll(conflicts.advisory(u, item));
    if (objects(result.get("suggestions")).isEmpty()) {
      @SuppressWarnings("unchecked")
      var warnings = (List<String>) result.get("warnings");
      warnings.add(
        "设定范围内没有满足人员、容量与工作时段要求的方案；请调整时长、范围或组织要求。"
      );
    }
    return result;
  }

  @Transactional
  public Map<String, Object> create(User u, Map<String, Object> d) {
    db.lock();
    u = auth.refresh(u);
    String request = d.containsKey("request_id")
      ? text(d.get("request_id"), "请求编号", 100, true)
      : null;
    Map<String, Object> canonical = new TreeMap<>(d);
    canonical.remove("request_id");
    String digest = Passwords.digest(json.write(canonical));
    if (request != null) {
      var previous = db.one(
        "SELECT * FROM event_requests WHERE user_id=? AND request_id=?",
        u.id(),
        request
      );
      if (previous != null) {
        if (!s(previous, "payload_hash").equals(digest)) throw new Problem(
          "此请求已处理，请重新发起新的安排",
          409
        );
        var e = events.get(id(previous.get("event_id")));
        if (!permissions.view(u, e)) throw new Problem(
          "你已无权查看该安排",
          403
        );
        return map("event", e, "replayed", true);
      }
    }
    var e = write(u, d, 0);
    if (request != null) db.update(
      "INSERT INTO event_requests VALUES(?,?,?,?)",
      u.id(),
      request,
      digest,
      e.get("id")
    );
    return map("event", e, "replayed", false);
  }

  @Transactional
  public Map<String, Object> edit(User u, long id, Map<String, Object> d) {
    db.lock();
    return map("event", write(auth.refresh(u), d, id));
  }

  private Map<String, Object> write(User u, Map<String, Object> d, long eid) {
    var item = validate(u, d);
    var old = eid > 0 ? events.get(eid) : map();
    if (eid > 0) {
      permissions.editable(u, old);
      version(d, old);
      if (s(old, "status").equals("cancelled")) throw new Problem(
        "已取消日程不能修改"
      );
      if (s(old, "phase").equals("completed")) throw new Problem(
        "已完成安排不能修改",
        409
      );
    }
    Long changeDraft = nullableId(d.get("ai_change_draft_id"));
    Map<String, Object> changeRow = null;
    if (changeDraft != null) {
      if (eid == 0) throw new Problem("改期草稿只能修改原日程", 409);
      changeRow = db.one(
        "SELECT * FROM ai_drafts WHERE id=? AND owner_id=? FOR UPDATE",
        changeDraft,
        u.id()
      );
      if (
        changeRow == null || changeRow.get("sent_event_id") != null
      ) throw new Problem("改期草稿无效或已使用", 409);
      var parsed = object(changeRow.get("result"));
      if (
        !"update".equals(parsed.get("action")) || !same(parsed.get("id"), eid)
      ) throw new Problem("改期草稿与原日程不匹配", 409);
      version(map("expected_updated_at", parsed.get("updated_at")), old);
    }
    var attrs = metadata(u, d, item, old);
    boolean change = old.isEmpty() || changed(old, item, attrs);
    if (s(old, "phase").equals("in_progress") && change) throw new Problem(
      "安排正在执行，不能修改排期或执行要求",
      409
    );
    var combined = new LinkedHashMap<>(item);
    combined.putAll(attrs);
    var overlap = conflicts.find(combined, eid);
    if (
      !overlap.isEmpty() && !Boolean.TRUE.equals(d.get("override_conflicts"))
    ) throw new Problem(
      "发现日程冲突，请调整时间或明确确认保留冲突",
      409,
      map("conflicts", conflicts.sanitized(u, overlap))
    );
    String status = change
      ? id(item.get("target_id")) == u.id()
        ? "accepted"
        : "pending"
      : s(old, "status");
    var room = db.one("SELECT id FROM rooms WHERE name=?", s(item, "location"));
    Object roomId = room == null ? null : room.get("id");
    String phase = change ? "not_started" : s(old, "phase");
    String moment = now();
    if (eid > 0) {
      events.snapshot(u.id(), eid, "before_change");
      db.update(
        "UPDATE events SET title=?,target_id=?,start_at=?,end_at=?,location=?,room_id=?,notes=?,status=?,phase=?,updated_at=? WHERE id=?",
        item.get("title"),
        item.get("target_id"),
        time(item.get("start")),
        time(item.get("end")),
        item.get("location"),
        roomId,
        item.get("notes"),
        status,
        phase,
        time(moment),
        eid
      );
      if (!same(old.get("target_id"), item.get("target_id"))) db.notify(
        id(old.get("target_id")),
        eid,
        "原日程已转交",
        s(old, "title") + " 已转交其他人。"
      );
    } else {
      eid = db.insert(
        "INSERT INTO events(title,target_id,creator_id,start_at,end_at,location,room_id,notes,status,source,original_text,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
        item.get("title"),
        item.get("target_id"),
        u.id(),
        time(item.get("start")),
        time(item.get("end")),
        item.get("location"),
        roomId,
        item.get("notes"),
        status,
        u.manager() && s(d, "source").equals("ai") ? "ai" : "manual",
        text(d.getOrDefault("original_text", ""), "原始消息", 4000, false),
        time(moment),
        time(moment)
      );
    }
    var req = object(attrs.get("reschedule_request"));
    if (
      "pending".equals(req.get("status")) &&
      !old.isEmpty() &&
      (!same(old.get("start"), item.get("start")) ||
        !same(old.get("end"), item.get("end")))
    ) {
      req = new LinkedHashMap<>(req);
      boolean approved =
        same(req.get("start"), item.get("start")) &&
        same(req.get("end"), item.get("end"));
      req.putAll(
        map(
          "status",
          approved ? "resolved" : "superseded",
          "resolved_at",
          now(),
          "response",
          approved ? "已按申请时间调整" : "通知人提出了其他时间，请重新确认安排"
        )
      );
      attrs.put("reschedule_request", req);
    }
    events.details(eid, attrs);
    Long draft = nullableId(attrs.get("draft_id"));
    if (draft != null && old.isEmpty()) {
      var row = db.one(
        "SELECT * FROM ai_drafts WHERE id=? AND owner_id=? FOR UPDATE",
        draft,
        u.id()
      );
      if (row == null || row.get("sent_event_id") != null) throw new Problem(
        "AI草稿无效或已发送，请重新解析",
        409
      );
      var parsed = object(row.get("result"));
      if ("update".equals(parsed.get("action"))) throw new Problem(
        "此草稿用于修改已有日程，不能创建新安排",
        409
      );
      boolean corrected = List.of(
        "title",
        "target_id",
        "start",
        "end",
        "location",
        "notes"
      )
        .stream()
        .anyMatch(k -> !same(parsed.get(k), item.get(k)));
      db.update(
        "UPDATE ai_drafts SET sent_event_id=?,corrected=? WHERE id=?",
        eid,
        corrected ? 1 : 0,
        draft
      );
    }
    if (changeDraft != null) {
      var parsed = object(changeRow.get("result"));
      boolean corrected = List.of(
        "title",
        "target_id",
        "start",
        "end",
        "location",
        "notes"
      )
        .stream()
        .anyMatch(k -> !same(parsed.get(k), item.get(k)));
      db.update(
        "UPDATE ai_drafts SET sent_event_id=?,corrected=? WHERE id=?",
        eid,
        corrected ? 1 : 0,
        changeDraft
      );
    }
    db.notify(
      id(item.get("target_id")),
      eid,
      old.isEmpty() ? "收到新的日程安排" : "日程已更新",
      u.name() +
        "：" +
        item.get("title") +
        " · " +
        item.get("start") +
        " · " +
        item.get("location")
    );
    db.audit(
      u.id(),
      old.isEmpty() ? "event.create" : "event.update",
      map("event_id", eid, "conflicts_kept", overlap)
    );
    events.snapshot(u.id(), eid, old.isEmpty() ? "created" : "modified");
    return events.get(eid);
  }

  @Transactional
  public void cancel(User u, long eid, Map<String, Object> d) {
    db.lock();
    u = auth.refresh(u);
    var e = events.get(eid);
    permissions.editable(u, e);
    version(d, e);
    if (
      s(e, "status").equals("cancelled") || s(e, "phase").equals("completed")
    ) throw new Problem("已取消或完成安排不能取消", 409);
    db.update(
      "UPDATE events SET status='cancelled',updated_at=clock_timestamp() WHERE id=?",
      eid
    );
    closeRequest(eid, e, "原安排已取消");
    db.notify(
      id(e.get("target_id")),
      eid,
      "日程已取消",
      u.name() + "取消了：" + s(e, "title")
    );
    events.snapshot(u.id(), eid, "cancelled");
    db.audit(u.id(), "event.cancel", map("event_id", eid));
  }

  private void closeRequest(long eid, Map<String, Object> e, String reason) {
    var attrs = events.attrs(e);
    var req = object(attrs.get("reschedule_request"));
    if ("pending".equals(req.get("status"))) {
      req = new LinkedHashMap<>(req);
      req.putAll(
        map("status", "closed", "resolved_at", now(), "response", reason)
      );
      attrs.put("reschedule_request", req);
      events.details(eid, attrs);
    }
  }

  @Transactional
  public void respond(User u, long eid, Map<String, Object> d) {
    db.lock();
    u = auth.refresh(u);
    var e = events.get(eid);
    if (id(e.get("target_id")) != u.id()) throw new Problem(
      "只有接收人可以确认安排",
      403
    );
    version(d, e);
    if (!s(e, "status").equals("pending")) throw new Problem(
      "该安排已处理",
      409
    );
    String status = s(d, "status");
    if (!Set.of("accepted", "rejected").contains(status)) throw new Problem(
      "确认状态不正确"
    );
    db.update(
      "UPDATE events SET status=?,updated_at=clock_timestamp() WHERE id=?",
      status,
      eid
    );
    if (status.equals("rejected")) closeRequest(eid, e, "原安排已拒绝");
    String reason = text(d.getOrDefault("reason", ""), "回复说明", 500, false);
    db.notify(
      id(e.get("creator_id")),
      eid,
      "日程确认反馈",
      u.name() +
        (status.equals("accepted") ? "接受" : "拒绝") +
        "了：" +
        s(e, "title") +
        "。" +
        reason
    );
    events.snapshot(u.id(), eid, status);
    db.audit(
      u.id(),
      "event.respond",
      map("event_id", eid, "status", status, "reason", reason)
    );
  }

  @Transactional
  public Map<String, Object> action(
    User u,
    long eid,
    String op,
    Map<String, Object> d
  ) {
    db.lock();
    u = auth.refresh(u);
    var e = events.get(eid);
    if (!permissions.view(u, e)) throw new Problem("无权查看该安排", 403);
    version(d, e);
    var a = json.copy(events.attrs(e));
    var req = object(a.get("reschedule_request"));
    if (op.equals("progress") || op.equals("checklist")) {
      if (id(e.get("target_id")) != u.id()) throw new Problem(
        "只有接收人可以记录自己的执行进度",
        403
      );
      if (
        !s(e, "status").equals("accepted") || s(e, "phase").equals("completed")
      ) throw new Problem("请先接受安排，已完成安排不能再次操作", 409);
      var checks = objects(a.get("checklist"));
      if (op.equals("checklist")) {
        int index = (int) id(d.get("index"));
        if (
          index < 0 ||
          index >= checks.size() ||
          !(d.get("done") instanceof Boolean)
        ) throw new Problem("清单操作无效");
        checks.get(index).put("done", d.get("done"));
      } else {
        String phase = s(d, "phase");
        if (
          !Set.of("in_progress", "completed").contains(phase)
        ) throw new Problem("进度无效");
        if (
          phase.equals("in_progress") &&
          (!s(e, "phase").equals("not_started") ||
            "pending".equals(req.get("status")))
        ) throw new Problem("安排已开始或改期申请正在处理中", 409);
        if (phase.equals("completed")) {
          if (!s(e, "phase").equals("in_progress")) throw new Problem(
            "请先开始执行",
            409
          );
          if (
            checks.stream().anyMatch(x -> !Boolean.TRUE.equals(x.get("done")))
          ) throw new Problem("请先完成组织准备清单");
          a.put(
            "completion_report",
            text(d.get("report"), "完成反馈", 2000, true)
          );
        }
        db.update("UPDATE events SET phase=? WHERE id=?", phase, eid);
        db.notify(
          id(e.get("creator_id")),
          eid,
          "执行进度更新",
          u.name() +
            (phase.equals("completed") ? "已完成" : "开始执行") +
            "：" +
            s(e, "title") +
            "。" +
            s(a, "completion_report")
        );
      }
    } else if (op.equals("reschedule")) {
      String action = Objects.toString(d.getOrDefault("action", "request"));
      if (action.equals("approve") || action.equals("reject")) {
        permissions.editable(u, e);
        if (!"pending".equals(req.get("status"))) throw new Problem(
          "没有待处理的改期请求"
        );
        if (action.equals("approve")) {
          if (
            !Set.of("pending", "accepted").contains(s(e, "status")) ||
            !s(e, "phase").equals("not_started")
          ) throw new Problem("该安排当前不能改期", 409);
          var payload = new LinkedHashMap<>(e);
          payload.putAll(a);
          payload.putAll(
            map(
              "start",
              req.get("start"),
              "end",
              req.get("end"),
              "expected_updated_at",
              e.get("updated_at"),
              "override_conflicts",
              Boolean.TRUE.equals(d.get("override_conflicts"))
            )
          );
          return map("ok", true, "event", write(u, payload, eid));
        }
        req = new LinkedHashMap<>(req);
        req.putAll(
          map(
            "status",
            "rejected",
            "resolved_at",
            now(),
            "response",
            text(d.getOrDefault("reason", ""), "说明", 500, false)
          )
        );
        db.notify(
          id(e.get("target_id")),
          eid,
          "改期请求未通过",
          s(req, "response")
        );
      } else if (action.equals("withdraw")) {
        if (id(e.get("target_id")) != u.id()) throw new Problem(
          "只有申请人可以撤回",
          403
        );
        if (!"pending".equals(req.get("status"))) throw new Problem(
          "没有待处理的改期请求",
          409
        );
        req = new LinkedHashMap<>(req);
        req.putAll(
          map(
            "status",
            "withdrawn",
            "resolved_at",
            now(),
            "response",
            "申请人已撤回"
          )
        );
        db.notify(
          id(e.get("creator_id")),
          eid,
          "改期申请已撤回",
          u.name() + "撤回了改期申请。"
        );
      } else if (action.equals("request")) {
        if (id(e.get("target_id")) != u.id()) throw new Problem(
          "只有接收人可以请求改期",
          403
        );
        if (
          !Set.of("pending", "accepted").contains(s(e, "status")) ||
          !s(e, "phase").equals("not_started")
        ) throw new Problem("此安排不能申请改期");
        if ("pending".equals(req.get("status"))) throw new Problem(
          "已有待处理的改期请求",
          409
        );
        String start = stamp(d.get("start")),
          end = stamp(d.get("end"));
        long secs = Duration.between(time(start), time(end)).toSeconds();
        if (secs <= 0 || secs > 604800) throw new Problem("改期时长无效");
        if (
          same(start, e.get("start")) && same(end, e.get("end"))
        ) throw new Problem("申请时间与原安排相同");
        req = map(
          "status",
          "pending",
          "start",
          start,
          "end",
          end,
          "reason",
          text(d.get("reason"), "改期原因", 500, true),
          "created_at",
          now()
        );
        db.notify(
          id(e.get("creator_id")),
          eid,
          "收到改期请求",
          u.name() + "请求调整：" + s(e, "title") + "。" + req.get("reason")
        );
      } else throw new Problem("改期操作无效");
      a.put("reschedule_request", req);
    } else throw new Problem("操作不存在", 404);
    events.details(eid, a);
    db.update("UPDATE events SET updated_at=clock_timestamp() WHERE id=?", eid);
    events.snapshot(u.id(), eid, op);
    db.audit(u.id(), "event." + op, map("event_id", eid));
    return map("ok", true);
  }

  @Transactional(readOnly = true)
  public Object history(User u, long id) {
    if (!permissions.view(u, events.get(id))) throw new Problem(
      "无权查看该安排",
      403
    );
    return map(
      "items",
      db.rows(
        "SELECT h.*,u.name actor_name FROM event_history h LEFT JOIN users u ON h.actor_id=u.id WHERE event_id=? ORDER BY h.id DESC",
        id
      )
    );
  }
}
