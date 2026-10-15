package com.xushi;

import static com.xushi.common.Fields.*;
import static org.junit.jupiter.api.Assertions.*;

import com.xushi.ai.RuleParser;
import com.xushi.common.Problem;
import java.util.List;
import org.junit.jupiter.api.Test;

class RuleParserTest {

  @Test
  void ambiguousRecipientAndChineseTime() {
    var rules = new RuleParser();
    var users = List.of(
      map("id", 2, "name", "赵明", "username", "zhao", "team_id", 1),
      map("id", 3, "name", "赵强", "username", "zhaoqiang", "team_id", 1)
    );
    var teams = List.of(map("id", 1, "name", "三组"));
    var draft = rules.parse(
      "让三组的小赵2026年10月20日下午三点半到五号会议室开会",
      users,
      teams
    );
    assertNull(draft.get("target_id"));
    assertEquals("2026-10-20T15:30:00+08:00", draft.get("start"));
    assertEquals("5号会议室", draft.get("location"));
    assertThrows(Problem.class, () ->
      rules.parse("让赵明2026年2月30日下午三点开会", users, teams)
    );
  }
}
