package com.xushi.knowledge;

import static com.xushi.common.Fields.*;

import com.xushi.auth.*;
import com.xushi.common.Problem;
import com.xushi.data.Database;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@EnableScheduling
public class DocumentIngestionService {

  private final Database db;
  private final AuthService auth;
  private final KnowledgeService files;
  private final DocumentParser parser;
  private final EmbeddingConfiguration config;
  private final EmbeddingGateway gateway;
  private final TransactionTemplate tx;
  private final boolean enabled;

  public DocumentIngestionService(
    Database db,
    AuthService auth,
    KnowledgeService files,
    DocumentParser parser,
    EmbeddingConfiguration config,
    EmbeddingGateway gateway,
    PlatformTransactionManager manager,
    @Value("${schedule.knowledge.worker-enabled:true}") boolean enabled
  ) {
    this.db = db;
    this.auth = auth;
    this.files = files;
    this.parser = parser;
    this.config = config;
    this.gateway = gateway;
    tx = new TransactionTemplate(manager);
    this.enabled = enabled;
  }

  @Scheduled(fixedDelay = 2000, initialDelay = 4000)
  public void tick() {
    if (!enabled) return;
    try {
      runOne();
    } catch (Exception ignored) {
      /* A failed claim will be retried after its lease. No private input is logged. */
    }
  }

  public boolean runOne() {
    UUID token = UUID.randomUUID();
    var job = tx.execute(status -> {
      var j = db.one(
        "SELECT j.*,v.storage_key,v.filename,v.external_allowed,v.document_id FROM knowledge_jobs j JOIN knowledge_versions v ON v.id=j.version_id JOIN knowledge_documents d ON d.id=v.document_id WHERE NOT d.deleted AND ((j.status='queued' AND j.available_at<=now()) OR (j.status='processing' AND j.lease_until<now())) ORDER BY j.id LIMIT 1 FOR UPDATE OF j SKIP LOCKED"
      );
      if (j == null) return null;
      if (id(j.get("attempts")) >= 3) {
        db.update(
          "UPDATE knowledge_jobs SET status='failed',error='任务多次中断，请重试',lease_token=NULL WHERE id=?",
          j.get("id")
        );
        db.update(
          "UPDATE knowledge_versions SET status='failed' WHERE id=? AND chunk_count=0",
          j.get("version_id")
        );
        return null;
      }
      db.update(
        "UPDATE knowledge_jobs SET status='processing',attempts=attempts+1,lease_until=now()+interval '3 minutes',lease_token=?,updated_at=now() WHERE id=?",
        token,
        j.get("id")
      );
      db.update(
        "UPDATE knowledge_versions SET status='processing' WHERE id=? AND chunk_count=0",
        j.get("version_id")
      );
      return j;
    });
    if (job == null) return false;
    try {
      long uid = id(job.get("requested_by"));
      var raw = db.one(
        "SELECT id,username,name,role,team_id FROM users WHERE id=? AND active=1",
        uid
      );
      if (raw == null) throw new Problem("发起人账号已停用");
      var user = new User(
        uid,
        s(raw, "username"),
        s(raw, "name"),
        s(raw, "role"),
        nullableId(raw.get("team_id")),
        false
      );
      auth.admin(auth.refresh(user));
      var chunks = parser.parse(
        files.path(s(job, "storage_key")),
        s(job, "filename")
      );
      var c = Boolean.TRUE.equals(job.get("external_allowed"))
        ? config.capture(user)
        : null;
      var vectors = new ArrayList<List<Double>>();
      if (c != null) for (int start = 0; start < chunks.size(); start += 16) {
        auth.admin(auth.refresh(user));
        if (!config.current(c)) throw new Problem(
          "处理期间向量模型配置发生变化，请重新处理",
          409
        );
        var batch = chunks
          .subList(start, Math.min(start + 16, chunks.size()))
          .stream()
          .map(DocumentParser.Chunk::content)
          .toList();
        heartbeat(job, token);
        vectors.addAll(gateway.embed(batch, c));
      }
      tx.execute(status -> {
        db.lock();
        auth.admin(auth.refresh(user));
        if (c != null && !config.current(c)) throw new Problem(
          "处理期间向量配置已变更，请重新处理",
          409
        );
        var current = db.one(
          "SELECT j.id FROM knowledge_jobs j JOIN knowledge_versions v ON v.id=j.version_id JOIN knowledge_documents d ON d.id=v.document_id WHERE j.id=? AND j.lease_token=? AND j.status='processing' AND NOT d.deleted FOR UPDATE OF j",
          job.get("id"),
          token
        );
        if (current == null) throw new Problem("任务已被替换或资料已删除", 409);
        db.update(
          "DELETE FROM knowledge_chunks WHERE version_id=?",
          job.get("version_id")
        );
        for (int i = 0; i < chunks.size(); i++) {
          var chunk = chunks.get(i);
          db.update(
            "INSERT INTO knowledge_chunks(version_id,ordinal,locator,content,embedding,space_id) VALUES(?,?,?,?,?::vector,?)",
            job.get("version_id"),
            i,
            chunk.locator(),
            chunk.content(),
            c == null ? null : gateway.literal(vectors.get(i)),
            c == null ? "" : c.space()
          );
        }
        db.update(
          "UPDATE knowledge_versions SET status='ready',chunk_count=?,space_id=? WHERE id=?",
          chunks.size(),
          c == null ? "" : c.space(),
          job.get("version_id")
        );
        db.update(
          "UPDATE knowledge_jobs SET status='done',error='',lease_token=NULL,lease_until=NULL,updated_at=now() WHERE id=?",
          job.get("id")
        );
        // Revision invalidates old citations atomically when a published version is re-indexed.
        db.update(
          "UPDATE knowledge_documents SET revision=revision+1 WHERE id=?",
          job.get("document_id")
        );
        db.audit(
          uid,
          "knowledge.ingested",
          map(
            "version_id",
            job.get("version_id"),
            "chunks",
            chunks.size(),
            "vectorized",
            c != null
          )
        );
        return null;
      });
    } catch (Exception e) {
      String message =
        e instanceof Problem
          ? e.getMessage()
          : "处理失败，请检查文档或模型配置后重试";
      tx.execute(status -> {
        var current = db.one(
          "SELECT attempts FROM knowledge_jobs WHERE id=? AND lease_token=? FOR UPDATE",
          job.get("id"),
          token
        );
        if (current != null) {
          boolean again =
            e instanceof Problem p &&
            p.status == 502 &&
            id(current.get("attempts")) < 3;
          db.update(
            "UPDATE knowledge_jobs SET status=?,error=?,available_at=now()+interval '15 seconds',lease_until=NULL,lease_token=NULL,updated_at=now() WHERE id=?",
            again ? "queued" : "failed",
            message,
            job.get("id")
          );
          db.update(
            "UPDATE knowledge_versions SET status=? WHERE id=? AND chunk_count=0",
            again ? "queued" : "failed",
            job.get("version_id")
          );
        }
        return null;
      });
    }
    return true;
  }

  private void heartbeat(Map<String, Object> job, UUID token) {
    if (
      db.update(
        "UPDATE knowledge_jobs j SET lease_until=now()+interval '3 minutes' WHERE j.id=? AND j.lease_token=? AND j.status='processing' AND EXISTS(SELECT 1 FROM knowledge_versions v JOIN knowledge_documents d ON d.id=v.document_id WHERE v.id=j.version_id AND NOT d.deleted AND v.external_allowed)",
        job.get("id"),
        token
      ) != 1
    ) throw new Problem("任务已被其他工作进程接管", 409);
  }
}
