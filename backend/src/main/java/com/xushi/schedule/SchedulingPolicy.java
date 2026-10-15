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
public class SchedulingPolicy {

  private final Database db;
  private final Json json;
  private final AuthService auth;

  public SchedulingPolicy(Database db, Json json, AuthService auth) {
    this.db = db;
    this.json = json;
    this.auth = auth;
  }

  public Map<String, Object> get() {
    return object(
      db
        .one("SELECT settings FROM scheduling_policy WHERE id=1")
        .get("settings")
    );
  }

  @Transactional
  public Object save(User actor, Map<String, Object> input) {
    db.lock();
    actor = auth.refresh(actor);
    if (!actor.admin()) throw new Problem("只有管理员可以设置排期规则", 403);
    var result = map();
    try {
      var start = LocalTime.parse(s(input, "work_start"));
      var end = LocalTime.parse(s(input, "work_end"));
      if (
        !end.isAfter(start) || start.getSecond() != 0 || end.getSecond() != 0
      ) throw new IllegalArgumentException();
      result.put("work_start", start.toString());
      result.put("work_end", end.toString());
      if (
        !(input.get("weekdays") instanceof List<?> days) ||
        days.isEmpty() ||
        days.size() > 7
      ) throw new IllegalArgumentException();
      var weekdays = new TreeSet<Long>();
      for (var day : days) {
        long value = id(day);
        if (value < 1 || value > 7) throw new IllegalArgumentException();
        weekdays.add(value);
      }
      result.put("weekdays", new ArrayList<>(weekdays));
      long step = id(input.get("step_minutes")),
        horizon = id(input.get("horizon_days"));
      if (
        !Set.of(15L, 30L, 60L).contains(step) || horizon < 1 || horizon > 14
      ) throw new IllegalArgumentException();
      result.put("step_minutes", step);
      result.put("horizon_days", horizon);
      for (String key : List.of("holidays", "workdays")) {
        if (
          !(input.getOrDefault(key, List.of()) instanceof List<?> dates) ||
          dates.size() > 366
        ) throw new IllegalArgumentException();
        var clean = new TreeSet<String>();
        for (var date : dates)
          clean.add(LocalDate.parse(date.toString()).toString());
        result.put(key, new ArrayList<>(clean));
      }
      if (
        ((List<?>) result.get("holidays"))
          .stream()
          .anyMatch(((List<?>) result.get("workdays"))::contains)
      ) throw new IllegalArgumentException();
    } catch (Exception e) {
      throw new Problem("排期规则无效：请检查工作时段、工作日与例外日期");
    }
    db.update(
      "UPDATE scheduling_policy SET settings=?::jsonb,updated_at=clock_timestamp() WHERE id=1",
      json.write(result)
    );
    db.audit(actor.id(), "scheduling.policy", result);
    return map("policy", result);
  }

  public boolean fits(Map<String, Object> p, Map<String, Object> item) {
    var start = time(item.get("start")).minusMinutes(n(item, "buffer_before"));
    var end = time(item.get("end")).plusMinutes(n(item, "buffer_after"));
    var day = start.toLocalDate();
    String date = day.toString();
    boolean workday =
      ((List<?>) p.get("workdays")).contains(date) ||
      (!((List<?>) p.get("holidays")).contains(date) &&
        ((List<?>) p.get("weekdays"))
          .stream()
          .anyMatch(x -> id(x) == day.getDayOfWeek().getValue()));
    return (
      workday &&
      end.toLocalDate().equals(day) &&
      !start.toLocalTime().isBefore(LocalTime.parse(s(p, "work_start"))) &&
      !end.toLocalTime().isAfter(LocalTime.parse(s(p, "work_end")))
    );
  }
}
