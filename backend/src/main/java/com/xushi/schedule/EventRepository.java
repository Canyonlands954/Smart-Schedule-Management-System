package com.xushi.schedule;

import static com.xushi.common.Fields.*;

import com.xushi.common.*;
import com.xushi.data.Database;
import java.util.*;
import org.springframework.stereotype.Repository;

@Repository
public class EventRepository {

  public static final String SELECT =
    "SELECT e.id,e.title,e.target_id,e.creator_id,e.start_at AS start,e.end_at AS end,e.location,e.room_id,e.notes,e.status,e.phase,e.source,e.original_text,e.created_at,e.updated_at,u.name target_name,v.name creator_name,d.attrs FROM events e JOIN users u ON e.target_id=u.id JOIN users v ON e.creator_id=v.id LEFT JOIN event_details d ON d.event_id=e.id";
  private final Database db;
  private final Json json;

  public EventRepository(Database db, Json json) {
    this.db = db;
    this.json = json;
  }

  public Map<String, Object> decorate(Map<String, Object> row) {
    Map<String, Object> result = map(
      "priority",
      "normal",
      "buffer_before",
      0,
      "buffer_after",
      0,
      "travel_minutes",
      0,
      "organize_team_id",
      null,
      "checklist",
      new ArrayList<>(),
      "completion_report",
      "",
      "reschedule_request",
      null,
      "draft_id",
      null
    );
    result.putAll(row);
    result.putAll(object(result.remove("attrs")));
    return result;
  }

  public Map<String, Object> get(long id) {
    var row = db.one(SELECT + " WHERE e.id=?", id);
    if (row == null) throw new Problem("日程不存在", 404);
    return decorate(row);
  }

  public List<Map<String, Object>> list(String condition, Object... args) {
    return db
      .rows(SELECT + condition, args)
      .stream()
      .map(this::decorate)
      .toList();
  }

  public void details(long id, Map<String, Object> attrs) {
    db.update(
      "INSERT INTO event_details VALUES(?,?::jsonb) ON CONFLICT(event_id) DO UPDATE SET attrs=EXCLUDED.attrs",
      id,
      json.write(attrs)
    );
  }

  public void snapshot(long actor, long id, String action) {
    db.update(
      "INSERT INTO event_history(event_id,actor_id,action,snapshot) VALUES(?,?,?,?::jsonb)",
      id,
      actor,
      action,
      json.write(get(id))
    );
  }

  public Map<String, Object> attrs(Map<String, Object> event) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (String key : List.of(
      "priority",
      "buffer_before",
      "buffer_after",
      "travel_minutes",
      "organize_team_id",
      "checklist",
      "completion_report",
      "reschedule_request",
      "draft_id"
    ))
      result.put(key, event.get(key));
    return result;
  }
}
