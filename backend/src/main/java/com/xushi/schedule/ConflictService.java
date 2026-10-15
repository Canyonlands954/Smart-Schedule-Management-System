package com.xushi.schedule;

import static com.xushi.common.Fields.*;

import com.xushi.auth.*;
import com.xushi.common.*;
import com.xushi.data.Database;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class ConflictService {

  private final Database db;
  private final EventRepository events;
  private final Permissions permissions;
  private final SchedulingPolicy policy;

  public ConflictService(
    Database db,
    EventRepository events,
    Permissions permissions,
    SchedulingPolicy policy
  ) {
    this.db = db;
    this.events = events;
    this.permissions = permissions;
    this.policy = policy;
  }

  private OffsetDateTime before(Map<String, Object> e) {
    return time(e.get("start")).minusMinutes(n(e, "buffer_before"));
  }

  private OffsetDateTime after(Map<String, Object> e) {
    return time(e.get("end")).plusMinutes(n(e, "buffer_after"));
  }

  public String person(Map<String, Object> item, Map<String, Object> other) {
    long travel =
      !s(item, "location").isEmpty() &&
      !s(other, "location").isEmpty() &&
      !s(item, "location").equals(s(other, "location"))
        ? Math.max(n(item, "travel_minutes"), n(other, "travel_minutes"))
        : 0;
    if (
      before(item).isBefore(after(other).plusMinutes(travel)) &&
      after(item).plusMinutes(travel).isAfter(before(other))
    ) return time(item.get("start")).isBefore(time(other.get("end"))) &&
      time(item.get("end")).isAfter(time(other.get("start")))
      ? "人员时间重叠"
      : "准备、收尾或跨地点通勤时间不足";
    return null;
  }

  public List<Map<String, Object>> find(
    Map<String, Object> item,
    long exclude
  ) {
    var room = db.one("SELECT id FROM rooms WHERE name=?", s(item, "location"));
    var rows = events.list(
      " WHERE e.id<>? AND e.status IN ('pending','accepted') AND e.phase<>'completed' AND e.start_at<? AND e.end_at>? AND (e.target_id=? OR (e.location=? AND ?))",
      exclude,
      time(item.get("end")).plusDays(1),
      time(item.get("start")).minusDays(1),
      item.get("target_id"),
      s(item, "location"),
      room != null
    );
    List<Map<String, Object>> result = new ArrayList<>();
    for (var other : rows) {
      String kind = null,
        reason = null;
      if (same(item.get("target_id"), other.get("target_id"))) {
        reason = person(item, other);
        if (reason != null) kind = "person";
      }
      if (
        kind == null &&
        room != null &&
        s(item, "location").equals(s(other, "location")) &&
        before(item).isBefore(after(other)) &&
        after(item).isAfter(before(other))
      ) {
        kind = "room";
        reason = "会议室准备、使用或收尾时间重叠";
      }
      if (kind != null) result.add(
        map(
          "event_id",
          other.get("id"),
          "kind",
          kind,
          "reason",
          reason,
          "title",
          other.get("title"),
          "start",
          other.get("start"),
          "end",
          other.get("end"),
          "target_name",
          other.get("target_name"),
          "location",
          other.get("location"),
          "priority",
          other.get("priority")
        )
      );
    }
    return result;
  }

  public List<Map<String, Object>> sanitized(
    User u,
    List<Map<String, Object>> items
  ) {
    return items
      .stream()
      .map(c -> {
        if (permissions.view(u, events.get(id(c.get("event_id"))))) return c;
        var copy = new LinkedHashMap<>(c);
        copy.putAll(
          map(
            "event_id",
            null,
            "title",
            s(c, "kind").equals("room")
              ? "会议室已被占用"
              : "已有安排占用该时段",
            "target_name",
            "",
            "location",
            "",
            "priority",
            "normal"
          )
        );
        return (Map<String, Object>) copy;
      })
      .toList();
  }

  public Map<String, Object> advisory(User u, Map<String, Object> item) {
    List<String> warnings = new ArrayList<>();
    OffsetDateTime start = time(item.get("start")),
      end = time(item.get("end"));
    var settings = policy.get();
    if (!policy.fits(settings, item)) warnings.add(
      "安排不符合设定的工作日或工作时段" +
        settings.get("work_start") +
        "–" +
        settings.get("work_end") +
        "（含准备和收尾）；请确认相关人员是否可参加。"
    );
    Long team = nullableId(item.get("organize_team_id"));
    List<Map<String, Object>> busy = new ArrayList<>();
    if (team != null) {
      Set<Long> seen = new HashSet<>();
      for (var other : events.list(
        " WHERE u.team_id=? AND u.active=1 AND e.target_id<>? AND e.id<>? AND e.status IN ('pending','accepted') AND e.phase<>'completed' AND e.start_at<? AND e.end_at>?",
        team,
        item.get("target_id"),
        item.getOrDefault("id", 0),
        end.plusDays(1),
        start.minusDays(1)
      )) {
        if (
          person(item, other) != null && seen.add(id(other.get("target_id")))
        ) busy.add(
          map("id", other.get("target_id"), "name", other.get("target_name"))
        );
      }
      if (!busy.isEmpty()) warnings.add(
        "拟组织团队有" +
          busy.size() +
          "位成员时间冲突；仍只向指定接收人写入安排，由其协调。"
      );
      long count = db.count(
        "SELECT count(*) FROM users WHERE team_id=? AND active=1",
        team
      );
      var room = db.one(
        "SELECT capacity FROM rooms WHERE name=?",
        s(item, "location")
      );
      if (room != null && count > id(room.get("capacity"))) warnings.add(
        "团队人数超过会议室容量。"
      );
    }
    if (!u.admin() && !same(u.teamId(), team)) busy.clear();
    return map("warnings", warnings, "team_busy", busy);
  }

  public List<Map<String, Object>> suggestions(
    Map<String, Object> item,
    long exclude
  ) {
    var initial = time(item.get("start"));
    var duration = Duration.between(initial, time(item.get("end")));
    List<Map<String, Object>> result = new ArrayList<>();
    for (int i = 1; i <= 336 && result.size() < 3; i++) {
      var start = initial.plusMinutes(i * 30L);
      var end = start.plus(duration);
      if (
        start.getDayOfWeek().getValue() > 5 ||
        start
          .minusMinutes(n(item, "buffer_before"))
          .isBefore(start.withHour(9).withMinute(0).withSecond(0)) ||
        end
          .plusMinutes(n(item, "buffer_after"))
          .isAfter(start.withHour(18).withMinute(0).withSecond(0))
      ) continue;
      var candidate = new LinkedHashMap<>(item);
      candidate.put("start", stamp(start.toString()));
      candidate.put("end", stamp(end.toString()));
      if (find(candidate, exclude).isEmpty()) result.add(
        map(
          "start",
          candidate.get("start"),
          "end",
          candidate.get("end"),
          "reason",
          "工作时段、人员缓冲与会议室检查通过；未自动移动已有安排。"
        )
      );
    }
    return result;
  }
}
