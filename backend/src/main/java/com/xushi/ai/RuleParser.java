package com.xushi.ai;

import static com.xushi.common.Fields.*;

import com.xushi.common.*;
import java.time.*;
import java.util.*;
import java.util.regex.*;
import org.springframework.stereotype.Component;

@Component
public class RuleParser {

  public int chinese(String value) {
    if (value.matches("\\d+")) return Integer.parseInt(value);
    String chars = "零一二三四五六七八九";
    if (value.equals("两")) return 2;
    if (value.contains("十")) {
      String[] p = value.split("十", -1);
      return (
        (p[0].isEmpty() ? 1 : chinese(p[0])) * 10 +
        (p[1].isEmpty() ? 0 : chinese(p[1]))
      );
    }
    int n = chars.indexOf(value);
    if (n < 0) throw new Problem("消息中的数字无效");
    return n;
  }

  private Matcher match(String pattern, String value) {
    return Pattern.compile(pattern).matcher(value);
  }

  public String combine(List<Map<String, Object>> history, String text) {
    String prior = String.join(
      "，",
      history
        .stream()
        .filter(m -> s(m, "role").equals("user"))
        .map(m -> s(m, "content"))
        .toList()
    );
    if (
      text.matches(".*(今天|明天|后天|周[一二三四五六日天]|[0-9]+[年/-]).*")
    ) prior = prior.replaceAll(
      "今天|明天|后天|(?:下)?周[一二三四五六日天]|[0-9]{4}[年/-][0-9]+[月/-][0-9]+",
      ""
    );
    if (text.matches(".*[点时:：].*")) prior = prior.replaceAll(
      "(?:上午|下午|晚上|中午|早上)?\\s*[零一二两三四五六七八九十0-9]+(?:点|时|:|：)(?:半|[零一二三四五六七八九十0-9]+)?",
      ""
    );
    if (text.contains("会议室")) prior = prior.replaceAll(
      "[一二三四五六七八九十0-9]+\\s*号会议室",
      ""
    );
    String result = prior.isBlank() ? text : prior + "，" + text;
    return result.substring(Math.max(0, result.length() - 4000));
  }

  public Map<String, Object> parse(
    String text,
    List<Map<String, Object>> users,
    List<Map<String, Object>> teams
  ) {
    Map<String, Object> group = teams
      .stream()
      .filter(t -> text.contains(s(t, "name")))
      .max(Comparator.comparingInt(t -> s(t, "name").length()))
      .orElse(null);
    if (group == null) {
      Matcher g = match("([一二三四五六七八九十0-9]+)组", text);
      if (g.find()) {
        String alias = chinese(g.group(1)) + "组",
          literal = g.group(1) + "组";
        group = teams
          .stream()
          .filter(
            t -> s(t, "name").contains(alias) || s(t, "name").contains(literal)
          )
          .findFirst()
          .orElse(null);
      }
    }
    List<Map<String, Object>> candidates = users
      .stream()
      .filter(
        u -> text.contains(s(u, "name")) || text.contains(s(u, "username"))
      )
      .toList();
    if (candidates.isEmpty()) {
      Matcher alias = match("(?:小|老)([\\p{IsHan}])", text);
      if (alias.find()) candidates = users
        .stream()
        .filter(u -> s(u, "name").startsWith(alias.group(1)))
        .toList();
    }
    if (group != null) {
      Object tid = group.get("id");
      candidates = candidates
        .stream()
        .filter(u -> same(u.get("team_id"), tid))
        .toList();
    }
    Object target = candidates.size() == 1 ? candidates.get(0).get("id") : null;
    List<String> warnings = new ArrayList<>();
    LocalDate day = LocalDate.now(ZONE);
    if (text.contains("后天")) day = day.plusDays(2);
    else if (text.contains("明天")) day = day.plusDays(1);
    Matcher date = match("(\\d{4})[年/-](\\d{1,2})[月/-](\\d{1,2})", text);
    if (date.find()) {
      try {
        day = LocalDate.of(
          Integer.parseInt(date.group(1)),
          Integer.parseInt(date.group(2)),
          Integer.parseInt(date.group(3))
        );
      } catch (Exception e) {
        throw new Problem("消息中的日期无效");
      }
    } else {
      Matcher week = match("周([一二三四五六日天])", text);
      if (week.find()) {
        int weekday = "一二三四五六日天".indexOf(week.group(1));
        weekday = Math.min(weekday, 6);
        int offset = weekday - (day.getDayOfWeek().getValue() - 1);
        if (text.contains("下周")) offset += 7;
        else if (offset < 0) offset += 7;
        day = day.plusDays(offset);
      } else if (!text.matches(".*(今天|明天|后天).*")) warnings.add(
        "未指定日期，暂按今天；请确认日期。"
      );
    }
    String start = null,
      end = null;
    Matcher hour = match(
      "(上午|下午|晚上|中午|早上)?\\s*([零一二两三四五六七八九十0-9]+)(?:点|时|:|：)(半|[零一二两三四五六七八九十0-9]+)?",
      text
    );
    if (hour.find()) {
      String period = hour.group(1);
      int h = chinese(hour.group(2)),
        m = "半".equals(hour.group(3))
          ? 30
          : chinese(hour.group(3) == null ? "0" : hour.group(3));
      if (period == null) {
        String prefix = text.substring(
          Math.max(0, hour.start() - 5),
          hour.start()
        );
        for (String p : List.of("下午", "晚上", "中午", "上午"))
          if (prefix.contains(p)) period = p;
      }
      if (
        Set.of("下午", "晚上", "中午").contains(Objects.toString(period, "")) &&
        h < 12
      ) h += 12;
      if (h > 23 || m > 59) throw new Problem("消息中的时间无效");
      Matcher duration = match("(\\d+)\\s*分钟", text);
      boolean specified = duration.find();
      int minutes = specified ? Integer.parseInt(duration.group(1)) : 60;
      var at = day.atTime(h, m).atOffset(ZONE);
      start = stamp(at.toString());
      end = stamp(at.plusMinutes(minutes).toString());
      if (!specified) warnings.add("未指定时长，暂按60分钟；请确认结束时间。");
    } else warnings.add("未识别到时间，请补充开始与结束时间。");
    Matcher room = match("([一二三四五六七八九十0-9]+)\\s*号会议室", text);
    String location = room.find() ? chinese(room.group(1)) + "号会议室" : "";
    if (target == null) warnings.add(
      "接收人有歧义或未找到，请从员工列表中选择。"
    );
    boolean organize = text.matches(
      ".*(所有人|所有成员|全员|全组|全部组员|全体).*"
    );
    String notes = organize
      ? "请组织" +
        (group == null ? "所在团队" : s(group, "name")) +
        "全体成员参会；由接收人通知组员。"
      : text;
    return map(
      "title",
      text.contains("开会") || text.contains("会议")
        ? "团队工作会议"
        : "工作安排",
      "target_id",
      target,
      "start",
      start,
      "end",
      end,
      "location",
      location,
      "notes",
      notes,
      "original_text",
      text,
      "candidates",
      candidates
        .stream()
        .map(u -> map("id", u.get("id"), "name", u.get("name")))
        .toList(),
      "warnings",
      warnings,
      "engine",
      "rules"
    );
  }
}
