package com.xushi.auth;

import static com.xushi.common.Fields.*;

import com.xushi.common.*;
import com.xushi.data.Database;
import jakarta.servlet.http.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

  private final Database db;
  private final Passwords passwords;
  private final boolean secure;
  private final Map<String, List<Long>> attempts = new ConcurrentHashMap<>();

  public AuthService(
    Database db,
    Passwords passwords,
    @Value("${schedule.secure-cookie}") boolean secure
  ) {
    this.db = db;
    this.passwords = passwords;
    this.secure = secure;
  }

  public String cookie(String token) {
    return ResponseCookie.from("schedule_session", token)
      .httpOnly(true)
      .secure(secure)
      .sameSite("Lax")
      .path("/")
      .maxAge(token.isEmpty() ? 0 : 43200)
      .build()
      .toString();
  }

  public String token(HttpServletRequest r) {
    if (r.getCookies() != null) for (Cookie c : r.getCookies())
      if (c.getName().equals("schedule_session")) return c.getValue();
    return "";
  }

  public User require(HttpServletRequest r) {
    var row = db.one(
      "SELECT u.*, EXISTS(SELECT 1 FROM teams t WHERE t.id=u.team_id AND t.leader_id=u.id) is_leader FROM users u JOIN sessions s ON s.user_id=u.id WHERE s.token_hash=? AND s.expires>? AND u.active=1",
      Passwords.digest(token(r)),
      System.currentTimeMillis() / 1000.0
    );
    if (row == null) throw new Problem("请先登录", 401);
    return user(row);
  }

  public User refresh(User u) {
    var row = db.one(
      "SELECT u.*, EXISTS(SELECT 1 FROM teams t WHERE t.id=u.team_id AND t.leader_id=u.id) is_leader FROM users u WHERE u.id=? AND u.active=1",
      u.id()
    );
    if (row == null) throw new Problem("账号已停用", 401);
    return user(row);
  }

  private User user(Map<String, Object> r) {
    return new User(
      id(r.get("id")),
      s(r, "username"),
      s(r, "name"),
      s(r, "role"),
      nullableId(r.get("team_id")),
      Boolean.TRUE.equals(r.get("is_leader"))
    );
  }

  public void admin(User user) {
    if (!user.admin()) throw new Problem("此操作需要管理员权限", 403);
  }

  public boolean initialized() {
    return db.count("SELECT count(*) FROM users") > 0;
  }

  @Transactional
  public long register(Map<String, Object> d, String address, boolean setup) {
    db.lock();
    boolean initialized = initialized();
    String role = setup ? "admin" : s(d, "role");
    if (role.isEmpty()) role = "member";
    if (!Set.of("admin", "member").contains(role)) throw new Problem(
      "注册类型不正确"
    );
    if (
      !setup && !Objects.equals(d.get("password"), d.get("confirm"))
    ) throw new Problem("两次密码输入不一致");
    String hash = null;
    if (!initialized) {
      if (!role.equals("admin")) throw new Problem("请先注册首位管理员");
      if (
        !address.equals("127.0.0.1") &&
        !address.equals("0:0:0:0:0:0:0:1") &&
        !address.equals("::1")
      ) throw new Problem("首位管理员须在服务器本机注册", 403);
    } else {
      if (setup) throw new Problem("系统已初始化", 409);
      if (role.equals("admin")) {
        hash = Passwords.digest(
          text(d.get("invite_code"), "邀请码", 200, true)
        );
        if (
          db.one(
            "SELECT * FROM admin_invites WHERE token_hash=? AND used_by IS NULL AND expires>? FOR UPDATE",
            hash,
            System.currentTimeMillis() / 1000.0
          ) == null
        ) throw new Problem("管理员邀请码无效、已使用或已过期", 403);
      }
    }
    long id = createUser(d, role, null);
    if (hash != null) db.update(
      "UPDATE admin_invites SET used_by=? WHERE token_hash=?",
      id,
      hash
    );
    db.audit(id, "user.register", map("role", role));
    return id;
  }

  public long createUser(Map<String, Object> d, String role, Long team) {
    String username = text(d.get("username"), "账号", 50, true);
    if (!username.matches("[a-zA-Z0-9_.-]{3,50}")) throw new Problem(
      "账号须为3–50位字母、数字、下划线、点或短横线"
    );
    return db.insert(
      "INSERT INTO users(username,name,password,role,team_id) VALUES(?,?,?,?,?)",
      username,
      text(d.get("name"), "姓名", 50, true),
      passwords.encode(d.get("password")),
      role,
      team
    );
  }

  @Transactional
  public Map<String, Object> login(
    Map<String, Object> d,
    String ip,
    HttpServletResponse response
  ) {
    db.lock();
    long now = System.currentTimeMillis();
    attempts
      .entrySet()
      .removeIf(
        e ->
          e.getValue().isEmpty() ||
          e.getValue().get(e.getValue().size() - 1) < now - 300000
      );
    var list = attempts.computeIfAbsent(ip, k ->
      Collections.synchronizedList(new ArrayList<>())
    );
    synchronized (list) {
      list.removeIf(t -> t < now - 300000);
      if (list.size() >= 15) throw new Problem(
        "尝试次数过多，请5分钟后再试",
        429
      );
      var row = db.one(
        "SELECT * FROM users WHERE username=? AND active=1",
        text(d.get("username"), "账号", 50, true)
      );
      if (
        row == null || !passwords.matches(d.get("password"), s(row, "password"))
      ) {
        list.add(now);
        throw new Problem("账号或密码不正确", 401);
      }
      attempts.remove(ip);
      long id = id(row.get("id"));
      if (!s(row, "password").startsWith("{pbkdf2}")) db.update(
        "UPDATE users SET password=? WHERE id=?",
        passwords.encode(d.get("password")),
        id
      );
      String token = Passwords.token();
      db.update("DELETE FROM sessions WHERE expires<?", now / 1000.0);
      db.update(
        "INSERT INTO sessions VALUES(?,?,?)",
        Passwords.digest(token),
        id,
        now / 1000.0 + 43200
      );
      response.addHeader("Set-Cookie", cookie(token));
      return map("user", refresh(user(row)).publicView());
    }
  }

  @Transactional
  public void logout(HttpServletRequest r, HttpServletResponse response) {
    db.update(
      "DELETE FROM sessions WHERE token_hash=?",
      Passwords.digest(token(r))
    );
    response.addHeader("Set-Cookie", cookie(""));
  }

  @Transactional
  public void changePassword(
    User u,
    Map<String, Object> d,
    HttpServletResponse response
  ) {
    db.lock();
    var row = db.one("SELECT password FROM users WHERE id=?", u.id());
    if (
      !passwords.matches(d.get("old_password"), s(row, "password"))
    ) throw new Problem("原密码不正确", 403);
    db.update(
      "UPDATE users SET password=? WHERE id=?",
      passwords.encode(d.get("new_password")),
      u.id()
    );
    db.update("DELETE FROM sessions WHERE user_id=?", u.id());
    db.audit(u.id(), "password.change", map());
    response.addHeader("Set-Cookie", cookie(""));
  }

  @Transactional
  public Map<String, Object> invite(User u) {
    db.lock();
    admin(refresh(u));
    String code = Passwords.token();
    double expiry = System.currentTimeMillis() / 1000.0 + 86400;
    db.update(
      "INSERT INTO admin_invites VALUES(?,?,?,NULL)",
      Passwords.digest(code),
      u.id(),
      expiry
    );
    db.audit(u.id(), "admin.invite.create", map("expires", expiry));
    return map(
      "code",
      code,
      "expires_at",
      java.time.Instant.ofEpochSecond((long) expiry)
        .atOffset(ZONE)
        .toString()
    );
  }
}
