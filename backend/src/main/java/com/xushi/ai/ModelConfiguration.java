package com.xushi.ai;

import static com.xushi.common.Fields.*;

import com.xushi.auth.*;
import com.xushi.common.*;
import com.xushi.data.Database;
import java.net.URI;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ModelConfiguration {

  public record Connection(
    String baseUrl,
    String model,
    String key,
    long revision
  ) {
    @Override
    public String toString() {
      return "ModelConnection[revision=" + revision + ", credentials=REDACTED]";
    }
  }

  private final Database db;
  private final AuthService auth;
  private final SecretBox box;
  private final Json json;
  private final String initialKey, initialBase, initialModel;

  public ModelConfiguration(
    Database db,
    AuthService auth,
    SecretBox box,
    Json json,
    @Value("${schedule.ai.key}") String key,
    @Value("${schedule.ai.base-url}") String base,
    @Value("${schedule.ai.model}") String model
  ) {
    this.db = db;
    this.auth = auth;
    this.box = box;
    this.json = json;
    initialKey = key;
    initialBase = base;
    initialModel = model;
  }

  private Map<String, Object> row() {
    return db.one("SELECT * FROM ai_model_settings WHERE id=1");
  }

  private User administrator(User u) {
    u = auth.refresh(u);
    auth.admin(u);
    return u;
  }

  private boolean enabled(Map<String, Object> row) {
    return id(row.get("revision")) == 0
      ? !initialKey.isBlank()
      : Boolean.TRUE.equals(row.get("enabled"));
  }

  public boolean enabled(User u) {
    return u.admin() && enabled(row());
  }

  private Connection stored(Map<String, Object> row) {
    if (row.get("encrypted_payload") != null) {
      var config = json.read(box.decrypt(s(row, "encrypted_payload")));
      return new Connection(
        s(config, "base_url"),
        s(config, "model"),
        s(config, "api_key"),
        id(row.get("revision"))
      );
    }
    if (
      id(row.get("revision")) == 0 && !initialKey.isBlank()
    ) return new Connection(initialBase, initialModel, initialKey, 0);
    return null;
  }

  @Transactional(readOnly = true)
  public Connection capture(User u) {
    administrator(u);
    var row = row();
    return enabled(row) ? stored(row) : null;
  }

  public boolean current(Connection c) {
    return id(row().get("revision")) == c.revision();
  }

  private Map<String, Object> view(Map<String, Object> row) {
    Connection c = stored(row);
    boolean preset = id(row.get("revision")) == 0 && !initialKey.isBlank();
    return map(
      "base_url",
      c == null ? "" : c.baseUrl(),
      "model",
      c == null ? "" : c.model(),
      "has_key",
      c != null,
      "enabled",
      preset || Boolean.TRUE.equals(row.get("enabled")),
      "encrypted",
      row.get("encrypted_payload") != null,
      "source",
      preset ? "environment" : "database",
      "revision",
      row.get("revision"),
      "updated_at",
      row.get("updated_at")
    );
  }

  @Transactional(readOnly = true)
  public Object get(User u) {
    administrator(u);
    return map("config", view(row()));
  }

  private void version(Map<String, Object> input, Map<String, Object> row) {
    if (!same(input.get("revision"), row.get("revision"))) throw new Problem(
      "模型配置已被其他管理员更新，请重新读取后操作",
      409
    );
  }

  public static String baseUrl(Object raw) {
    String value = text(raw, "接口基础地址", 500, true).replaceAll("/+$", "");
    try {
      URI uri = URI.create(value);
      String host = Objects.toString(uri.getHost(), "");
      boolean loopback = Set.of(
        "127.0.0.1",
        "localhost",
        "[::1]",
        "::1"
      ).contains(host.toLowerCase(Locale.ROOT));
      if (
        host.isBlank() ||
        uri.getUserInfo() != null ||
        uri.getRawQuery() != null ||
        uri.getRawFragment() != null ||
        uri.getPort() == 0 ||
        uri.getPort() > 65535 ||
        !(
          "https".equals(uri.getScheme()) ||
          ("http".equals(uri.getScheme()) && loopback)
        ) ||
        value.endsWith("/chat/completions")
      ) throw new IllegalArgumentException();
      return value;
    } catch (Exception e) {
      throw new Problem(
        "请填写HTTPS接口基础地址（不含/chat/completions、账号或查询参数）；本机服务可使用HTTP。 "
      );
    }
  }

  @Transactional
  public Object save(User u, Map<String, Object> input) {
    db.lock();
    u = administrator(u);
    var row = row();
    version(input, row);
    if (!(input.get("enabled") instanceof Boolean)) throw new Problem(
      "请明确选择是否启用模型接口"
    );
    String base = baseUrl(input.get("base_url")),
      model = text(input.get("model"), "模型名称", 100, true);
    String key = text(
      input.getOrDefault("api_key", ""),
      "API密钥",
      4096,
      false
    );
    if (key.isBlank()) {
      var previous = stored(row);
      if (previous == null) throw new Problem("首次配置需要填写API密钥");
      key = previous.key();
    }
    if (!key.matches("[\\x21-\\x7e]{1,4096}")) throw new Problem(
      "API密钥不能包含空格、换行或非ASCII字符"
    );
    String encrypted = box.encrypt(
      json.write(map("base_url", base, "model", model, "api_key", key)),
      row.get("encrypted_payload") == null
    );
    db.update(
      "UPDATE ai_model_settings SET encrypted_payload=?,enabled=?,revision=revision+1,updated_by=?,updated_at=clock_timestamp() WHERE id=1",
      encrypted,
      input.get("enabled"),
      u.id()
    );
    db.audit(
      u.id(),
      "ai.config.save",
      map(
        "enabled",
        input.get("enabled"),
        "key_replaced",
        !s(input, "api_key").isBlank()
      )
    );
    return map("config", view(row()));
  }

  @Transactional
  public Object clear(User u, Map<String, Object> input) {
    db.lock();
    u = administrator(u);
    var row = row();
    version(input, row);
    db.update(
      "UPDATE ai_model_settings SET encrypted_payload=NULL,enabled=false,revision=revision+1,updated_by=?,updated_at=clock_timestamp() WHERE id=1",
      u.id()
    );
    db.audit(u.id(), "ai.config.clear", map());
    return map("config", view(row()));
  }
}
