package com.xushi.ai;

import static com.xushi.common.Fields.*;

import com.xushi.common.*;
import java.time.*;
import java.util.*;
import java.util.regex.*;
import org.springframework.stereotype.Component;

/** Resolves only the latest instruction against explicit, persisted fields. */
@Component
public class ConversationResolver {

  private final RuleParser numbers;

  public ConversationResolver(RuleParser numbers) {
    this.numbers = numbers;
  }

  private Matcher match(String pattern, String text) {
    return Pattern.compile(pattern).matcher(text);
  }

  private int number(String value) {
    return numbers.chinese(value.replace("个", ""));
  }

  private static final String NUM = "[零一二两三四五六七八九十0-9]+";

  public boolean modification(String text) {
    return text.matches(
      "(?s).*(刚才|上一场|那场|这场|那次|改期申请|推迟|延后|提前|改期|改到|换到|修改.*会议).*"
    );
  }

  public void mergeEdits(Map<String, Object> state, Map<String, Object> input) {
    var d = object(state.get("draft"));
    var r = object(state.get("resolution"));
    for (String key : List.of(
      "title",
      "target_id",
      "start",
      "end",
      "location",
      "notes",
      "priority",
      "organize_team_id",
      "buffer_before",
      "buffer_after",
      "travel_minutes",
      "checklist"
    )) {
      if (!input.containsKey(key) || same(input.get(key), d.get(key))) continue;
      Object value = input.get(key);
      if (List.of("start", "end").contains(key)) value =
        value == null || value.equals("") ? null : stamp(value);
      if (key.equals("target_id")) value =
        nullableId(value) == null || id(value) == 0 ? null : id(value);
      if (List.of("title", "location", "notes").contains(key)) value = text(
        value,
        key,
        key.equals("notes") ? 2000 : 100,
        false
      );
      if (
        List.of("buffer_before", "buffer_after", "travel_minutes").contains(key)
      ) {
        value = id(value);
        if (id(value) > 180) throw new Problem("缓冲时间不能超过180分钟");
      }
      if (key.equals("organize_team_id")) {
        value = nullableId(value);
        r.put("organize", value != null);
      }
      if (key.equals("checklist")) {
        if (!(value instanceof List<?> l) || l.size() > 12) throw new Problem(
          "清单最多12项"
        );
        for (Object x : l) text(object(x).get("label"), "清单项", 100, true);
      }
      d.put(key, value);
    }
    if (d.get("start") != null) {
      var start = time(d.get("start"));
      r.putAll(
        map(
          "date",
          start.toLocalDate().toString(),
          "hour",
          start.getHour(),
          "minute",
          start.getMinute(),
          "period",
          start.getHour() >= 12 ? "下午" : "上午",
          "ambiguous_hour",
          false
        )
      );
    }
    if (d.get("start") != null && d.get("end") != null) {
      long duration = Duration.between(
        time(d.get("start")),
        time(d.get("end"))
      ).toMinutes();
      if (duration <= 0 || duration > 10080) throw new Problem("日程时长无效");
      r.put("duration_minutes", duration);
    }
    state.put("draft", d);
    state.put("resolution", r);
  }

  public Map<String, Object> apply(
    Map<String, Object> state,
    String message,
    List<Map<String, Object>> users,
    List<Map<String, Object>> teams,
    List<Map<String, Object>> rooms
  ) {
    var d = object(state.get("draft"));
    var r = object(state.get("resolution"));
    boolean first = d.isEmpty();
    if (first) d.putAll(
      map(
        "title",
        message.matches(".*(会议|开会).*") ? "团队工作会议" : "工作安排",
        "target_id",
        null,
        "location",
        "",
        "notes",
        message,
        "priority",
        "normal",
        "buffer_before",
        0,
        "buffer_after",
        0,
        "travel_minutes",
        0,
        "checklist",
        new ArrayList<>(),
        "organize_team_id",
        null,
        "action",
        "create"
      )
    );
    String original = s(d, "original_text");
    d.put(
      "original_text",
      original.isBlank() ? message : original + "\n" + message
    );
    if (s(d, "original_text").length() > 4000) d.put(
      "original_text",
      s(d, "original_text").substring(s(d, "original_text").length() - 4000)
    );
    String instruction = message
      .replaceAll(
        "(?:标题|主题)(?:改为|改成|是|为|[:：]).+?(?:[，。\\n]|$)",
        ""
      )
      .replaceAll("(?:备注|议题)(?:改为|改成|补充|是|为|[:：]).+", "");
    Map<String, Object> group = teams
      .stream()
      .filter(
        t -> !s(t, "name").isBlank() && instruction.contains(s(t, "name"))
      )
      .max(Comparator.comparingInt(t -> s(t, "name").length()))
      .orElse(null);
    var groupAlias = match("(" + NUM + ")组", instruction);
    if (group == null && groupAlias.find()) {
      int g = number(groupAlias.group(1));
      group = teams
        .stream()
        .filter(t -> {
          String name = s(t, "name");
          return (
            name.contains(g + "组") || name.contains(groupAlias.group(1) + "组")
          );
        })
        .findFirst()
        .orElse(null);
    }
    String recipientText = instruction.replaceAll("今天|明天|后天", " ");
    List<Map<String, Object>> candidates = users
      .stream()
      .filter(
        u ->
          recipientText.contains(s(u, "name")) ||
          recipientText.contains(s(u, "username"))
      )
      .toList();
    Matcher alias = match("(?:小(?!时|组|结)|老)([\\p{IsHan}])", recipientText);
    boolean named = !candidates.isEmpty() || alias.find();
    if (candidates.isEmpty() && named) {
      String surname = alias.group(1);
      candidates = users
        .stream()
        .filter(u -> s(u, "name").startsWith(surname))
        .toList();
    }
    if (group != null) {
      Object tid = group.get("id");
      r.put("team_id", tid);
      candidates = candidates
        .stream()
        .filter(u -> same(u.get("team_id"), tid))
        .toList();
    }
    if (named) {
      d.put(
        "target_id",
        candidates.size() == 1 ? candidates.get(0).get("id") : null
      );
      d.put(
        "candidates",
        candidates
          .stream()
          .map(u -> map("id", u.get("id"), "name", u.get("name")))
          .toList()
      );
    } else if (group != null && d.get("target_id") != null) {
      Object tid = group.get("id");
      Object target = d.get("target_id");
      if (
        users
          .stream()
          .noneMatch(
            u -> same(u.get("id"), target) && same(u.get("team_id"), tid)
          )
      ) d.put("target_id", null);
    }
    LocalDate today = LocalDate.now(ZONE);
    LocalDate date = null;
    Matcher absolute = match(
      "(\\d{4})[年/-](\\d{1,2})[月/-](\\d{1,2})",
      instruction
    );
    try {
      if (absolute.find()) date = LocalDate.of(
        Integer.parseInt(absolute.group(1)),
        Integer.parseInt(absolute.group(2)),
        Integer.parseInt(absolute.group(3))
      );
      else if (instruction.contains("后天")) date = today.plusDays(2);
      else if (instruction.contains("明天")) date = today.plusDays(1);
      else if (instruction.contains("今天")) date = today;
      else {
        Matcher week = match(
          "(下周|本周|周|星期)([一二三四五六日天])",
          instruction
        );
        if (week.find()) {
          int day = Math.min(7, "一二三四五六日天".indexOf(week.group(2)) + 1);
          date = today
            .minusDays(today.getDayOfWeek().getValue() - 1)
            .plusDays(day - 1);
          if (week.group(1).equals("下周")) date = date.plusDays(7);
          else if (!week.group(1).equals("本周") && date.isBefore(today)) date =
            date.plusDays(7);
        }
      }
    } catch (Exception e) {
      throw new Problem("消息中的日期无效");
    }
    if (date != null) r.put("date", date.toString());
    Matcher clock = match(
      "(上午|下午|晚上|中午|早上|凌晨)?\\s*(" +
        NUM +
        ")(?:点|时(?!间)|[:：])(半|" +
        NUM +
        ")?",
      instruction
    );
    // '一小时' describes duration, not a clock.
    boolean explicitEnd = instruction.matches(
      ".*结束(?:时间|于|改为|改到|是|为|[:：]).*"
    );
    if (clock.find() && !explicitEnd) {
      int hour = number(clock.group(2)),
        minute =
          clock.group(3) == null
            ? 0
            : clock.group(3).equals("半")
              ? 30
              : number(clock.group(3));
      if (hour > 23 || minute > 59) throw new Problem("消息中的时间无效");
      String period = clock.group(1) == null ? s(r, "period") : clock.group(1);
      if (
        !period.isBlank() &&
        hour < 12 &&
        Set.of("下午", "晚上", "中午").contains(period)
      ) hour += 12;
      if (Set.of("凌晨", "上午", "早上").contains(period) && hour == 12) hour =
        0;
      r.putAll(
        map(
          "hour",
          hour,
          "minute",
          minute,
          "ambiguous_hour",
          period.isBlank() && hour >= 1 && hour <= 12
        )
      );
      if (!period.isBlank()) r.put("period", period);
    } else {
      Matcher period = match("上午|下午|晚上|中午|早上|凌晨", instruction);
      if (period.find() && r.get("hour") != null) {
        int hour = n(r, "hour") % 12;
        if (Set.of("下午", "晚上", "中午").contains(period.group())) hour += 12;
        r.putAll(
          map("hour", hour, "period", period.group(), "ambiguous_hour", false)
        );
      }
    }
    Integer duration = duration(instruction);
    Matcher shift = match(
      "(推迟|延后|提前)(?:了)?\\s*(半(?:个)?小时|" +
        NUM +
        "(?:个)?小时(?:" +
        NUM +
        "分钟)?|" +
        NUM +
        "分钟)",
      instruction
    );
    boolean shifted = shift.find();
    if (shifted && d.get("start") != null && d.get("end") != null) {
      int delta =
        Objects.requireNonNull(duration(shift.group(2))) *
        (shift.group(1).equals("提前") ? -1 : 1);
      var start = time(d.get("start")).plusMinutes(delta);
      var end = time(d.get("end")).plusMinutes(delta);
      r.putAll(
        map(
          "date",
          start.toLocalDate().toString(),
          "hour",
          start.getHour(),
          "minute",
          start.getMinute(),
          "ambiguous_hour",
          false,
          "period",
          start.getHour() >= 12 ? "下午" : "上午",
          "duration_minutes",
          Duration.between(start, end).toMinutes()
        )
      );
    } else if (duration != null && !shifted) r.put(
      "duration_minutes",
      duration
    );
    // Explicit clock range supplies duration after the start clock is resolved.
    Matcher range = match(
      "(?:到|至|结束(?:时间)?(?:于|改为|改到|是|为|[:：])?)(上午|下午|晚上|中午)?\\s*(" +
        NUM +
        ")(?:点|[:：])(半|" +
        NUM +
        ")?",
      instruction
    );
    if (
      range.find() &&
      (explicitEnd ||
        Pattern.compile("点|[:：]").matcher(instruction).results().count() >=
          2) &&
      r.get("hour") != null &&
      !Boolean.TRUE.equals(r.get("ambiguous_hour"))
    ) {
      int h = number(range.group(2)),
        m =
          range.group(3) == null
            ? 0
            : range.group(3).equals("半")
              ? 30
              : number(range.group(3));
      String period = range.group(1) == null ? s(r, "period") : range.group(1);
      if (h < 12 && Set.of("下午", "晚上", "中午").contains(period)) h += 12;
      int minutes = h * 60 + m - n(r, "hour") * 60 - n(r, "minute");
      if (h > 23 || m > 59 || minutes <= 0) throw new Problem(
        "结束时间须晚于开始时间"
      );
      r.put("duration_minutes", minutes);
    }
    if (
      r.get("date") != null &&
      r.get("hour") != null &&
      !Boolean.TRUE.equals(r.get("ambiguous_hour"))
    ) {
      var at = LocalDate.parse(s(r, "date"))
        .atTime(n(r, "hour"), n(r, "minute"))
        .atOffset(ZONE);
      d.put("start", stamp(at.toString()));
      d.put(
        "end",
        r.get("duration_minutes") == null
          ? null
          : stamp(at.plusMinutes(n(r, "duration_minutes")).toString())
      );
    } else {
      d.put("start", null);
      d.put("end", null);
    }
    Matcher room = match("(" + NUM + ")\\s*号会议室", instruction);
    if (room.find()) d.put("location", number(room.group(1)) + "号会议室");
    else rooms
      .stream()
      .filter(x -> instruction.contains(s(x, "name")))
      .max(Comparator.comparingInt(x -> s(x, "name").length()))
      .ifPresent(x -> d.put("location", x.get("name")));
    if (instruction.contains("线上") || instruction.contains("远程会议")) d.put(
      "location",
      "线上会议"
    );
    Matcher title = match(
      "(?:标题|主题)(?:改为|改成|是|为|[:：])(.+?)(?:[，。\n]|$)",
      message
    );
    if (title.find()) d.put("title", text(title.group(1), "标题", 100, true));
    Matcher notes = match(
      "(?:备注|议题)(?:改为|改成|补充|是|为|[:：])(.+)",
      message
    );
    if (notes.find()) d.put("notes", text(notes.group(1), "备注", 2000, false));
    for (String key : List.of(
      "buffer_before",
      "buffer_after",
      "travel_minutes"
    )) {
      String name = key.equals("buffer_before")
        ? "(?:提前准备|准备时间)"
        : key.equals("buffer_after")
          ? "(?:收尾|结束收尾)"
          : "(?:通勤|路程)";
      Matcher buffer = match(
        name + "(?:需要|时间|改为|改成|为|[:：])?\\s*(" + NUM + ")分钟",
        instruction
      );
      if (buffer.find()) {
        int minutes = number(buffer.group(1));
        if (minutes > 180) throw new Problem("缓冲时间不能超过180分钟");
        d.put(key, minutes);
      }
    }
    if (instruction.contains("紧急")) d.put("priority", "urgent");
    else if (instruction.contains("重要")) d.put("priority", "high");
    if (
      instruction.matches("(?s).*(全员|全体|全组|所有人|所有成员|全部组员).*")
    ) r.put("organize", true);
    if (instruction.matches(".*(不用|不需要|无需).*(全组|全员|团队).*")) {
      r.put("organize", false);
      d.put("organize_team_id", null);
    }
    if (Boolean.TRUE.equals(r.get("organize")) && d.get("target_id") != null) {
      Object target = d.get("target_id");
      users
        .stream()
        .filter(u -> same(u.get("id"), target))
        .findFirst()
        .ifPresent(u -> d.put("organize_team_id", u.get("team_id")));
    }
    state.put("draft", d);
    state.put("resolution", r);
    return d;
  }

  public Integer duration(String message) {
    message = message.replaceAll(
      "(?:提前准备|准备时间|收尾|结束收尾|通勤|路程)(?:需要|时间|改为|改成|为|[:：])?\\s*" +
        NUM +
        "分钟",
      ""
    );
    int total = 0;
    boolean specified = false;
    Matcher hours = match("(" + NUM + ")(?:个)?(半)?小时", message);
    if (hours.find()) {
      total += number(hours.group(1)) * 60;
      if (
        hours.group(2) != null ||
        message.substring(hours.end()).startsWith("半")
      ) total += 30;
      specified = true;
    } else if (message.matches(".*半(?:个)?小时.*")) {
      total = 30;
      specified = true;
    }
    Matcher minutes = match("(" + NUM + ")\\s*分钟", message);
    if (minutes.find()) {
      total += number(minutes.group(1));
      specified = true;
    }
    // Buffer durations and relative shifts must not become meeting duration.
    if (
      message.matches(".*(准备|收尾|通勤|路程).*分钟.*") &&
      !message.matches(".*(持续|时长|开会|会议).*")
    ) return null;
    if (specified && (total <= 0 || total > 10080)) throw new Problem(
      "日程时长须为1分钟至7天"
    );
    return specified ? total : null;
  }

  public List<String> questions(Map<String, Object> state) {
    var d = object(state.get("draft"));
    var r = object(state.get("resolution"));
    List<String> questions = new ArrayList<>();
    if (Boolean.TRUE.equals(state.get("needs_reference"))) return List.of(
      "你想调整哪一场安排？请选择下面的原日程。"
    );
    if (s(d, "title").isBlank()) questions.add("请补充安排的标题或主题。");
    if (d.get("target_id") == null) questions.add(
      "这项安排发给哪位员工？请补充姓名或团队。"
    );
    if (r.get("date") == null) questions.add(
      "安排在哪一天？例如今天、明天或具体日期。"
    );
    if (r.get("hour") == null) questions.add("几点开始？请注明上午或下午。");
    else if (Boolean.TRUE.equals(r.get("ambiguous_hour"))) questions.add(
      "你说的时间是上午还是下午？"
    );
    if (r.get("duration_minutes") == null) questions.add(
      "这项安排持续多久？例如30分钟或1小时。"
    );
    if (s(d, "location").isBlank()) questions.add(
      "在哪里进行？请补充会议室或线上会议。"
    );
    return questions;
  }
}
