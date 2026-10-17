package com.xushi.knowledge;

import static com.xushi.common.Fields.*;

import com.xushi.common.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class EmbeddingGateway {

  private final Json json;
  private final HttpClient http = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(10))
    .followRedirects(HttpClient.Redirect.NEVER)
    .build();

  public EmbeddingGateway(Json json) {
    this.json = json;
  }

  public List<List<Double>> embed(
    List<String> texts,
    EmbeddingConfiguration.Connection c
  ) {
    if (texts.isEmpty() || texts.size() > 16) throw new Problem(
      "向量批次数量不正确"
    );
    try {
      var request = HttpRequest.newBuilder(
        URI.create(c.baseUrl() + "/embeddings")
      )
        .timeout(Duration.ofSeconds(35))
        .header("Authorization", "Bearer " + c.key())
        .header("Content-Type", "application/json")
        .POST(
          HttpRequest.BodyPublishers.ofString(
            json.write(
              map(
                "model",
                c.model(),
                "input",
                texts,
                "encoding_format",
                "float"
              )
            )
          )
        )
        .build();
      // Do not log provider responses: they may echo credentials or private source text.
      var response = http.send(request, HttpPayload.limited(4 * 1024 * 1024));
      byte[] bytes = response.body();
      if (
        response.statusCode() != 200 || bytes.length > 4 * 1024 * 1024
      ) throw new IllegalArgumentException();
      var rows = objects(
        json
          .read(new String(bytes, java.nio.charset.StandardCharsets.UTF_8))
          .get("data")
      );
      if (rows.size() != texts.size()) throw new IllegalArgumentException();
      List<List<Double>> ordered = new ArrayList<>(
        Collections.nCopies(texts.size(), null)
      );
      for (var row : rows) {
        int index = (int) id(row.get("index"));
        if (
          index >= texts.size() ||
          ordered.get(index) != null ||
          !(row.get("embedding") instanceof List<?> raw) ||
          raw.size() != c.dimensions()
        ) throw new IllegalArgumentException();
        var v = new ArrayList<Double>();
        double norm = 0;
        for (var x : raw) {
          if (
            !(x instanceof Number n) || !Double.isFinite(n.doubleValue())
          ) throw new IllegalArgumentException();
          double d = n.doubleValue();
          v.add(d);
          norm += d * d;
        }
        if (
          !Double.isFinite(norm) || norm == 0
        ) throw new IllegalArgumentException();
        ordered.set(index, v);
      }
      return ordered;
    } catch (Exception e) {
      throw new Problem(
        "向量接口不可用或返回维度不匹配，请检查配置后重试。",
        502
      );
    }
  }

  public String literal(List<Double> v) {
    return json.write(v);
  }
}
