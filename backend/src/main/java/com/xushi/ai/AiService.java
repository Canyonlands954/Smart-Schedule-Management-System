package com.xushi.ai;

import static com.xushi.common.Fields.*;

import com.xushi.auth.*;
import com.xushi.common.*;
import com.xushi.data.Database;
import com.xushi.schedule.*;
import jakarta.servlet.http.HttpServletRequest;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AiService {

  private final Database db;
  private final AuthService auth;
  private final Permissions permissions;
  private final ConversationResolver resolver;
  private final ModelGateway model;
  private final ModelConfiguration configuration;
  private final Json json;
  private final EventRepository events;
  private final ScheduleService schedules;
  private final TransactionTemplate tx;

  public AiService(
    Database db,
    AuthService auth,
    Permissions permissions,
    ConversationResolver resolver,
    ModelGateway model,
    ModelConfiguration configuration,
    Json json,
    EventRepository events,
    ScheduleService schedules,
    PlatformTransactionManager manager
  ) {
    this.db = db;
    this.auth = auth;
    this.permissions = permissions;
    this.resolver = resolver;
    this.model = model;
    this.configuration = configuration;
    this.json = json;
    this.events = events;
    this.schedules = schedules;
    tx = new TransactionTemplate(manager);
  }

  private User manager(HttpServletRequest request) {
    var u = auth.require(request);
    if (!u.manager()) throw new Problem(
      "AI安排需要管理员或团队负责人权限",
      403
    );
    return u;
  }

  private Map<String, Object> conversation(User u, String id) {
    var row = db.one(
      "SELECT * FROM ai_conversations WHERE id=? AND owner_id=?",
      id,
      u.id()
    );
    if (row == null) throw new Problem("对话不存在或无权访问", 404);
    return row;
  }

  private List<Map<String, Object>> history(String conversation) {
    var rows = db.rows(
      "SELECT role,content FROM ai_turns WHERE conversation_id=? ORDER BY id DESC LIMIT 40",
      conversation
    );
    Collections.reverse(rows);
    return rows;
  }

  private List<Map<String, Object>> contextEvents(User u) {
    var rows = events.list(
      " WHERE e.status IN ('pending','accepted') AND e.phase='not_started' AND e.end_at>? AND e.start_at<? AND (? OR (e.creator_id=? AND (e.target_id=? OR u.team_id=?))) ORDER BY e.start_at LIMIT 100",
      OffsetDateTime.now(ZONE),
      OffsetDateTime.now(ZONE).plusDays(30),
      u.admin(),
      u.id(),
      u.id(),
      u.teamId()
    );
    return rows
      .stream()
      .filter(e -> permissions.edit(u, e))
      .toList();
  }

  private Map<String, Object> brief(Map<String, Object> e) {
    return map(
      "id",
      e.get("id"),
      "title",
      e.get("title"),
      "target_name",
      e.get("target_name"),
      "target_id",
      e.get("target_id"),
      "start",
      e.get("start"),
      "end",
      e.get("end"),
      "location",
      e.get("location"),
      "pending_reschedule",
      "pending".equals(object(e.get("reschedule_request")).get("status"))
    );
  }

  private void reference(User u, Map<String, Object> state, long id) {
    var event = events.get(id);
    permissions.editable(u, event);
    if (
      !Set.of("pending", "accepted").contains(s(event, "status")) ||
      !s(event, "phase").equals("not_started")
    ) throw new Problem("只能调整尚未开始的有效安排", 409);
    var draft = json.copy(event);
    draft.putAll(
      map(
        "action",
        "update",
        "original_draft_id",
        event.get("draft_id"),
        "draft_id",
        null,
        "original_text",
        ""
      )
    );
    state.put("draft", draft);
    state.put("resolution", map());
    state.put("needs_reference", false);
    resolver.mergeEdits(state, map());
    if (event.get("organize_team_id") != null) object(
      state.get("resolution")
    ).put("organize", true);
  }

  private void authorized(User u, Map<String, Object> state) {
    var d = object(state.get("draft"));
    if (
      d.get("organize_team_id") != null &&
      !u.admin() &&
      !same(u.teamId(), d.get("organize_team_id"))
    ) throw new Problem("团队权限已变更，请开启新对话", 403);
    var references = objects(state.get("reference_candidates"))
      .stream()
      .filter(candidate -> {
        var e = events.get(id(candidate.get("id")));
        return (
          permissions.edit(u, e) &&
          Set.of("pending", "accepted").contains(s(e, "status")) &&
          s(e, "phase").equals("not_started")
        );
      })
      .map(candidate -> brief(events.get(id(candidate.get("id")))))
      .toList();
    state.put("reference_candidates", references);
    d.put("reference_candidates", references);
    d.put(
      "candidates",
      objects(d.get("candidates"))
        .stream()
        .filter(
          person ->
            permissions.assign(u, id(person.get("id"))) &&
            db.count(
              "SELECT count(*) FROM users WHERE id=? AND active=1",
              person.get("id")
            ) > 0
        )
        .toList()
    );
    Long target = nullableId(d.get("target_id"));
    if (
      target != null &&
      (db.count("SELECT count(*) FROM users WHERE id=? AND active=1", target) ==
        0 ||
        !permissions.assign(u, target))
    ) throw new Problem("接收人的团队或状态已变更，请开启新对话", 403);
    if (s(d, "action").equals("update")) {
      var e = events.get(id(d.get("id")));
      permissions.editable(u, e);
      boolean sent =
        d.get("draft_id") != null &&
        db.count(
          "SELECT count(*) FROM ai_drafts WHERE id=? AND owner_id=? AND sent_event_id IS NOT NULL",
          d.get("draft_id"),
          u.id()
        ) > 0;
      if (!sent) version(map("expected_updated_at", d.get("updated_at")), e);
    }
  }

  public Object load(HttpServletRequest request, String id) {
    return tx.execute(status -> {
      var u = manager(request);
      var row = conversation(u, id);
      var state = json.copy(object(row.get("state")));
      authorized(u, state);
      var d = object(state.get("draft"));
      d.remove("planning");
      boolean sent =
        d.get("draft_id") != null &&
        db.count(
          "SELECT count(*) FROM ai_drafts WHERE id=? AND owner_id=? AND sent_event_id IS NOT NULL",
          d.get("draft_id"),
          u.id()
        ) > 0;
      return map(
        "conversation_id",
        id,
        "revision",
        row.get("revision"),
        "draft",
        sent ? null : d,
        "history",
        history(id)
      );
    });
  }

  public Object parse(HttpServletRequest request, Map<String, Object> data) {
    var context = Objects.requireNonNull(
      tx.execute(status -> {
        var u = manager(request);
        String message = text(data.get("text"), "消息", 4000, true);
        String cid = s(data, "conversation_id");
        Map<String, Object> state = map();
        long revision = 0;
        if (!cid.isBlank()) {
          var row = conversation(u, cid);
          revision = id(row.get("revision"));
          if (!same(data.get("revision"), revision)) throw new Problem(
            "对话已更新，请刷新后继续",
            409
          );
          state = json.copy(object(row.get("state")));
          authorized(u, state);
        } else cid = UUID.randomUUID().toString();
        if (message.matches(".*(新建|另外安排|再安排|新增一场|重新开始).*")) {
          state = map();
        }
        if (data.containsKey("draft_updates")) resolver.mergeEdits(
          state,
          object(data.get("draft_updates"))
        );
        var users = u.admin()
          ? db.rows(
              "SELECT id,name,username,team_id FROM users WHERE active=1 ORDER BY id"
            )
          : db.rows(
              "SELECT id,name,username,team_id FROM users WHERE active=1 AND (team_id=? OR id=?) ORDER BY id",
              u.teamId(),
              u.id()
            );
        var teams = u.admin()
          ? db.rows("SELECT id,name FROM teams ORDER BY id")
          : db.rows("SELECT id,name FROM teams WHERE id=?", u.teamId());
        var rooms = db.rows("SELECT id,name,capacity FROM rooms ORDER BY id");
        var available = contextEvents(u);
        boolean modifying =
          resolver.modification(message) ||
          Boolean.TRUE.equals(state.get("needs_reference")) ||
          data.get("reference_event_id") != null;
        String instruction = message;
        if (data.get("reference_event_id") != null) {
          reference(u, state, id(data.get("reference_event_id")));
          String pending = s(state, "pending_instruction");
          if (!pending.isBlank()) instruction = pending + "，" + message;
          state.remove("pending_instruction");
        } else if (modifying) {
          var d = object(state.get("draft"));
          Long sent = null;
          if (d.get("draft_id") != null) {
            var row = db.one(
              "SELECT sent_event_id FROM ai_drafts WHERE id=? AND owner_id=?",
              d.get("draft_id"),
              u.id()
            );
            if (row != null) sent = nullableId(row.get("sent_event_id"));
          }
          if (sent != null) reference(u, state, sent);
          else if (
            d.isEmpty() || Boolean.TRUE.equals(state.get("needs_reference"))
          ) {
            var matching = available
              .stream()
              .filter(
                e ->
                  !message.contains("改期申请") ||
                  "pending".equals(
                    object(e.get("reschedule_request")).get("status")
                  )
              )
              .filter(
                e ->
                  !message.matches(".*(?:小(?!时|组|结)|老)[\\p{IsHan}].*") ||
                  message.contains(s(e, "target_name")) ||
                  message.contains(
                    "小" + s(e, "target_name").substring(0, 1)
                  ) ||
                  message.contains("老" + s(e, "target_name").substring(0, 1))
              )
              .toList();
            if (matching.size() == 1) reference(
              u,
              state,
              id(matching.get(0).get("id"))
            );
            else {
              state.put("needs_reference", true);
              state.put("pending_instruction", message);
              state.put(
                "reference_candidates",
                matching.stream().map(this::brief).toList()
              );
            }
          }
        }
        var draft = resolver.apply(state, instruction, users, teams, rooms);
        if (data.get("recipient_id") != null) {
          long selected = id(data.get("recipient_id"));
          if (
            users.stream().noneMatch(person -> same(person.get("id"), selected))
          ) throw new Problem("无权选择此接收人", 403);
          draft.put("target_id", selected);
          draft.put("candidates", List.of());
          if (
            Boolean.TRUE.equals(object(state.get("resolution")).get("organize"))
          ) users
            .stream()
            .filter(person -> same(person.get("id"), selected))
            .findFirst()
            .ifPresent(person ->
              draft.put("organize_team_id", person.get("team_id"))
            );
        }
        if (message.matches(".*(给我|我自己|我的日程).*")) draft.put(
          "target_id",
          u.id()
        );
        if (
          instruction.contains("改期申请") &&
          !Boolean.TRUE.equals(state.get("needs_reference")) &&
          s(draft, "action").equals("update")
        ) {
          var req = object(
            events.get(id(draft.get("id"))).get("reschedule_request")
          );
          if (!"pending".equals(req.get("status"))) throw new Problem(
            "此安排没有待处理的改期申请"
          );
          resolver.mergeEdits(
            state,
            map("start", req.get("start"), "end", req.get("end"))
          );
        }
        return map(
          "owner",
          u.id(),
          "use_model",
          u.admin() && configuration.enabled(u),
          "conversation_id",
          cid,
          "revision",
          revision,
          "state",
          state,
          "text",
          message,
          "history",
          history(cid)
            .stream()
            .skip(Math.max(0, history(cid).size() - 8))
            .toList(),
          "users",
          users,
          "teams",
          teams,
          "rooms",
          rooms,
          "events",
          available.stream().map(this::brief).toList(),
          "baseline",
          draft
        );
      })
    );
    var state = object(context.get("state"));
    var draft = object(state.get("draft"));
    final ModelConfiguration.Connection connection = Boolean.TRUE.equals(
      context.get("use_model")
    )
      ? tx.execute(status -> {
          var u = auth.require(request);
          auth.admin(u);
          return configuration.capture(u);
        })
      : null;
    if (connection != null) {
      var output = model.parse(context, connection);
      try {
        // Validate model proposals even when deterministic clarification takes precedence.
        Object target = output.get("target_id");
        if (
          target != null &&
          (!(target instanceof Number) ||
            objects(context.get("users"))
              .stream()
              .noneMatch(u -> same(u.get("id"), id(target))))
        ) throw new Problem("模型接收人无效");
        for (String k : List.of("start", "end"))
          if (output.get(k) != null && !output.get(k).equals("")) stamp(
            output.get(k)
          );
        if (
          !(output.getOrDefault("warnings", List.of()) instanceof
            List<?> warnings) ||
          warnings.size() > 30
        ) throw new Problem("模型提示无效");
        for (String k : List.of("title", "notes", "location"))
          if (output.containsKey(k)) text(
            output.get(k),
            k,
            k.equals("notes") ? 2000 : 100,
            false
          );
        // Model enriches semantics; it cannot invent missing date, duration or an ambiguous recipient.
        boolean first =
          id(context.get("revision")) == 0 &&
          !s(draft, "action").equals("update");
        String latest = s(context, "text");
        if (first) {
          for (String key : List.of("title", "notes"))
            if (!s(output, key).isBlank()) draft.put(key, output.get(key));
        }
        if (
          s(draft, "location").isBlank() &&
          latest.matches(".*(地点|线上|会议室|办公室).*")
        ) draft.put("location", s(output, "location"));
      } catch (Exception e) {
        throw new Problem(
          "模型接口未返回有效安排，本次没有发送任何日程。",
          502
        );
      }
    }
    return tx.execute(status -> {
      db.lock();
      var u = manager(request);
      if (u.id() != id(context.get("owner"))) throw new Problem(
        "会话已变更",
        403
      );
      authorized(u, state);
      if (connection != null) {
        auth.admin(u);
        if (!configuration.current(connection)) throw new Problem(
          "模型配置已更新，请重新解析",
          409
        );
      }
      String cid = s(context, "conversation_id");
      long revision = id(context.get("revision"));
      var current = db.one(
        "SELECT revision FROM ai_conversations WHERE id=? AND owner_id=?",
        cid,
        u.id()
      );
      if (
        current != null && id(current.get("revision")) != revision
      ) throw new Problem("对话已被另一请求更新，请刷新后继续", 409);
      if (current == null && revision != 0) throw new Problem(
        "对话已失效",
        409
      );
      var questions = resolver.questions(state);
      draft.putAll(
        map(
          "questions",
          questions,
          "warnings",
          new ArrayList<>(questions),
          "complete",
          questions.isEmpty(),
          "engine",
          connection != null ? "model" : "rules",
          "reference_candidates",
          state.getOrDefault("reference_candidates", List.of())
        )
      );
      if (questions.isEmpty()) {
        var check = new LinkedHashMap<>(draft);
        if (s(draft, "action").equals("update")) {
          check.put("exclude_id", draft.get("id"));
          check.put("draft_id", draft.get("original_draft_id"));
        }
        var planning = schedules.check(u, check);
        draft.put("planning", planning);
      } else draft.remove("planning");
      var recipient =
        draft.get("target_id") == null
          ? null
          : db.one("SELECT name FROM users WHERE id=?", draft.get("target_id"));
      draft.put(
        "evidence",
        map(
          "recipient",
          recipient == null
            ? "接收人尚未确定"
            : "已核对接收人：" + recipient.get("name"),
          "time",
          draft.get("start"),
          "location",
          draft.get("location"),
          "context",
          s(draft, "action").equals("update")
            ? "修改已有安排 #" + draft.get("id") + "，时长及其他条件已保留"
            : "结合授权员工目录、会议室与现有安排"
        )
      );
      long did = db.insert(
        "INSERT INTO ai_drafts(owner_id,result,complete,engine) VALUES(?,?::jsonb,?,?)",
        u.id(),
        json.write(draft),
        questions.isEmpty() ? 1 : 0,
        draft.get("engine")
      );
      draft.put("draft_id", did);
      String assistant = questions.isEmpty()
        ? s(draft, "action").equals("update")
          ? "改期草稿已生成，其他信息已保留。请核对并确认修改原日程。"
          : "草稿已完整，已检查当前安排并比较候选方案。请核对后确认发送。"
        : "已保留已知条件。" + String.join(" ", questions);
      if (current == null) db.update(
        "INSERT INTO ai_conversations(id,owner_id,state,revision) VALUES(?,?,?::jsonb,1)",
        cid,
        u.id(),
        json.write(state)
      );
      else db.update(
        "UPDATE ai_conversations SET state=?::jsonb,revision=revision+1,updated_at=clock_timestamp() WHERE id=?",
        json.write(state),
        cid
      );
      db.update(
        "INSERT INTO ai_turns(conversation_id,role,content) VALUES(?,'user',?),(?,'assistant',?)",
        cid,
        context.get("text"),
        cid,
        assistant
      );
      return map(
        "conversation_id",
        cid,
        "revision",
        revision + 1,
        "draft",
        draft,
        "assistant_message",
        assistant
      );
    });
  }
}
