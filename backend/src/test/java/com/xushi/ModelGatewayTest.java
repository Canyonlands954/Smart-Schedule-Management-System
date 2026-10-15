package com.xushi;

import static com.xushi.common.Fields.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.xushi.ai.ModelConfiguration;
import com.xushi.ai.ModelGateway;
import com.xushi.common.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ModelGatewayTest {

  @Test
  void compatibleProtocolAndFailureHandling() throws Exception {
    Json json = new Json(new ObjectMapper());
    AtomicReference<String> reply = new AtomicReference<>(
      json.write(
        map(
          "choices",
          List.of(
            map(
              "message",
              map(
                "content",
                json.write(
                  map(
                    "title",
                    "工作会议",
                    "target_id",
                    2,
                    "warnings",
                    List.of()
                  )
                )
              )
            )
          )
        )
      )
    );
    AtomicReference<String> requestBody = new AtomicReference<>();
    HttpServer server = HttpServer.create(
      new InetSocketAddress("127.0.0.1", 0),
      0
    );
    server.createContext("/v1/chat/completions", exchange -> {
      requestBody.set(
        new String(
          exchange.getRequestBody().readAllBytes(),
          StandardCharsets.UTF_8
        )
      );
      byte[] response = reply.get().getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, response.length);
      exchange.getResponseBody().write(response);
      exchange.close();
    });
    server.start();
    try {
      ModelGateway gateway = new ModelGateway(json);
      var connection = new ModelConfiguration.Connection(
        "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
        "fixture-model",
        "test-key",
        1
      );
      var context = map(
        "users",
        List.of(map("id", 2, "name", "赵明")),
        "teams",
        List.of(),
        "history",
        List.of(),
        "text",
        "安排工作会议"
      );
      assertEquals("工作会议", gateway.parse(context, connection).get("title"));
      var payload = json.read(requestBody.get());
      assertEquals("fixture-model", payload.get("model"));
      assertEquals(
        "json_object",
        object(payload.get("response_format")).get("type")
      );
      assertFalse(requestBody.get().contains("test-key"));
      reply.set("{}");
      assertEquals(
        502,
        assertThrows(Problem.class, () ->
          gateway.parse(context, connection)
        ).status
      );
      reply.set(
        json.write(
          map("choices", List.of(map("message", map("content", "not json"))))
        )
      );
      assertThrows(Problem.class, () -> gateway.parse(context, connection));
    } finally {
      server.stop(0);
    }
  }
}
