package com.xushi.knowledge;

import static com.xushi.common.Fields.*;

import com.xushi.ai.*;
import com.xushi.auth.*;
import com.xushi.common.*;
import com.xushi.data.Database;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class KnowledgeAnswerService {

  private final Database db;
  private final AuthService auth;
  private final KnowledgeRepository repo;
  private final KnowledgeRetrievalService retrieval;
  private final ModelConfiguration config;
  private final ModelGateway model;
  private final Json json;
  private final TransactionTemplate tx;

  public KnowledgeAnswerService(
    Database db,
    AuthService auth,
    KnowledgeRepository repo,
    KnowledgeRetrievalService retrieval,
    ModelConfiguration config,
    ModelGateway model,
    Json json,
    PlatformTransactionManager manager
  ) {
    this.db = db;
    this.auth = auth;
    this.repo = repo;
    this.retrieval = retrieval;
    this.config = config;
    this.model = model;
    this.json = json;
    tx = new TransactionTemplate(manager);
  }

  public Object search(HttpServletRequest request, Map<String, Object> input) {
    var u = auth.require(request);
    String question = text(input.get("question"), "检索内容", 2000, true);
    var result = retrieval.retrieve(
      u,
      question,
      nullableId(input.get("base_id")),
      false,
      u.admin() && Boolean.TRUE.equals(input.get("semantic"))
    );
    var current = auth.require(request);
    if (
      current.id() != u.id() ||
      !repo.valid(
        current,
        result.chunks().stream().map(repo::citation).toList(),
        false
      )
    ) throw new Problem("权限或资料已变更，请重新检索", 409);
    var items = result
      .chunks()
      .stream()
      .map(c -> {
        var view = repo.citation(c);
        view.put("excerpt", s(c, "content"));
        view.put("external_allowed", c.get("external_allowed"));
        return view;
      })
      .toList();
    return map(
      "items",
      items,
      "mode",
      result.mode(),
      "warning",
      result.warning(),
      "retrieval_ms",
      result.millis()
    );
  }

  public Map<String, Object> ask(
    HttpServletRequest request,
    Map<String, Object> input
  ) {
    long started = System.nanoTime();
    var u = auth.require(request);
    auth.admin(u);
    String question = text(input.get("question"), "问题", 2000, true);
    String query = question;
    if (!s(input, "previous_query_id").isBlank()) {
      var previous = owned(u, s(input, "previous_query_id"));
      if (
        !repo.valid(u, objects(previous.get("citations")), true)
      ) throw new Problem("上轮资料已变更，请重新提问", 409);
      String earlier = s(previous, "question");
      if (earlier.length() > 4000) earlier =
        earlier.substring(0, 2000) +
        "\n" +
        earlier.substring(earlier.length() - 2000);
      query = earlier + "\n本轮问题：" + question;
    }
    var found = retrieval.retrieve(
      u,
      query,
      nullableId(input.get("base_id")),
      true,
      true
    );
    var citations = found.chunks().stream().map(repo::citation).toList();
    var connection = config.capture(u);
    Map<String, Object> answer = map(
      "statements",
      List.of(),
      "suggestions",
      List.of()
    );
    String mode = "abstain",
      message =
        "未找到足够的有效资料。请补充关键词或上传相关资料；未获准发送给模型的资料可在“资料检索”中查看原文。";
    Map<String, Object> usage = map();
    if (!found.chunks().isEmpty()) {
      if (connection == null) {
        mode = "excerpts";
        message = "尚未启用问答模型，以下为相关原文。";
      } else {
        var context = map(
          "question",
          question,
          "previous_question",
          query.equals(question) ? "" : query,
          "intent",
          s(input, "intent"),
          "evidence",
          found
            .chunks()
            .stream()
            .map(c ->
              map(
                "source_id",
                c.get("id"),
                "title",
                c.get("title"),
                "locator",
                c.get("locator"),
                "content",
                c.get("content")
              )
            )
            .toList()
        );
        if ("schedule".equals(input.get("intent"))) context.put(
          "business_context",
          map(
            "time",
            now(),
            "rooms",
            db.rows("SELECT name,capacity FROM rooms ORDER BY id"),
            "policy",
            db.one("SELECT settings FROM scheduling_policy WHERE id=1"),
            "rule",
            "只向明确的单个接收人派发，日期或时长不明需追问；忙闲和冲突由日程服务核对"
          )
        );
        try {
          var before = auth.require(request);
          auth.admin(before);
          if (
            before.id() != u.id() || !repo.valid(before, citations, true)
          ) throw new Problem("资料或权限已变更", 409);
          var generated = model.answer(context, connection);
          usage = object(generated.get("_usage"));
          answer = validate(generated, found.chunks());
          if (!objects(answer.get("statements")).isEmpty()) {
            mode = "answer";
            message = "回答依据以下已发布资料，日程建议需另行确认。";
          } else {
            answer = map("statements", List.of(), "suggestions", List.of());
            message =
              "检索到了相关资料，但不足以支持可靠回答，请查看原文或补充问题。";
          }
        } catch (Exception p) {
          mode = "excerpts";
          answer = map("statements", List.of(), "suggestions", List.of());
          message = "问答模型暂不可用或缺少可核实引用，以下原文仍可查看。";
        }
      }
    }
    final var output = answer;
    final var tokenUsage = usage;
    final String resolvedQuestion = query;
    var used = new HashSet<Long>();
    for (var item : objects(output.get("statements")))
      used.add(id(item.get("source_id")));
    for (var item : objects(output.get("suggestions")))
      used.add(id(item.get("source_id")));
    final var cited = mode.equals("answer")
      ? citations
          .stream()
          .filter(c -> used.contains(id(c.get("id"))))
          .toList()
      : citations;
    final String finalMode = mode,
      finalMessage = message;
    return tx.execute(status -> {
      db.lock();
      var current = auth.require(request);
      auth.admin(current);
      if (
        current.id() != u.id() || !repo.valid(current, citations, true)
      ) throw new Problem("资料或权限已变化，请重新提问", 409);
      if (connection != null && !config.current(connection)) throw new Problem(
        "问答模型配置已更新，请重试",
        409
      );
      UUID id = UUID.randomUUID();
      var stored = map(
        "statements",
        output.get("statements"),
        "suggestions",
        output.get("suggestions"),
        "message",
        finalMessage,
        "usage",
        tokenUsage
      );
      db.update(
        "INSERT INTO knowledge_queries(id,owner_id,question,answer,citations,mode,retrieval_ms,total_ms) VALUES(?,?,?,?::jsonb,?::jsonb,?,?,?)",
        id,
        u.id(),
        resolvedQuestion,
        json.write(stored),
        json.write(cited),
        finalMode,
        found.millis(),
        (System.nanoTime() - started) / 1000000
      );
      db.audit(
        u.id(),
        "knowledge.ask",
        map(
          "query_id",
          id.toString(),
          "source_count",
          cited.size(),
          "mode",
          finalMode
        )
      );
      return view(id.toString(), stored, cited, finalMode, found.warning());
    });
  }

  public Map<String, Object> validate(
    Map<String, Object> raw,
    List<Map<String, Object>> chunks
  ) {
    var statements = new ArrayList<Map<String, Object>>();
    var suggestions = new ArrayList<Map<String, Object>>();
    for (var entry : objects(raw.get("statements"))) {
      if (statements.size() == 6) break;
      var source = source(entry, chunks);
      if (source == null) continue;
      String text = text(entry.get("text"), "回答", 1200, true);
      statements.add(
        map(
          "text",
          text,
          "source_id",
          source.get("id"),
          "quote",
          text(entry.get("quote"), "引用", 800, true)
        )
      );
    }
    for (var entry : objects(raw.get("suggestions"))) {
      if (suggestions.size() == 5) break;
      var source = source(entry, chunks);
      String kind = s(entry, "kind");
      if (
        source == null ||
        !Set.of("duration", "checklist", "notes").contains(kind)
      ) continue;
      Object value;
      if (kind.equals("duration")) {
        long minutes = id(entry.get("value"));
        if (minutes < 15 || minutes > 480) continue;
        value = minutes;
      } else value = text(
        entry.get("value"),
        "建议",
        kind.equals("checklist") ? 100 : 1000,
        true
      );
      suggestions.add(
        map(
          "kind",
          kind,
          "value",
          value,
          "source_id",
          source.get("id"),
          "quote",
          s(entry, "quote")
        )
      );
    }
    return map("statements", statements, "suggestions", suggestions);
  }

  private Map<String, Object> source(
    Map<String, Object> entry,
    List<Map<String, Object>> chunks
  ) {
    if (
      !(entry.get("source_id") instanceof Number n) ||
      n.doubleValue() != n.longValue() ||
      s(entry, "quote").isBlank() ||
      s(entry, "quote").length() > 800
    ) return null;
    return chunks
      .stream()
      .filter(
        c ->
          same(c.get("id"), entry.get("source_id")) &&
          s(c, "content").contains(s(entry, "quote"))
      )
      .findFirst()
      .orElse(null);
  }

  private Map<String, Object> owned(User u, String id) {
    UUID uuid;
    try {
      uuid = UUID.fromString(id);
    } catch (Exception e) {
      throw new Problem("问答记录不存在", 404);
    }
    var r = db.one(
      "SELECT * FROM knowledge_queries WHERE id=? AND owner_id=?",
      uuid,
      u.id()
    );
    if (r == null) throw new Problem("问答记录不存在或无权访问", 404);
    return r;
  }

  private Map<String, Object> view(
    String id,
    Map<String, Object> answer,
    List<Map<String, Object>> citations,
    String mode,
    String warning
  ) {
    var out = new LinkedHashMap<>(answer);
    out.putAll(
      map(
        "query_id",
        id,
        "citations",
        citations,
        "mode",
        mode,
        "warning",
        warning
      )
    );
    return out;
  }

  public Map<String, Object> load(User user, String id) {
    var u = auth.refresh(user);
    auth.admin(u);
    var r = owned(u, id);
    var cites = objects(r.get("citations"));
    if (!repo.valid(u, cites, true)) return map(
      "query_id",
      id,
      "mode",
      "revoked",
      "statements",
      List.of(),
      "suggestions",
      List.of(),
      "citations",
      List.of(),
      "message",
      "资料已更新、停用或访问权限发生变化，请重新提问。"
    );
    return view(id, object(r.get("answer")), cites, s(r, "mode"), "");
  }

  public Object feedback(User u, String id, Map<String, Object> input) {
    auth.admin(auth.refresh(u));
    owned(u, id);
    Object v = input.get("value");
    if (
      !(v instanceof Number n) ||
      (n.intValue() != 1 && n.intValue() != -1) ||
      n.doubleValue() != n.intValue()
    ) throw new Problem("反馈格式不正确");
    db.update(
      "UPDATE knowledge_queries SET feedback=? WHERE id=? AND owner_id=?",
      ((Number) v).intValue(),
      UUID.fromString(id),
      u.id()
    );
    return map("ok", true);
  }
}
