package com.xushi.knowledge;

import static com.xushi.common.Fields.*;

import com.xushi.ai.*;
import com.xushi.auth.*;
import com.xushi.common.*;
import com.xushi.data.Database;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EmbeddingConfiguration {

  public record Connection(
    String baseUrl,
    String model,
    String key,
    int dimensions,
    String space,
    long revision
  ) {
    @Override
    public String toString() {
      return (
        "EmbeddingConnection[revision=" + revision + ", credentials=REDACTED]"
      );
    }
  }

  private final Database db;
  private final AuthService auth;
  private final SecretBox box;
  private final Json json;

  public EmbeddingConfiguration(
    Database db,
    AuthService auth,
    SecretBox box,
    Json json
  ) {
    this.db = db;
    this.auth = auth;
    this.box = box;
    this.json = json;
  }

  private Map<String, Object> row() {
    return db.one("SELECT * FROM knowledge_settings WHERE id=1");
  }

  private void admin(User u) {
    auth.admin(auth.refresh(u));
  }

  private Connection stored(Map<String, Object> r) {
    if (r.get("encrypted_payload") == null) return null;
    var p = json.read(box.decrypt(s(r, "encrypted_payload")));
    return new Connection(
      s(p, "base_url"),
      s(p, "model"),
      s(p, "api_key"),
      (int) id(p.get("dimensions")),
      s(r, "space_id"),
      id(r.get("revision"))
    );
  }

  private Map<String, Object> view(Map<String, Object> r) {
    var c = stored(r);
    return map(
      "base_url",
      c == null ? "" : c.baseUrl(),
      "model",
      c == null ? "" : c.model(),
      "dimensions",
      c == null ? 1024 : c.dimensions(),
      "has_key",
      c != null,
      "enabled",
      r.get("enabled"),
      "revision",
      r.get("revision"),
      "space_id",
      r.get("space_id"),
      "encrypted",
      r.get("encrypted_payload") != null
    );
  }

  public Object get(User u) {
    admin(u);
    return map("config", view(row()));
  }

  public Connection capture(User u) {
    admin(u);
    var r = row();
    return Boolean.TRUE.equals(r.get("enabled")) ? stored(r) : null;
  }

  public boolean current(Connection c) {
    var r = row();
    return (
      Boolean.TRUE.equals(r.get("enabled")) &&
      same(r.get("revision"), c.revision()) &&
      s(r, "space_id").equals(c.space())
    );
  }

  @Transactional
  public Object save(User u, Map<String, Object> d) {
    db.lock();
    admin(u);
    var r = row();
    version(d, r);
    if (!(d.get("enabled") instanceof Boolean)) throw new Problem(
      "请选择是否启用向量模型"
    );
    String base = ModelConfiguration.baseUrl(d.get("base_url"));
    if (base.endsWith("/embeddings")) throw new Problem(
      "填写基础地址，不包含 /embeddings"
    );
    String model = text(d.get("model"), "向量模型", 100, true);
    long dimension = id(d.get("dimensions"));
    if (dimension < 1 || dimension > 4096) throw new Problem(
      "向量维度须为1至4096"
    );
    String key = text(d.getOrDefault("api_key", ""), "API密钥", 4096, false);
    if (key.isBlank()) {
      var old = stored(r);
      if (old == null) throw new Problem("首次配置需要填写密钥");
      key = old.key();
    }
    if (!key.matches("[\\x21-\\x7e]{1,4096}")) throw new Problem(
      "密钥格式不正确"
    );
    String space = Passwords.digest(base + "\n" + model + "\n" + dimension);
    String encrypted = box.encrypt(
      json.write(
        map(
          "base_url",
          base,
          "model",
          model,
          "api_key",
          key,
          "dimensions",
          dimension
        )
      ),
      db.count(
        "SELECT (SELECT count(*) FROM ai_model_settings WHERE encrypted_payload IS NOT NULL)+(SELECT count(*) FROM knowledge_settings WHERE encrypted_payload IS NOT NULL)"
      ) == 0
    );
    db.update(
      "UPDATE knowledge_settings SET encrypted_payload=?,enabled=?,space_id=?,revision=revision+1,updated_by=?,updated_at=clock_timestamp() WHERE id=1",
      encrypted,
      d.get("enabled"),
      space,
      u.id()
    );
    db.audit(
      u.id(),
      "knowledge.config.save",
      map(
        "enabled",
        d.get("enabled"),
        "space_changed",
        !space.equals(r.get("space_id"))
      )
    );
    return map("config", view(row()));
  }

  @Transactional
  public Object clear(User u, Map<String, Object> d) {
    db.lock();
    admin(u);
    var r = row();
    version(d, r);
    db.update(
      "UPDATE knowledge_settings SET encrypted_payload=NULL,enabled=false,space_id='',revision=revision+1,updated_by=?,updated_at=clock_timestamp() WHERE id=1",
      u.id()
    );
    db.audit(u.id(), "knowledge.config.clear", map());
    return map("config", view(row()));
  }

  private void version(Map<String, Object> d, Map<String, Object> r) {
    if (!same(d.get("revision"), r.get("revision"))) throw new Problem(
      "向量配置已更新，请重新读取",
      409
    );
  }
}
