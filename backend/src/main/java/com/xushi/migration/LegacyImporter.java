package com.xushi.migration;

import static com.xushi.common.Fields.*;

import com.xushi.common.*;
import com.xushi.data.Database;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class LegacyImporter implements CommandLineRunner {

  private final String source;
  private final Database db;
  private final Json json;
  private final TransactionTemplate tx;

  public LegacyImporter(
    @Value("${schedule.import-source}") String source,
    Database db,
    Json json,
    PlatformTransactionManager manager
  ) {
    this.source = source;
    this.db = db;
    this.json = json;
    tx = new TransactionTemplate(manager);
  }

  public void run(String... args) throws Exception {
    if (source.isBlank()) return;
    Path path = Path.of(source).toAbsolutePath();
    if (!Files.isRegularFile(path)) throw new IllegalArgumentException(
      "Migration source does not exist"
    );
    String digest = HexFormat.of().formatHex(
      MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))
    );
    if (
      db.count(
        "SELECT count(*) FROM migration_runs WHERE source_digest=?",
        digest
      ) > 0
    ) return;
    try (
      Connection legacy = DriverManager.getConnection(
        "jdbc:sqlite:file:" + path.toString().replace('\\', '/') + "?mode=ro"
      )
    ) {
      legacy.setAutoCommit(false);
      tx.execute(status -> {
        db.lock();
        if (
          db.count("SELECT count(*) FROM users") > 0 ||
          db.count("SELECT count(*) FROM events") > 0
        ) throw new IllegalStateException(
          "Migration requires an empty target database; existing data was not modified"
        );
        db.update("SET CONSTRAINTS ALL DEFERRED");
        db.update("DELETE FROM rooms");
        Map<String, Object> counts = new LinkedHashMap<>();
        for (String table : List.of(
          "teams",
          "users",
          "rooms",
          "events",
          "event_details",
          "event_history",
          "notifications",
          "ai_drafts",
          "event_requests",
          "audit"
        )) {
          try (
            var stmt = legacy.createStatement();
            var rs = stmt.executeQuery("SELECT * FROM " + table)
          ) {
            int count = 0;
            while (rs.next()) {
              var md = rs.getMetaData();
              List<String> cols = new ArrayList<>(),
                slots = new ArrayList<>();
              List<Object> values = new ArrayList<>();
              for (int i = 1; i <= md.getColumnCount(); i++) {
                String col = md.getColumnName(i);
                Object value = rs.getObject(i);
                if (
                  table.equals("events") &&
                  (col.equals("start") || col.equals("end"))
                ) col += "_at";
                boolean date = Set.of(
                  "created_at",
                  "updated_at",
                  "start_at",
                  "end_at"
                ).contains(col);
                boolean jsonCol = Set.of(
                  "attrs",
                  "snapshot",
                  "result",
                  "detail"
                ).contains(col);
                cols.add(col);
                slots.add(jsonCol ? "?::jsonb" : "?");
                values.add(date && value != null ? time(value) : value);
              }
              db.update(
                "INSERT INTO " +
                  table +
                  "(" +
                  String.join(",", cols) +
                  ") VALUES(" +
                  String.join(",", slots) +
                  ")",
                values.toArray()
              );
              count++;
            }
            counts.put(table, count);
          } catch (SQLException e) {
            throw new IllegalStateException(
              "Migration failed reading " + table,
              e
            );
          }
        }
        db.update(
          "UPDATE events e SET room_id=r.id FROM rooms r WHERE e.location=r.name"
        );
        for (String table : List.of(
          "teams",
          "users",
          "rooms",
          "events",
          "event_history",
          "notifications",
          "ai_drafts",
          "audit"
        ))
          db.count(
            "SELECT setval(pg_get_serial_sequence('" +
              table +
              "','id'),GREATEST(COALESCE((SELECT max(id) FROM " +
              table +
              "),0),1),(SELECT count(*)>0 FROM " +
              table +
              "))"
          );
        db.update(
          "INSERT INTO migration_runs(source_digest,counts) VALUES(?,?::jsonb)",
          digest,
          json.write(counts)
        );
        return counts;
      });
      legacy.rollback();
    }
  }
}
