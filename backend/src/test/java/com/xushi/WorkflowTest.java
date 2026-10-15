package com.xushi;

import static com.xushi.common.Fields.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.xushi.ai.ModelConfiguration;
import com.xushi.ai.ModelGateway;
import com.xushi.auth.Passwords;
import com.xushi.common.*;
import com.xushi.data.Database;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(
  webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
  properties = {
    "spring.datasource.url=jdbc:postgresql://127.0.0.1:55432/schedule_test",
    "schedule.import-source=",
    "schedule.ai.key=",
    "schedule.ai.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
  }
)
class WorkflowTest {

  @Autowired
  Database db;

  @Autowired
  Json json;

  @Autowired
  Passwords passwords;

  @LocalServerPort
  int port;

  @MockitoBean
  ModelGateway model;

  Client admin, zhao, chen;
  long team, zid, cid;
  String password = "OnlyForIntegrationTests!2026";
  Map<String, Object> event;

  class Client {

    final HttpClient http = HttpClient.newBuilder()
      .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
      .build();

    Map<String, Object> call(
      String path,
      String method,
      Object body,
      int expected
    ) {
      try {
        var b = HttpRequest.newBuilder(
          URI.create("http://127.0.0.1:" + port + "/api" + path)
        );
        if (!method.equals("GET")) b.header("Content-Type", "application/json");
        b.method(
          method,
          method.equals("GET")
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(json.write(body))
        );
        var response = http.send(
          b.build(),
          HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(
          expected,
          response.statusCode(),
          path + " " + response.body()
        );
        return json.read(response.body());
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }

    Map<String, Object> state() {
      return call("/state", "GET", null, 200);
    }

    Map<String, Object> current(long eid) {
      return objects(state().get("events"))
        .stream()
        .filter(e -> id(e.get("id")) == eid)
        .findFirst()
        .orElseThrow();
    }

    Map<String, Object> action(
      long eid,
      String op,
      Map<String, Object> d,
      int code
    ) {
      var data = map("expected_updated_at", current(eid).get("updated_at"));
      data.putAll(d);
      return call(
        "/events/" + eid + (op.isEmpty() ? "" : "/" + op),
        "POST",
        data,
        code
      );
    }
  }

  @BeforeEach
  void setup() {
    assertEquals("schedule_test", db.name(), "Never reset a real database");
    db.update(
      "TRUNCATE users,teams,sessions,admin_invites,events,event_details,event_history,notifications,ai_drafts,event_requests,audit,rooms,migration_runs RESTART IDENTITY CASCADE"
    );
    db.update(
      "INSERT INTO rooms(name,capacity) SELECT i||'号会议室',8+i*2 FROM generate_series(1,5) i"
    );
    db.update(
      "UPDATE scheduling_policy SET settings=\'{\"weekdays\":[1,2,3,4,5],\"work_start\":\"09:00\",\"work_end\":\"18:00\",\"step_minutes\":30,\"horizon_days\":7,\"holidays\":[],\"workdays\":[]}\'::jsonb WHERE id=1"
    );
    db.update("INSERT INTO ai_model_settings(id) VALUES(1)");
    admin = new Client();
    zhao = new Client();
    chen = new Client();
    admin.call(
      "/auth/setup",
      "POST",
      map("username", "liuzong", "name", "刘总", "password", password),
      201
    );
    admin.call(
      "/auth/login",
      "POST",
      map("username", "liuzong", "password", password),
      200
    );
    team = id(
      admin
        .call(
          "/teams",
          "POST",
          map("name", "三组", "description", "研发组"),
          201
        )
        .get("id")
    );
    zid = id(
      admin
        .call(
          "/users",
          "POST",
          map(
            "username",
            "zhao",
            "name",
            "赵明",
            "password",
            password,
            "team_id",
            team
          ),
          201
        )
        .get("id")
    );
    cid = id(
      admin
        .call(
          "/users",
          "POST",
          map(
            "username",
            "chen",
            "name",
            "陈晓",
            "password",
            password,
            "team_id",
            team
          ),
          201
        )
        .get("id")
    );
    zhao.call(
      "/auth/login",
      "POST",
      map("username", "zhao", "password", password),
      200
    );
    chen.call(
      "/auth/login",
      "POST",
      map("username", "chen", "password", password),
      200
    );
    String day = LocalDate.now(ZONE).plusDays(1).toString();
    event = map(
      "title",
      "团队工作会议",
      "target_id",
      zid,
      "start",
      day + "T15:00:00+08:00",
      "end",
      day + "T16:00:00+08:00",
      "location",
      "5号会议室",
      "notes",
      "小赵组织三组全员参会",
      "source",
      "ai"
    );
  }

  long create(Map<String, Object> changes) {
    var d = new LinkedHashMap<>(event);
    d.putAll(changes);
    return id(
      object(admin.call("/events", "POST", d, 201).get("event")).get("id")
    );
  }

  @Test
  void singleRecipientAndPermission() {
    var draft = object(
      admin
        .call(
          "/ai/parse",
          "POST",
          map(
            "text",
            "让三组的小赵明天下午三点到5号会议室开会，带上他们组的所有人"
          ),
          200
        )
        .get("draft")
    );
    assertEquals(zid, id(draft.get("target_id")));
    assertEquals(event.get("start"), draft.get("start"));
    assertEquals(team, id(draft.get("organize_team_id")));
    assertEquals(0, db.count("SELECT count(*) FROM events"));
    zhao.call("/ai/parse", "POST", map("text", "给刘总安排会议"), 403);
    long eid = create(
      map("draft_id", draft.get("draft_id"), "organize_team_id", team)
    );
    assertEquals(1, objects(zhao.state().get("events")).size());
    assertTrue(objects(chen.state().get("events")).isEmpty());
    assertTrue(objects(chen.state().get("notifications")).isEmpty());
    chen.call(
      "/events/" + eid + "/respond",
      "POST",
      map(
        "status",
        "accepted",
        "expected_updated_at",
        admin.current(eid).get("updated_at")
      ),
      403
    );
    zhao.call("/events/" + eid, "PUT", event, 403);
    assertEquals(
      eid,
      id(
        db
          .one(
            "SELECT sent_event_id FROM ai_drafts WHERE id=?",
            draft.get("draft_id")
          )
          .get("sent_event_id")
      )
    );
  }

  @Test
  void conflictIdempotencyAndVersion() {
    long eid = create(map("request_id", "request-a"));
    admin.call(
      "/events",
      "POST",
      new LinkedHashMap<>() {
        {
          putAll(event);
          put("request_id", "request-a");
        }
      },
      200
    );
    assertEquals(1, db.count("SELECT count(*) FROM events"));
    admin.call("/events", "POST", event, 409);
    var check = chen.call(
      "/events/check",
      "POST",
      new LinkedHashMap<>() {
        {
          putAll(event);
          put("target_id", cid);
        }
      },
      200
    );
    var conflicts = objects(check.get("conflicts"));
    assertTrue(
      conflicts
        .stream()
        .allMatch(
          c ->
            c.get("event_id") == null && s(c, "title").equals("会议室已被占用")
        )
    );
    assertEquals(3, objects(check.get("suggestions")).size());
    var edit = new LinkedHashMap<>(event);
    edit.put("expected_updated_at", "old-version");
    admin.call("/events/" + eid, "PUT", edit, 409);
    var previous = admin.current(eid);
    zhao.action(eid, "respond", map("status", "accepted"), 200);
    edit.put("expected_updated_at", previous.get("updated_at"));
    admin.call("/events/" + eid, "PUT", edit, 409);
    assertEquals("accepted", admin.current(eid).get("status"));
  }

  @Test
  void concurrentBookingCannotBothPass() throws Exception {
    var pool = Executors.newFixedThreadPool(2);
    try {
      var latch = new CountDownLatch(1);
      List<Future<Integer>> results = new ArrayList<>();
      for (int i = 0; i < 2; i++) results.add(
        pool.submit(() -> {
          latch.await();
          var response = admin.http.send(
            HttpRequest.newBuilder(
              URI.create("http://127.0.0.1:" + port + "/api/events")
            )
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(json.write(event)))
              .build(),
            HttpResponse.BodyHandlers.ofString()
          );
          return response.statusCode();
        })
      );
      latch.countDown();
      List<Integer> codes = new ArrayList<>();
      for (var result : results) codes.add(result.get(10, TimeUnit.SECONDS));
      Collections.sort(codes);
      assertEquals(List.of(201, 409), codes);
      assertEquals(1, db.count("SELECT count(*) FROM events"));
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void preparationExecutionAndMinorEdits() {
    long eid = create(
      map(
        "organize_team_id",
        team,
        "checklist",
        List.of(map("label", "提交议题", "done", true))
      )
    );
    var checks = objects(admin.current(eid).get("checklist"));
    assertEquals(3, checks.size());
    assertTrue(
      checks.stream().noneMatch(c -> Boolean.TRUE.equals(c.get("done")))
    );
    zhao.action(eid, "respond", map("status", "accepted"), 200);
    for (int i = 0; i < 3; i++) zhao.action(
      eid,
      "checklist",
      map("index", i, "done", true),
      200
    );
    zhao.action(eid, "progress", map("phase", "in_progress"), 200);
    var current = admin.current(eid);
    var edit = new LinkedHashMap<>(current);
    edit.putAll(
      map(
        "title",
        "工作会议补充",
        "expected_updated_at",
        current.get("updated_at")
      )
    );
    admin.call("/events/" + eid, "PUT", edit, 200);
    assertEquals("in_progress", admin.current(eid).get("phase"));
    assertTrue(
      objects(admin.current(eid).get("checklist"))
        .stream()
        .allMatch(c -> Boolean.TRUE.equals(c.get("done")))
    );
    edit = new LinkedHashMap<>(admin.current(eid));
    edit.putAll(
      map(
        "start",
        s(event, "start").replace("15:00", "17:00"),
        "end",
        s(event, "end").replace("16:00", "18:00"),
        "expected_updated_at",
        edit.get("updated_at")
      )
    );
    admin.call("/events/" + eid, "PUT", edit, 409);
    zhao.action(
      eid,
      "progress",
      map("phase", "completed", "report", "会议完成，已分配后续工作"),
      200
    );
    assertEquals("completed", admin.current(eid).get("phase"));
    assertTrue(
      db.count("SELECT count(*) FROM event_history WHERE event_id=?", eid) > 5
    );
  }

  @Test
  void rescheduleApprovalAndReconfirmation() {
    long eid = create(map());
    zhao.action(eid, "respond", map("status", "accepted"), 200);
    var newTime = map(
      "start",
      s(event, "start").replace("15:00", "17:00"),
      "end",
      s(event, "end").replace("16:00", "18:00"),
      "reason",
      "需要外出拜访客户"
    );
    zhao.action(eid, "reschedule", newTime, 200);
    zhao.action(eid, "progress", map("phase", "in_progress"), 409);
    admin.action(eid, "reschedule", map("action", "approve"), 200);
    assertEquals("pending", admin.current(eid).get("status"));
    assertEquals(
      "resolved",
      object(admin.current(eid).get("reschedule_request")).get("status")
    );
    zhao.action(eid, "respond", map("status", "accepted"), 200);
    zhao.action(
      eid,
      "reschedule",
      map(
        "start",
        s(event, "start").replace("15:00", "13:00"),
        "end",
        s(event, "end").replace("16:00", "14:00"),
        "reason",
        "另选时间"
      ),
      200
    );
    zhao.action(eid, "reschedule", map("action", "withdraw"), 200);
    assertEquals(
      "withdrawn",
      object(admin.current(eid).get("reschedule_request")).get("status")
    );
  }

  @Test
  void registrationInvitationAndSessionRevocation() {
    var member = new Client();
    member.call(
      "/auth/register",
      "POST",
      map(
        "username",
        "newmember",
        "name",
        "新同事",
        "password",
        password,
        "confirm",
        password,
        "role",
        "member",
        "team_id",
        team
      ),
      201
    );
    member.call(
      "/auth/login",
      "POST",
      map("username", "newmember", "password", password),
      200
    );
    assertNull(object(member.state().get("user")).get("team_id"));
    member.call("/users", "POST", map(), 403);
    String code = s(
      admin.call("/admin-invitations", "POST", map(), 201),
      "code"
    );
    var other = new Client();
    other.call(
      "/auth/register",
      "POST",
      map(
        "username",
        "admin2",
        "name",
        "另一管理员",
        "password",
        password,
        "confirm",
        password,
        "role",
        "admin",
        "invite_code",
        code
      ),
      201
    );
    other.call(
      "/auth/register",
      "POST",
      map(
        "username",
        "admin3",
        "name",
        "重复邀请",
        "password",
        password,
        "confirm",
        password,
        "role",
        "admin",
        "invite_code",
        code
      ),
      403
    );
    admin.call(
      "/users/" + zid,
      "PUT",
      map("password", "NewPasswordForTest2026!"),
      200
    );
    zhao.call("/state", "GET", null, 401);
  }

  @Test
  void rollbackAndTeamAuthorization() {
    admin.call(
      "/events",
      "POST",
      new LinkedHashMap<>() {
        {
          putAll(event);
          put("draft_id", 9999);
        }
      },
      409
    );
    assertEquals(0, db.count("SELECT count(*) FROM events"));
    assertEquals(0, db.count("SELECT count(*) FROM notifications"));
    admin.call("/teams/" + team, "PUT", map("leader_id", zid), 200);
    assertTrue(
      Boolean.TRUE.equals(object(zhao.state().get("user")).get("is_leader"))
    );
    var ownTeam = new LinkedHashMap<>(event);
    ownTeam.put("target_id", cid);
    zhao.call("/events", "POST", ownTeam, 201);
    admin.call("/teams/" + team, "PUT", map("leader_id", null), 200);
    zhao.call("/events", "POST", ownTeam, 403);
    admin.call("/teams/" + team, "DELETE", map(), 400);
    admin.call("/users/1", "PUT", map("active", 0), 400);
  }

  @Test
  void roomForeignKeyRenameAndStatusScope() {
    long eid = create(map());
    assertEquals(
      5,
      id(db.one("SELECT room_id FROM events WHERE id=?", eid).get("room_id"))
    );
    admin.call(
      "/rooms/5",
      "PUT",
      map("name", "研发会议室", "capacity", 20),
      200
    );
    assertEquals("研发会议室", admin.current(eid).get("location"));
    assertEquals(5, id(admin.current(eid).get("room_id")));
    chen.call("/events/" + eid + "/history", "GET", null, 403);
    var metrics = admin.call("/metrics", "GET", null, 200);
    assertEquals(1, id(metrics.get("event_count")));
    chen.call("/metrics", "GET", null, 403);
  }

  @Test
  void modelCannotInventRecipientOrSendAutomatically() {
    admin.call(
      "/admin/model-config",
      "PUT",
      map(
        "revision",
        0,
        "enabled",
        true,
        "base_url",
        "https://model.example.test/v1",
        "model",
        "fixture-model",
        "api_key",
        "test-fixture-key"
      ),
      200
    );
    when(
      model.parse(anyMap(), any(ModelConfiguration.Connection.class))
    ).thenReturn(
      map(
        "title",
        "会议",
        "target_id",
        99999,
        "start",
        event.get("start"),
        "end",
        event.get("end"),
        "location",
        "5号会议室",
        "notes",
        "",
        "warnings",
        List.of()
      )
    );
    admin.call(
      "/ai/parse",
      "POST",
      map("text", "给小赵安排明天下午三点会议"),
      502
    );
    assertEquals(0, db.count("SELECT count(*) FROM ai_drafts"));
    assertEquals(0, db.count("SELECT count(*) FROM events"));
    when(
      model.parse(anyMap(), any(ModelConfiguration.Connection.class))
    ).thenReturn(
      map(
        "title",
        "会议",
        "target_id",
        zid,
        "start",
        null,
        "end",
        null,
        "location",
        "5号会议室",
        "notes",
        "",
        "warnings",
        List.of()
      )
    );
    var draft = object(
      admin
        .call("/ai/parse", "POST", map("text", "给小赵安排会议"), 200)
        .get("draft")
    );
    assertEquals(false, draft.get("complete"));
    assertNull(draft.get("start"));
    assertTrue(((List<?>) draft.get("questions")).size() > 0);
    assertEquals(0, db.count("SELECT count(*) FROM events"));
  }

  Map<String, Object> follow(
    Map<String, Object> reply,
    String text,
    Map<String, Object> extra
  ) {
    var payload = map(
      "text",
      text,
      "conversation_id",
      reply.get("conversation_id"),
      "revision",
      reply.get("revision")
    );
    payload.putAll(extra);
    return admin.call("/ai/parse", "POST", payload, 200);
  }

  Map<String, Object> aiEdit(Map<String, Object> draft) {
    var payload = new LinkedHashMap<>(draft);
    payload.put("expected_updated_at", draft.get("updated_at"));
    payload.put("ai_change_draft_id", draft.get("draft_id"));
    payload.put("draft_id", draft.get("original_draft_id"));
    return payload;
  }

  @Test
  void clarificationKeepsFieldsAndConversationIsPrivate() {
    var reply = admin.call(
      "/ai/parse",
      "POST",
      map("text", "让三组的小赵下午三点到5号会议室开会，带上全组"),
      200
    );
    var draft = object(reply.get("draft"));
    assertNull(draft.get("start"));
    assertNull(draft.get("end"));
    assertEquals(false, draft.get("complete"));
    assertTrue(
      ((List<?>) draft.get("questions"))
        .stream()
        .anyMatch(q -> q.toString().contains("哪一天"))
    );
    reply = follow(reply, "明天", map());
    draft = object(reply.get("draft"));
    assertEquals(event.get("start"), draft.get("start"));
    assertNull(draft.get("end"));
    reply = follow(
      reply,
      "持续一个小时",
      map(
        "draft_updates",
        map(
          "title",
          "研发复盘",
          "buffer_before",
          15,
          "notes",
          "携带项目资料",
          "checklist",
          List.of(map("label", "提交议题", "done", false))
        )
      )
    );
    draft = object(reply.get("draft"));
    assertEquals(true, draft.get("complete"));
    assertEquals(event.get("end"), draft.get("end"));
    reply = follow(reply, "换到4号会议室", map());
    draft = object(reply.get("draft"));
    assertEquals("研发复盘", draft.get("title"));
    assertEquals("携带项目资料", draft.get("notes"));
    assertEquals("4号会议室", draft.get("location"));
    assertEquals(15, id(draft.get("buffer_before")));
    assertEquals(team, id(draft.get("organize_team_id")));
    assertEquals(event.get("end"), draft.get("end"));
    assertNotNull(draft.get("planning"));
    assertEquals(0, db.count("SELECT count(*) FROM events"));
    admin.call("/teams/" + team, "PUT", map("leader_id", zid), 200);
    zhao.call(
      "/ai/conversations/" + reply.get("conversation_id"),
      "GET",
      null,
      404
    );
    var restored = admin.call(
      "/ai/conversations/" + reply.get("conversation_id"),
      "GET",
      null,
      200
    );
    assertEquals("研发复盘", object(restored.get("draft")).get("title"));
    admin.call(
      "/ai/parse",
      "POST",
      map(
        "text",
        "明天",
        "conversation_id",
        reply.get("conversation_id"),
        "revision",
        0
      ),
      409
    );
  }

  @Test
  void ambiguousRecipientAndPeriodAreNeverGuessed() {
    long second = id(
      admin
        .call(
          "/users",
          "POST",
          map(
            "username",
            "zhao2",
            "name",
            "赵强",
            "password",
            password,
            "team_id",
            team
          ),
          201
        )
        .get("id")
    );
    var reply = admin.call(
      "/ai/parse",
      "POST",
      map("text", "让三组的小赵明天三点到5号会议室开会，持续半小时"),
      200
    );
    var d = object(reply.get("draft"));
    assertNull(d.get("target_id"));
    assertNull(d.get("start"));
    assertEquals(2, objects(d.get("candidates")).size());
    reply = follow(reply, "下午", map("recipient_id", second));
    d = object(reply.get("draft"));
    assertEquals(true, d.get("complete"));
    assertEquals(second, id(d.get("target_id")));
    assertEquals(event.get("start"), d.get("start"));
    assertEquals(time(event.get("start")).plusMinutes(30), time(d.get("end")));
    reply = follow(reply, "改到四点", map());
    d = object(reply.get("draft"));
    assertEquals(16, time(d.get("start")).getHour());
    assertEquals(
      30,
      Duration.between(time(d.get("start")), time(d.get("end"))).toMinutes()
    );
    follow(reply, "持续90分钟", map());
    assertEquals(0, db.count("SELECT count(*) FROM notifications"));
  }

  @Test
  void contextualRescheduleUpdatesOnlyOriginalAfterConfirmation() {
    var reply = admin.call(
      "/ai/parse",
      "POST",
      map(
        "text",
        "让三组的小赵明天下午三点到5号会议室开会，持续1小时，带上全组"
      ),
      200
    );
    var d = object(reply.get("draft"));
    long eid = create(
      map(
        "draft_id",
        d.get("draft_id"),
        "organize_team_id",
        team,
        "buffer_before",
        10
      )
    );
    var original = admin.current(eid);
    reply = follow(reply, "把刚才那场会推迟半小时", map());
    d = object(reply.get("draft"));
    assertEquals("update", d.get("action"));
    assertEquals(eid, id(d.get("id")));
    assertEquals(
      time(event.get("start")).plusMinutes(30),
      time(d.get("start"))
    );
    assertEquals(10, id(d.get("buffer_before")));
    assertEquals(original.get("start"), admin.current(eid).get("start"));
    admin.call("/events", "POST", d, 409);
    assertEquals(1, db.count("SELECT count(*) FROM events"));
    admin.call("/events/" + eid, "PUT", aiEdit(d), 200);
    var updated = admin.current(eid);
    assertEquals(d.get("start"), updated.get("start"));
    assertEquals(original.get("draft_id"), updated.get("draft_id"));
    assertEquals(1, db.count("SELECT count(*) FROM events"));
    assertEquals(0, objects(chen.state().get("notifications")).size());
    admin.call("/events/" + eid, "PUT", aiEdit(d), 409);
    reply = follow(reply, "再提前半小时", map());
    assertEquals(
      original.get("start"),
      object(reply.get("draft")).get("start")
    );
    var change = new LinkedHashMap<>(admin.current(eid));
    change.putAll(
      map(
        "title",
        "另一人更新",
        "expected_updated_at",
        change.get("updated_at")
      )
    );
    admin.call("/events/" + eid, "PUT", change, 200);
    admin.call(
      "/events/" + eid,
      "PUT",
      aiEdit(object(reply.get("draft"))),
      409
    );
  }

  @Test
  void ambiguousReferenceAndPendingRequestNeedAuthorizedSelection() {
    long first = create(map());
    long second = create(map("target_id", cid, "location", "4号会议室"));
    var reply = admin.call(
      "/ai/parse",
      "POST",
      map("text", "把那场会推迟半小时"),
      200
    );
    var d = object(reply.get("draft"));
    assertEquals(2, objects(d.get("reference_candidates")).size());
    assertEquals(false, d.get("complete"));
    reply = follow(reply, "选择原安排", map("reference_event_id", second));
    d = object(reply.get("draft"));
    assertEquals(second, id(d.get("id")));
    assertEquals(
      time(event.get("start")).plusMinutes(30),
      time(d.get("start"))
    );
    assertEquals(event.get("start"), admin.current(second).get("start"));
    zhao.action(
      first,
      "reschedule",
      map(
        "start",
        time(event.get("start")).plusHours(2).toString(),
        "end",
        time(event.get("end")).plusHours(2).toString(),
        "reason",
        "客户拜访"
      ),
      200
    );
    var pending = admin.call(
      "/ai/parse",
      "POST",
      map("text", "按小赵的改期申请调整"),
      200
    );
    assertEquals(first, id(object(pending.get("draft")).get("id")));
    assertEquals(
      time(event.get("start")).plusHours(2),
      time(object(pending.get("draft")).get("start"))
    );
    admin.call("/teams/" + team, "PUT", map("leader_id", zid), 200);
    zhao.call(
      "/ai/parse",
      "POST",
      map("text", "把那场会推迟半小时", "reference_event_id", second),
      403
    );
  }

  @Test
  void rankedPlansUseCapacityBusyBuffersAndPolicyWithoutLeakingDetails() {
    var policy = map(
      "weekdays",
      List.of(1, 2, 3, 4, 5, 6, 7),
      "work_start",
      "09:00",
      "work_end",
      "18:00",
      "step_minutes",
      30,
      "horizon_days",
      7,
      "holidays",
      List.of(),
      "workdays",
      List.of()
    );
    admin.call("/scheduling-policy", "PUT", policy, 200);
    chen.call("/scheduling-policy", "PUT", policy, 403);
    create(
      map(
        "target_id",
        cid,
        "title",
        "团队成员私密客户拜访",
        "location",
        "5号会议室",
        "travel_minutes",
        30
      )
    );
    admin.call("/rooms/1", "PUT", map("name", "1号会议室", "capacity", 1), 200);
    var input = new LinkedHashMap<>(event);
    input.putAll(
      map(
        "location",
        "1号会议室",
        "organize_team_id",
        team,
        "buffer_before",
        15,
        "buffer_after",
        10,
        "travel_minutes",
        30
      )
    );
    var check = admin.call("/events/check", "POST", input, 200);
    var plans = objects(check.get("suggestions"));
    assertEquals(3, plans.size());
    assertTrue(
      plans.stream().noneMatch(p -> s(p, "location").equals("1号会议室"))
    );
    assertEquals(0, id(plans.get(0).get("team_busy_count")));
    assertFalse(json.write(plans).contains("私密客户"));
    assertTrue(
      plans
        .stream()
        .allMatch(
          p ->
            !objects(p.get("reasons")).isEmpty() ||
            p.get("reasons") instanceof List<?>
        )
    );
    assertTrue(id(plans.get(0).get("score")) <= id(plans.get(1).get("score")));
    var selected = new LinkedHashMap<>(input);
    selected.putAll(plans.get(0));
    assertTrue(
      objects(
        admin.call("/events/check", "POST", selected, 200).get("conflicts")
      ).isEmpty()
    );
    policy.put("holidays", List.of(s(event, "start").substring(0, 10)));
    admin.call("/scheduling-policy", "PUT", policy, 200);
    var next = objects(
      admin.call("/events/check", "POST", input, 200).get("suggestions")
    );
    assertTrue(
      next
        .stream()
        .noneMatch(p ->
          s(p, "start").startsWith(s(event, "start").substring(0, 10))
        )
    );
    policy.put("workdays", policy.get("holidays"));
    admin.call("/scheduling-policy", "PUT", policy, 400);
  }

  Map<String, Object> modelSettings(
    long revision,
    boolean enabled,
    String key
  ) {
    return map(
      "revision",
      revision,
      "enabled",
      enabled,
      "base_url",
      "https://fixture-model.example.test/v1",
      "model",
      "fixture-model",
      "api_key",
      key
    );
  }

  @Test
  void modelSettingsAreEncryptedRedactedVersionedAndAdminOnly() {
    chen.call("/admin/model-config", "GET", null, 403);
    chen.call(
      "/admin/model-config",
      "PUT",
      modelSettings(0, true, "fixture-secret"),
      403
    );
    chen.call("/admin/model-config", "DELETE", map("revision", 0), 403);
    chen.call("/admin/model-config/test", "POST", map("revision", 0), 403);
    var saved = object(
      admin
        .call(
          "/admin/model-config",
          "PUT",
          modelSettings(0, true, "fixture-secret"),
          200
        )
        .get("config")
    );
    assertEquals(true, saved.get("encrypted"));
    assertEquals(true, saved.get("has_key"));
    assertFalse(saved.containsKey("api_key"));
    String ciphertext = s(
      db.one("SELECT encrypted_payload FROM ai_model_settings WHERE id=1"),
      "encrypted_payload"
    );
    assertFalse(ciphertext.contains("fixture-secret"));
    assertFalse(ciphertext.contains("fixture-model"));
    assertFalse(ciphertext.contains("example.test"));
    assertFalse(
      json
        .write(admin.call("/admin/model-config", "GET", null, 200))
        .contains("fixture-secret")
    );
    admin.call(
      "/admin/model-config",
      "PUT",
      modelSettings(0, true, "replacement-secret"),
      409
    );
    var second = object(
      admin
        .call("/admin/model-config", "PUT", modelSettings(1, false, ""), 200)
        .get("config")
    );
    assertEquals(false, second.get("enabled"));
    assertEquals(true, second.get("has_key"));
    assertNotEquals(
      ciphertext,
      s(
        db.one("SELECT encrypted_payload FROM ai_model_settings WHERE id=1"),
        "encrypted_payload"
      )
    );
    assertEquals("rules", admin.state().get("ai_mode"));
    var invalid = modelSettings(2, true, "new-secret");
    invalid.put("base_url", "http://remote.example.test/v1");
    admin.call("/admin/model-config", "PUT", invalid, 400);
    invalid.put("base_url", "https://user:secret@example.test/v1");
    admin.call("/admin/model-config", "PUT", invalid, 400);
    var enabled = object(
      admin
        .call("/admin/model-config", "PUT", modelSettings(2, true, ""), 200)
        .get("config")
    );
    assertEquals(3, id(enabled.get("revision")));
    assertEquals("model", admin.state().get("ai_mode"));
    admin.call("/teams/" + team, "PUT", map("leader_id", zid), 200);
    assertEquals("rules", zhao.state().get("ai_mode"));
    zhao.call("/admin/model-config", "GET", null, 403);
    zhao.call("/admin/model-config/test", "POST", map("revision", 3), 403);
    zhao.call(
      "/ai/parse",
      "POST",
      map("text", "让陈晓明天下午两点到5号会议室开会，持续1小时"),
      200
    );
    verify(model, never()).parse(
      anyMap(),
      any(ModelConfiguration.Connection.class)
    );
    admin.call("/admin/model-config/test", "POST", map("revision", 3), 200);
    verify(model).test(any(ModelConfiguration.Connection.class));
    admin.call("/admin/model-config", "DELETE", map("revision", 3), 200);
    assertNull(
      db
        .one("SELECT encrypted_payload FROM ai_model_settings WHERE id=1")
        .get("encrypted_payload")
    );
    assertEquals("rules", admin.state().get("ai_mode"));
    admin.call("/admin/model-config/test", "POST", map("revision", 4), 400);
    admin.call("/admin/model-config", "PUT", modelSettings(4, true, ""), 400);
    assertFalse(
      json
        .write(
          db.rows("SELECT detail FROM audit WHERE action LIKE 'ai.config.%'")
        )
        .contains("fixture-secret")
    );
  }

  @Test
  void savedConfigurationIsUsedOnlyForAdminAndRotationDiscardsOldResponse() {
    admin.call(
      "/admin/model-config",
      "PUT",
      modelSettings(0, true, "fixture-secret"),
      200
    );
    when(
      model.parse(anyMap(), any(ModelConfiguration.Connection.class))
    ).thenAnswer(invocation -> {
      var c = invocation.getArgument(1, ModelConfiguration.Connection.class);
      assertEquals("fixture-secret", c.key());
      assertEquals("fixture-model", c.model());
      assertEquals("https://fixture-model.example.test/v1", c.baseUrl());
      admin.call(
        "/admin/model-config",
        "PUT",
        modelSettings(1, false, ""),
        200
      );
      return map(
        "title",
        "测试工作会议",
        "target_id",
        zid,
        "start",
        null,
        "end",
        null,
        "location",
        "5号会议室",
        "notes",
        "",
        "warnings",
        List.of()
      );
    });
    admin.call(
      "/ai/parse",
      "POST",
      map("text", "让小赵明天下午三点在5号会议室开会，持续1小时"),
      409
    );
    assertEquals(0, db.count("SELECT count(*) FROM ai_drafts"));
    assertEquals(0, db.count("SELECT count(*) FROM events"));
    var reply = admin.call(
      "/ai/parse",
      "POST",
      map("text", "让小赵明天下午三点在5号会议室开会，持续1小时"),
      200
    );
    assertEquals("rules", object(reply.get("draft")).get("engine"));
    verify(model, times(1)).parse(
      anyMap(),
      any(ModelConfiguration.Connection.class)
    );
  }
}
