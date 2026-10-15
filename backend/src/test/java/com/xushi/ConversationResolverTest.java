package com.xushi;

import static com.xushi.common.Fields.*;
import static org.junit.jupiter.api.Assertions.*;

import com.xushi.ai.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ConversationResolverTest {

  private final ConversationResolver resolver = new ConversationResolver(
    new RuleParser()
  );
  private final List<Map<String, Object>> users = List.of(
    map("id", 2, "name", "赵明", "username", "zhao", "team_id", 1)
  );

  private Map<String, Object> apply(Map<String, Object> state, String text) {
    return resolver.apply(
      state,
      text,
      users,
      List.of(map("id", 1, "name", "三组")),
      List.of(map("id", 5, "name", "5号会议室"))
    );
  }

  @Test
  void endTimeAndBuffersDoNotOverwriteStartOrDuration() {
    var state = map();
    var d = apply(
      state,
      "小赵明天下午三点到四点在5号会议室开会，准备时间15分钟"
    );
    assertEquals(15, time(d.get("start")).getHour());
    assertEquals(16, time(d.get("end")).getHour());
    assertEquals(15, id(d.get("buffer_before")));
    d = apply(state, "改到五点");
    assertEquals(17, time(d.get("start")).getHour());
    assertEquals(18, time(d.get("end")).getHour());
    d = apply(state, "结束时间改到六点半");
    assertEquals(17, time(d.get("start")).getHour());
    assertEquals(18, time(d.get("end")).getHour());
    assertEquals(30, time(d.get("end")).getMinute());
    d = apply(state, "持续一小时，准备时间改为20分钟");
    assertEquals(
      60,
      Duration.between(time(d.get("start")), time(d.get("end"))).toMinutes()
    );
    assertEquals(90, resolver.duration("持续一个半小时"));
  }

  @Test
  void invalidDatesAndMissingDateRequireClarification() {
    var state = map();
    var d = apply(state, "小赵下午三点开会，持续半小时");
    assertNull(d.get("start"));
    assertTrue(
      resolver
        .questions(state)
        .stream()
        .anyMatch(q -> q.contains("哪一天"))
    );
    assertThrows(com.xushi.common.Problem.class, () ->
      apply(state, "2026-02-30")
    );
    d = apply(state, "明天");
    assertEquals(15, time(d.get("start")).getHour());
    assertEquals(
      30,
      Duration.between(time(d.get("start")), time(d.get("end"))).toMinutes()
    );
  }

  @Test
  void namesAndTimesInsideNotesAreNotSchedulingChanges() {
    var state = map();
    var d = apply(state, "小赵明天下午三点在5号会议室开会，持续1小时");
    var start = d.get("start");
    var end = d.get("end");
    d = apply(state, "备注改为陈晓下午四点在4号会议室开会");
    assertEquals(start, d.get("start"));
    assertEquals(end, d.get("end"));
    assertEquals("5号会议室", d.get("location"));
    assertEquals(2, id(d.get("target_id")));
    assertEquals("陈晓下午四点在4号会议室开会", d.get("notes"));
  }
}
