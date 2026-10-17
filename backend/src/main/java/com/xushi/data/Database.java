package com.xushi.data;

import com.xushi.common.*;
import java.sql.Timestamp;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class Database {

  private final JdbcTemplate jdbc;
  private final Json json;

  public Database(JdbcTemplate jdbc, Json json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  public List<Map<String, Object>> rows(String sql, Object... args) {
    return jdbc.query(
      sql,
      (rs, index) -> {
        Map<String, Object> row = new LinkedHashMap<>();
        var md = rs.getMetaData();
        for (int i = 1; i <= md.getColumnCount(); i++) {
          String key = md.getColumnLabel(i);
          Object value = rs.getObject(i);
          if (value instanceof Timestamp t) {
            boolean minute = key.equals("start") || key.equals("end");
            value = t
              .toInstant()
              .atOffset(Fields.ZONE)
              .format(
                DateTimeFormatter.ofPattern(
                  minute
                    ? "yyyy-MM-dd'T'HH:mm:ssxxx"
                    : "yyyy-MM-dd'T'HH:mm:ss.SSSSSSxxx"
                )
              );
          }
          if (value != null && md.getColumnTypeName(i).equals("jsonb")) value =
            json.value(value);
          row.put(key, value);
        }
        return row;
      },
      args
    );
  }

  public Map<String, Object> one(String sql, Object... args) {
    var rows = rows(sql, args);
    return rows.isEmpty() ? null : rows.get(0);
  }

  public int update(String sql, Object... args) {
    return jdbc.update(sql, args);
  }

  public long insert(String sql, Object... args) {
    return Objects.requireNonNull(
      jdbc.queryForObject(sql + " RETURNING id", Long.class, args)
    );
  }

  public long count(String sql, Object... args) {
    return Objects.requireNonNull(jdbc.queryForObject(sql, Long.class, args));
  }

  public String name() {
    return jdbc.queryForObject("SELECT current_database()", String.class);
  }

  public void lock() {
    jdbc.queryForObject("SELECT pg_advisory_xact_lock(742901)", Object.class);
  }

  public void audit(long actor, String action, Object details) {
    update(
      "INSERT INTO audit(actor_id,action,detail) VALUES(?,?,?::jsonb)",
      actor,
      action,
      json.write(details)
    );
  }

  public void notify(long user, long event, String title, String body) {
    update(
      "INSERT INTO notifications(user_id,event_id,title,body) VALUES(?,?,?,?)",
      user,
      event,
      title,
      body
    );
  }
}
