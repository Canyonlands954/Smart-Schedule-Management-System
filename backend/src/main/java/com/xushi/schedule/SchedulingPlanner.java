package com.xushi.schedule;

import static com.xushi.common.Fields.*;

import com.xushi.common.*;
import com.xushi.data.Database;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class SchedulingPlanner {

  private final Database db;
  private final EventRepository events;
  private final ConflictService conflicts;
  private final SchedulingPolicy policy;

  public SchedulingPlanner(
    Database db,
    EventRepository events,
    ConflictService conflicts,
    SchedulingPolicy policy
  ) {
    this.db = db;
    this.events = events;
    this.conflicts = conflicts;
    this.policy = policy;
  }

  public List<Map<String, Object>> suggest(
    Map<String, Object> item,
    long exclude
  ) {
    var p = policy.get();
    var initial = time(item.get("start"));
    var duration = Duration.between(initial, time(item.get("end")));
    long horizon = id(p.get("horizon_days")),
      step = id(p.get("step_minutes"));
    var rooms = db.rows("SELECT id,name,capacity FROM rooms ORDER BY id");
    boolean physical = rooms
      .stream()
      .anyMatch(r -> s(r, "name").equals(s(item, "location")));
    Long team = nullableId(item.get("organize_team_id"));
    var members =
      team == null
        ? List.<Map<String, Object>>of()
        : db.rows("SELECT id FROM users WHERE team_id=? AND active=1", team);
    Set<Long> people = new HashSet<>();
    people.add(id(item.get("target_id")));
    members.forEach(m -> people.add(id(m.get("id"))));
    long attendance = Math.max(1, members.size());
    // One bounded snapshot for all candidates; occupancy is never exposed as private titles.
    var snapshot = events.list(
      " WHERE e.id<>? AND e.status IN ('pending','accepted') AND e.phase<>'completed' AND e.start_at<? AND e.end_at>? AND (e.target_id=? OR u.team_id=? OR (? AND e.room_id IS NOT NULL)) ORDER BY e.start_at LIMIT 5001",
      exclude,
      initial.plusDays(horizon + 1),
      initial.minusDays(1),
      item.get("target_id"),
      team,
      physical
    );
    if (snapshot.size() > 5000) throw new Problem(
      "候选时段安排过多，请缩小排期范围",
      422
    );
    List<Map<String, Object>> locations = physical
      ? rooms
      : List.of(map("name", s(item, "location"), "capacity", 1000));
    List<Map<String, Object>> candidates = new ArrayList<>();
    for (long offset = 0; offset <= horizon * 1440; offset += step) {
      var start = initial.plusMinutes(offset);
      if (start.isBefore(OffsetDateTime.now(ZONE))) continue;
      for (var room : locations) {
        if (id(room.get("capacity")) < attendance) continue;
        var candidate = new LinkedHashMap<>(item);
        candidate.putAll(
          map(
            "start",
            stamp(start.toString()),
            "end",
            stamp(start.plus(duration).toString()),
            "location",
            room.get("name")
          )
        );
        if (!policy.fits(p, candidate)) continue;
        Set<Long> busy = new HashSet<>();
        boolean blocked = false;
        for (var other : snapshot) {
          boolean person =
            people.contains(id(other.get("target_id"))) &&
            conflicts.person(candidate, other) != null;
          if (person && same(item.get("target_id"), other.get("target_id"))) {
            blocked = true;
            break;
          }
          if (
            physical &&
            s(candidate, "location").equals(s(other, "location")) &&
            time(candidate.get("start"))
              .minusMinutes(n(candidate, "buffer_before"))
              .isBefore(
                time(other.get("end")).plusMinutes(n(other, "buffer_after"))
              ) &&
            time(candidate.get("end"))
              .plusMinutes(n(candidate, "buffer_after"))
              .isAfter(
                time(other.get("start")).minusMinutes(n(other, "buffer_before"))
              )
          ) {
            blocked = true;
            break;
          }
          if (person) busy.add(id(other.get("target_id")));
        }
        if (blocked) continue;
        boolean changedRoom = !s(item, "location").equals(
          s(candidate, "location")
        );
        long score =
          offset * (s(item, "priority").equals("urgent") ? 3 : 1) +
          (changedRoom ? 20 : 0) +
          busy.size() * 180;
        List<String> reasons = new ArrayList<>();
        reasons.add("接收人空闲，准备、收尾和跨地点通勤检查通过");
        if (physical) reasons.add(
          "会议室可用，容量" +
            room.get("capacity") +
            "人 / 预计" +
            attendance +
            "人"
        );
        if (team != null) reasons.add(
          busy.isEmpty()
            ? "团队成员均无已知时间冲突"
            : "团队仍有" + busy.size() + "人忙碌，需要接收人协调"
        );
        reasons.add(
          "符合工作时段 " + p.get("work_start") + "–" + p.get("work_end")
        );
        reasons.add(
          offset == 0 ? "保留原开始时间" : "较原安排推迟" + offset + "分钟"
        );
        if (changedRoom) reasons.add("需更换会议室，已计入地点变更的协调成本");
        candidates.add(
          map(
            "start",
            candidate.get("start"),
            "end",
            candidate.get("end"),
            "location",
            candidate.get("location"),
            "score",
            score,
            "team_busy_count",
            busy.size(),
            "delay_minutes",
            offset,
            "reasons",
            reasons,
            "reason",
            String.join("；", reasons)
          )
        );
      }
    }
    candidates.sort(
      Comparator.<Map<String, Object>>comparingLong(c -> id(c.get("score")))
        .thenComparing(c -> s(c, "start"))
        .thenComparing(c -> s(c, "location"))
    );
    List<Map<String, Object>> result = new ArrayList<>();
    Set<String> times = new HashSet<>();
    Set<String> locationsUsed = new HashSet<>();
    for (var c : candidates) {
      // Prefer comparable time/room choices over three identical-time room substitutions.
      if (
        result.size() > 0 &&
        times.contains(s(c, "start")) &&
        locationsUsed.contains(s(c, "location"))
      ) continue;
      if (
        result.size() > 0 &&
        times.contains(s(c, "start")) &&
        result
          .stream()
          .filter(x -> s(x, "start").equals(s(c, "start")))
          .count() >= 2
      ) continue;
      c.put("rank", result.size() + 1);
      result.add(c);
      times.add(s(c, "start"));
      locationsUsed.add(s(c, "location"));
      if (result.size() == 3) break;
    }
    return result;
  }
}
