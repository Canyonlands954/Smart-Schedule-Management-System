package com.xushi.knowledge;

import static com.xushi.common.Fields.*;

import com.xushi.auth.*;
import com.xushi.common.*;
import com.xushi.data.Database;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.sql.Date;
import java.time.LocalDate;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

@Service
public class KnowledgeService {

  private final Database db;
  private final AuthService auth;
  private final KnowledgeRepository repo;
  private final Path storage;
  private final TransactionTemplate tx;

  public KnowledgeService(
    Database db,
    AuthService auth,
    KnowledgeRepository repo,
    PlatformTransactionManager manager,
    @Value(
      "${schedule.knowledge.directory:.runtime/knowledge}"
    ) String directory
  ) {
    this.db = db;
    this.auth = auth;
    this.repo = repo;
    storage = Path.of(directory).toAbsolutePath().normalize();
    tx = new TransactionTemplate(manager);
  }

  private User admin(User u) {
    u = auth.refresh(u);
    auth.admin(u);
    return u;
  }

  public Object bases(User u) {
    u = auth.refresh(u);
    var rows = db.rows(
      "SELECT b.*, (SELECT count(*) FROM knowledge_documents d WHERE d.base_id=b.id AND NOT d.deleted" +
        (u.admin() ? "" : " AND d.published_version_id IS NOT NULL") +
        ") document_count FROM knowledge_bases b WHERE " +
        KnowledgeRepository.ACCESS +
        " ORDER BY b.id DESC LIMIT 200",
      repo.args(u)
    );
    if (u.admin()) for (var b : rows)
      b.put(
        "user_ids",
        db
          .rows(
            "SELECT user_id FROM knowledge_grants WHERE base_id=?",
            b.get("id")
          )
          .stream()
          .map(x -> x.get("user_id"))
          .toList()
      );
    return map("items", rows);
  }

  public Object saveBase(User user, Long baseId, Map<String, Object> input) {
    return tx.execute(status -> {
      db.lock();
      var u = admin(user);
      String name = text(input.get("name"), "知识库名称", 100, true),
        desc = text(input.getOrDefault("description", ""), "描述", 1000, false),
        scope = s(input, "scope");
      if (!Set.of("all", "team", "private").contains(scope)) throw new Problem(
        "请选择访问范围"
      );
      Long team = scope.equals("team")
        ? nullableId(input.get("team_id"))
        : null;
      if (
        scope.equals("team") &&
        (team == null ||
          db.count("SELECT count(*) FROM teams WHERE id=?", team) == 0)
      ) throw new Problem("请选择有效团队");
      if (
        !(input.getOrDefault("user_ids", List.of()) instanceof
          List<?> grants) ||
        grants.size() > 200
      ) throw new Problem("授权用户格式不正确");
      var users = new LinkedHashSet<Long>();
      for (var raw : grants) {
        long id = id(raw);
        if (
          db.count("SELECT count(*) FROM users WHERE id=? AND active=1", id) ==
          0
        ) throw new Problem("授权用户已停用或不存在");
        users.add(id);
      }
      long bid;
      if (baseId == null) bid = db.insert(
        "INSERT INTO knowledge_bases(name,description,scope,team_id,owner_id) VALUES(?,?,?,?,?)",
        name,
        desc,
        scope,
        team,
        u.id()
      );
      else {
        bid = baseId;
        var old = repo.base(u, bid);
        revision(input, old);
        db.update(
          "UPDATE knowledge_bases SET name=?,description=?,scope=?,team_id=?,revision=revision+1 WHERE id=?",
          name,
          desc,
          scope,
          team,
          bid
        );
        db.update("DELETE FROM knowledge_grants WHERE base_id=?", bid);
      }
      for (long uid : users)
        db.update(
          "INSERT INTO knowledge_grants(base_id,user_id) VALUES(?,?)",
          bid,
          uid
        );
      db.audit(
        u.id(),
        "knowledge.base.save",
        map("base_id", bid, "scope", scope)
      );
      return map("id", bid);
    });
  }

  public Object documents(User u, Long base) {
    u = auth.refresh(u);
    String sql =
      "SELECT d.*,b.name base_name,v.version,v.status,v.chunk_count,v.space_id,v.effective_from,v.effective_until,j.status job_status,j.error,j.attempts FROM knowledge_documents d JOIN knowledge_bases b ON b.id=d.base_id LEFT JOIN knowledge_versions v ON v.id=" +
      (u.admin()
        ? "(SELECT max(vv.id) FROM knowledge_versions vv WHERE vv.document_id=d.id)"
        : "d.published_version_id") +
      " LEFT JOIN knowledge_jobs j ON j.version_id=v.id WHERE " +
      KnowledgeRepository.ACCESS +
      " AND NOT d.deleted AND (?::bigint IS NULL OR b.id=?)";
    if (!u.admin()) sql += " AND " + KnowledgeRepository.LIVE;
    return map(
      "items",
      db.rows(sql + " ORDER BY d.id DESC LIMIT 300", repo.args(u, base, base))
    );
  }

  public Object versions(User user, long document) {
    var u = admin(user);
    repo.document(u, document);
    return map(
      "items",
      db.rows(
        "SELECT v.id,v.version,v.filename,v.status,v.chunk_count,v.space_id,v.effective_from,v.effective_until,v.external_allowed,v.created_at,j.status job_status,j.error,j.attempts,j.id job_id FROM knowledge_versions v JOIN knowledge_jobs j ON j.version_id=v.id WHERE v.document_id=? ORDER BY v.version DESC",
        document
      )
    );
  }

  public Object preview(User user, long version, int offset) {
    admin(user);
    if (offset < 0 || offset > 600) throw new Problem("页码不正确");
    versionFile(user, version);
    return map(
      "items",
      db.rows(
        "SELECT ordinal,locator,content FROM knowledge_chunks WHERE version_id=? ORDER BY ordinal LIMIT 20 OFFSET ?",
        version,
        offset
      ),
      "total",
      db.count(
        "SELECT count(*) FROM knowledge_chunks WHERE version_id=?",
        version
      )
    );
  }

  public Map<String, Object> versionFile(User user, long version) {
    admin(user);
    var v = db.one(
      "SELECT v.* FROM knowledge_versions v JOIN knowledge_documents d ON d.id=v.document_id WHERE v.id=? AND NOT d.deleted",
      version
    );
    if (v == null) throw new Problem("资料版本不存在", 404);
    return v;
  }

  public Path path(String key) {
    if (!key.matches("[0-9a-f-]{36}\\.(pdf|docx|md|txt)")) throw new Problem(
      "资料存储标识无效",
      500
    );
    Path p = storage.resolve(key).normalize();
    if (!p.startsWith(storage) || Files.isSymbolicLink(p)) throw new Problem(
      "资料存储不可访问",
      500
    );
    return p;
  }

  private void protect(Path p, boolean directory) throws Exception {
    if (
      Files.getFileStore(p).supportsFileAttributeView("posix")
    ) Files.setPosixFilePermissions(
      p,
      directory
        ? Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE
          )
        : Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE
          )
    );
    else {
      var acl = Files.getFileAttributeView(p, AclFileAttributeView.class);
      if (acl == null) throw new IllegalStateException();
      acl.setAcl(
        List.of(
          AclEntry.newBuilder()
            .setType(AclEntryType.ALLOW)
            .setPrincipal(Files.getOwner(p))
            .setPermissions(EnumSet.allOf(AclEntryPermission.class))
            .build()
        )
      );
    }
  }

  public Object upload(
    User user,
    Long document,
    MultipartFile file,
    Map<String, Object> input
  ) {
    var u = admin(user);
    if (file.isEmpty() || file.getSize() > 10 * 1024 * 1024) throw new Problem(
      "文件需非空且不超过10MB",
      413
    );
    String name = Objects.toString(
      file.getOriginalFilename(),
      "file.txt"
    ).replace('\\', '/');
    name = name
      .substring(name.lastIndexOf('/') + 1)
      .replaceAll("\\p{Cntrl}", "");
    if (
      name.length() > 180 ||
      !name.toLowerCase(Locale.ROOT).matches(".+\\.(pdf|docx|md|txt)")
    ) throw new Problem("支持PDF、DOCX、Markdown和TXT文件");
    String key =
        UUID.randomUUID() +
        name.substring(name.lastIndexOf('.')).toLowerCase(Locale.ROOT),
      filename = name;
    Path target = path(key);
    try {
      Files.createDirectories(storage);
      protect(storage, true);
      try (var stream = file.getInputStream()) {
        Files.copy(stream, target);
      }
      protect(target, false);
      byte[] bytes = Files.readAllBytes(target);
      String hash = HexFormat.of().formatHex(
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
      );
      return tx.execute(status -> {
        db.lock();
        admin(u);
        long did;
        if (document == null) {
          long bid = id(input.get("base_id"));
          repo.base(u, bid);
          did = db.insert(
            "INSERT INTO knowledge_documents(base_id,title,created_by) VALUES(?,?,?)",
            bid,
            text(input.get("title"), "资料标题", 150, true),
            u.id()
          );
        } else {
          did = document;
          revision(input, repo.document(u, did));
        }
        if (
          db.count(
            "SELECT count(*) FROM knowledge_versions WHERE document_id=? AND content_hash=?",
            did,
            hash
          ) > 0
        ) throw new Problem(
          "该资料已有相同内容；请使用原版本或上传修改后的文件",
          409
        );
        int number =
          1 +
          (int) db.count(
            "SELECT count(*) FROM knowledge_versions WHERE document_id=?",
            did
          );
        LocalDate from = date(
            input.getOrDefault("effective_from", LocalDate.now(ZONE).toString())
          ),
          until =
            input.get("effective_until") == null ||
            s(input, "effective_until").isBlank()
              ? null
              : date(input.get("effective_until"));
        if (until != null && until.isBefore(from)) throw new Problem(
          "失效日期不能早于生效日期"
        );
        long vid = db.insert(
          "INSERT INTO knowledge_versions(document_id,version,filename,storage_key,content_hash,effective_from,effective_until,external_allowed,created_by) VALUES(?,?,?,?,?,?,?,?,?)",
          did,
          number,
          filename,
          key,
          hash,
          Date.valueOf(from),
          until == null ? null : Date.valueOf(until),
          Boolean.TRUE.equals(input.get("external_allowed")),
          u.id()
        );
        db.update(
          "INSERT INTO knowledge_jobs(version_id,requested_by) VALUES(?,?)",
          vid,
          u.id()
        );
        db.update(
          "UPDATE knowledge_documents SET revision=revision+1 WHERE id=?",
          did
        );
        db.audit(
          u.id(),
          "knowledge.upload",
          map(
            "document_id",
            did,
            "version_id",
            vid,
            "external_allowed",
            Boolean.TRUE.equals(input.get("external_allowed"))
          )
        );
        return map("document_id", did, "version_id", vid, "status", "queued");
      });
    } catch (Exception e) {
      try {
        Files.deleteIfExists(target);
      } catch (Exception ignored) {}
      if (e instanceof Problem p) throw p;
      throw new Problem("资料保存失败，请检查文件存储目录或稍后重试", 500);
    }
  }

  private LocalDate date(Object raw) {
    try {
      return LocalDate.parse((String) raw);
    } catch (Exception e) {
      throw new Problem("日期格式应为YYYY-MM-DD");
    }
  }

  private void revision(Map<String, Object> d, Map<String, Object> current) {
    if (!same(d.get("revision"), current.get("revision"))) throw new Problem(
      "资料或权限已更新，请刷新后操作",
      409
    );
  }

  public Object action(
    User user,
    long document,
    String action,
    Map<String, Object> input
  ) {
    return tx.execute(status -> {
      db.lock();
      var u = admin(user);
      var d = repo.document(u, document);
      revision(input, d);
      if (action.equals("publish")) {
        long vid = id(input.get("version_id"));
        var v = db.one(
          "SELECT * FROM knowledge_versions WHERE id=? AND document_id=?",
          vid,
          document
        );
        if (
          v == null ||
          !"ready".equals(v.get("status")) ||
          id(v.get("chunk_count")) == 0
        ) throw new Problem("该版本尚未完成处理");
        LocalDate today = LocalDate.now(ZONE);
        if (
          date(v.get("effective_from").toString()).isAfter(today) ||
          (v.get("effective_until") != null &&
            date(v.get("effective_until").toString()).isBefore(today))
        ) throw new Problem("请在版本有效期内发布");
        db.update(
          "UPDATE knowledge_documents SET published_version_id=?,revision=revision+1 WHERE id=?",
          vid,
          document
        );
      } else if (Set.of("unpublish", "delete").contains(action)) db.update(
        "UPDATE knowledge_documents SET published_version_id=NULL,deleted=?,revision=revision+1 WHERE id=?",
        action.equals("delete"),
        document
      );
      else throw new Problem("无效操作");
      db.audit(
        u.id(),
        "knowledge." + action,
        map("document_id", document, "version_id", input.get("version_id"))
      );
      return map("ok", true);
    });
  }

  public Object retry(User user, long vid) {
    return tx.execute(status -> {
      db.lock();
      var u = admin(user);
      var v = db.one(
        "SELECT v.* FROM knowledge_versions v JOIN knowledge_documents d ON d.id=v.document_id WHERE v.id=? AND NOT d.deleted",
        vid
      );
      if (v == null) throw new Problem("资料版本不存在", 404);
      var job = db.one(
        "SELECT * FROM knowledge_jobs WHERE version_id=? FOR UPDATE",
        vid
      );
      if (
        Set.of("processing", "queued").contains(s(job, "status"))
      ) throw new Problem("任务正在处理或排队，请稍后查看", 409);
      db.update(
        "UPDATE knowledge_jobs SET status='queued',attempts=0,error='',available_at=now(),requested_by=?,updated_at=now() WHERE version_id=?",
        u.id(),
        vid
      );
      db.audit(u.id(), "knowledge.reindex", map("version_id", vid));
      return map("ok", true);
    });
  }

  public Map<String, Object> file(User u, long document) {
    u = auth.refresh(u);
    var d = repo.document(u, document);
    if (d.get("published_version_id") == null) throw new Problem(
      "资料尚未发布",
      404
    );
    var v = db.one(
      "SELECT v.* FROM knowledge_versions v JOIN knowledge_documents d ON d.id=v.document_id WHERE d.id=? AND " +
        KnowledgeRepository.LIVE,
      document
    );
    if (v == null) throw new Problem("资料已失效", 404);
    return v;
  }

  public Object stats(User u) {
    admin(u);
    return map(
      "queries",
      db.one(
        "SELECT count(*) total,coalesce(round(avg(retrieval_ms)),0) retrieval_ms,coalesce(round(avg(total_ms)),0) total_ms,count(*) FILTER(WHERE feedback=1) helpful,count(*) FILTER(WHERE feedback=-1) unhelpful,count(*) FILTER(WHERE mode='abstain') abstained,coalesce(sum((answer->'usage'->>'total_tokens')::bigint),0) answer_tokens,count(*) FILTER(WHERE answer->'usage'->>'total_tokens' IS NOT NULL) metered FROM knowledge_queries WHERE created_at>now()-interval '30 days'"
      ),
      "jobs",
      db.rows(
        "SELECT status,count(*) total FROM knowledge_jobs GROUP BY status"
      )
    );
  }
}
