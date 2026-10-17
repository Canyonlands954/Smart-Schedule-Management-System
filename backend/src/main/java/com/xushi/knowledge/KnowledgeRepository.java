package com.xushi.knowledge;

import static com.xushi.common.Fields.*;

import com.xushi.auth.User;
import com.xushi.common.Problem;
import com.xushi.data.Database;
import java.util.*;
import org.springframework.stereotype.Repository;

@Repository
public class KnowledgeRepository {

  private final Database db;

  public KnowledgeRepository(Database db) {
    this.db = db;
  }

  public static final String ACCESS =
    "(? OR b.owner_id=? OR b.scope='all' OR (b.scope='team' AND b.team_id=?) OR EXISTS(SELECT 1 FROM knowledge_grants g WHERE g.base_id=b.id AND g.user_id=?))";
  public static final String LIVE =
    "NOT d.deleted AND d.published_version_id=v.id AND v.status='ready' AND v.effective_from<=(now() AT TIME ZONE 'Asia/Shanghai')::date AND (v.effective_until IS NULL OR v.effective_until>=(now() AT TIME ZONE 'Asia/Shanghai')::date)";
  public static final String JOIN =
    " FROM knowledge_chunks c JOIN knowledge_versions v ON v.id=c.version_id JOIN knowledge_documents d ON d.id=v.document_id JOIN knowledge_bases b ON b.id=d.base_id ";
  public static final String FIELDS =
    "c.id,c.content,c.locator,c.version_id,d.id document_id,d.title,v.version,v.external_allowed,b.name base_name,b.revision base_revision,d.revision document_revision";

  public Object[] args(User u, Object... extra) {
    var args = new ArrayList<Object>(
      Arrays.asList(u.admin(), u.id(), u.teamId(), u.id())
    );
    args.addAll(Arrays.asList(extra));
    return args.toArray();
  }

  public Map<String, Object> base(User u, long id) {
    var b = db.one(
      "SELECT b.* FROM knowledge_bases b WHERE " + ACCESS + " AND b.id=?",
      args(u, id)
    );
    if (b == null) throw new Problem("知识库不存在或无权访问", 404);
    return b;
  }

  public Map<String, Object> document(User u, long id) {
    var d = db.one(
      "SELECT d.* FROM knowledge_documents d JOIN knowledge_bases b ON b.id=d.base_id WHERE " +
        ACCESS +
        " AND NOT d.deleted AND d.id=?",
      args(u, id)
    );
    if (d == null) throw new Problem("资料不存在或无权访问", 404);
    return d;
  }

  public Map<String, Object> chunk(User u, long id) {
    var c = db.one(
      "SELECT " +
        FIELDS +
        JOIN +
        " WHERE " +
        ACCESS +
        " AND " +
        LIVE +
        " AND c.id=?",
      args(u, id)
    );
    if (c == null) throw new Problem("引用已失效或无权访问，请重新检索", 404);
    return c;
  }

  public Map<String, Object> citation(Map<String, Object> c) {
    return map(
      "id",
      c.get("id"),
      "version_id",
      c.get("version_id"),
      "document_id",
      c.get("document_id"),
      "title",
      c.get("title"),
      "version",
      c.get("version"),
      "locator",
      c.get("locator"),
      "base_name",
      c.get("base_name"),
      "base_revision",
      c.get("base_revision"),
      "document_revision",
      c.get("document_revision")
    );
  }

  public boolean valid(
    User u,
    List<Map<String, Object>> citations,
    boolean external
  ) {
    for (var c : citations) {
      try {
        var current = chunk(u, id(c.get("id")));
        if (
          !same(c.get("version_id"), current.get("version_id")) ||
          !same(c.get("base_revision"), current.get("base_revision")) ||
          (external && !Boolean.TRUE.equals(current.get("external_allowed")))
        ) return false;
      } catch (Problem p) {
        return false;
      }
    }
    return true;
  }
}
