package com.xushi.knowledge;

import static com.xushi.common.Fields.*;

import com.xushi.auth.*;
import com.xushi.common.Problem;
import com.xushi.data.Database;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeRetrievalService {

  public record Result(
    List<Map<String, Object>> chunks,
    String mode,
    String warning,
    long millis
  ) {}

  private final Database db;
  private final AuthService auth;
  private final KnowledgeRepository repo;
  private final EmbeddingConfiguration config;
  private final EmbeddingGateway gateway;

  public KnowledgeRetrievalService(
    Database db,
    AuthService auth,
    KnowledgeRepository repo,
    EmbeddingConfiguration config,
    EmbeddingGateway gateway
  ) {
    this.db = db;
    this.auth = auth;
    this.repo = repo;
    this.config = config;
    this.gateway = gateway;
  }

  public List<String> terms(String query) {
    var out = new LinkedHashSet<String>();
    var matcher = Pattern.compile("[\\p{IsHan}]{2,}|[a-zA-Z0-9_-]{2,}").matcher(
      query.toLowerCase(Locale.ROOT)
    );
    while (matcher.find() && out.size() < 48) {
      String word = matcher.group();
      if (word.matches("[\\p{IsHan}]+")) {
        if (word.length() <= 8) out.add(word);
        for (int i = 0; i < word.length() - 1 && out.size() < 48; i++) {
          String term = word.substring(i, i + 2);
          if (
            !Set.of(
              "什么",
              "怎么",
              "如何",
              "一个",
              "一下",
              "我们",
              "请问",
              "安排",
              "明天",
              "今天",
              "下午",
              "上午",
              "会议"
            ).contains(term)
          ) out.add(term);
        }
      } else out.add(word);
    }
    return new ArrayList<>(out);
  }

  public Result retrieve(
    User user,
    String question,
    Long base,
    boolean externalOnly,
    boolean semantic
  ) {
    long started = System.nanoTime();
    var u = auth.refresh(user);
    if (base != null) repo.base(u, base);
    var terms = terms(question);
    String score;
    if (terms.isEmpty()) score = "0";
    else {
      var parts = new ArrayList<String>();
      for (String t : terms) {
        parts.add(
          "(CASE WHEN position(? in lower(d.title))>0 THEN 4 ELSE 0 END + CASE WHEN position(? in lower(c.content))>0 THEN 1 ELSE 0 END)"
        );
      }
      score = String.join("+", parts);
    }
    String filter =
      " WHERE " +
      KnowledgeRepository.ACCESS +
      " AND " +
      KnowledgeRepository.LIVE +
      " AND (?::bigint IS NULL OR b.id=?) AND (NOT ? OR v.external_allowed)";
    // Score placeholders come before the scoped WHERE placeholders in this query.
    var keywordArgs = new ArrayList<Object>();
    for (String t : terms) {
      keywordArgs.add(t);
      keywordArgs.add(t);
    }
    keywordArgs.addAll(Arrays.asList(repo.args(u, base, base, externalOnly)));
    var keyword = db.rows(
      "SELECT * FROM (SELECT " +
        KnowledgeRepository.FIELDS +
        ",(" +
        score +
        ") keyword_score" +
        KnowledgeRepository.JOIN +
        filter +
        ") q WHERE keyword_score>0 ORDER BY keyword_score DESC,id LIMIT 24",
      keywordArgs.toArray()
    );
    List<Map<String, Object>> vector = List.of();
    String warning = "",
      mode = "keyword";
    if (u.admin() && semantic) {
      var c = config.capture(u);
      if (c != null) {
        try {
          var query = gateway.embed(List.of(question), c).get(0);
          auth.admin(auth.refresh(u));
          if (!config.current(c)) throw new Problem(
            "检索期间向量模型配置已变更，请重试",
            409
          );
          vector = db.rows(
            "SELECT " +
              KnowledgeRepository.FIELDS +
              ",c.embedding<=>?::vector distance" +
              KnowledgeRepository.JOIN +
              filter +
              " AND c.space_id=? AND c.embedding IS NOT NULL ORDER BY distance,c.id LIMIT 24",
            prepend(
              gateway.literal(query),
              repo.args(auth.refresh(u), base, base, externalOnly, c.space())
            )
          );
          // A conservative floor reduces unrelated vector-only evidence; tune with domain evaluation.
          vector = vector
            .stream()
            .filter(x -> ((Number) x.get("distance")).doubleValue() < 0.65)
            .toList();
          mode = "hybrid";
        } catch (Problem p) {
          if (p.status == 401 || p.status == 403 || p.status == 409) throw p;
          warning = "向量检索暂不可用，已使用关键词检索。";
        }
      }
    }
    var byId = new LinkedHashMap<Long, Map<String, Object>>();
    var scores = new HashMap<Long, Double>();
    for (var list : List.of(keyword, vector))
      for (int i = 0; i < list.size(); i++) {
        var row = list.get(i);
        long id = id(row.get("id"));
        byId.put(id, row);
        scores.merge(id, 1.0 / (60 + i + 1), Double::sum);
      }
    var ordered = new ArrayList<>(byId.values());
    ordered.sort(
      Comparator.<Map<String, Object>>comparingDouble(x ->
        scores.get(id(x.get("id")))
      )
        .reversed()
        .thenComparingLong(x -> id(x.get("id")))
    );
    var result = new ArrayList<Map<String, Object>>();
    var seen = new HashSet<String>();
    for (var row : ordered) {
      String key = row.get("version_id") + ":" + s(row, "content");
      if (seen.add(key)) result.add(row);
      if (result.size() == 6) break;
    }
    u = auth.refresh(u);
    if (
      !repo.valid(u, result.stream().map(repo::citation).toList(), externalOnly)
    ) throw new Problem("资料或访问权限发生变化，请重新检索", 409);
    return new Result(
      result,
      mode,
      warning,
      (System.nanoTime() - started) / 1000000
    );
  }

  private Object[] prepend(Object first, Object[] rest) {
    var a = new ArrayList<Object>();
    a.add(first);
    a.addAll(Arrays.asList(rest));
    return a.toArray();
  }
}
