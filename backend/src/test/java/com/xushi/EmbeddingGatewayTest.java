package com.xushi;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.xushi.common.*;
import com.xushi.knowledge.*;
import java.net.*;
import java.util.*;
import org.junit.jupiter.api.*;

class EmbeddingGatewayTest {

  final Json json = new Json(new ObjectMapper());
  HttpServer server;
  String body;
  Map<String, Object> seen;
  String authorization;

  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/v1/embeddings", exchange -> {
      authorization = exchange.getRequestHeaders().getFirst("Authorization");
      seen = json.read(
        new String(
          exchange.getRequestBody().readAllBytes(),
          java.nio.charset.StandardCharsets.UTF_8
        )
      );
      byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, bytes.length);
      exchange.getResponseBody().write(bytes);
      exchange.close();
    });
    server.start();
  }

  @AfterEach
  void close() {
    server.stop(0);
  }

  EmbeddingConfiguration.Connection connection() {
    return new EmbeddingConfiguration.Connection(
      "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
      "test-vector",
      "unit-secret",
      3,
      "space",
      1
    );
  }

  @Test
  void validatesAndOrdersProviderVectors() {
    body =
      "{\"data\":[{\"index\":1,\"embedding\":[0,1,0]},{\"index\":0,\"embedding\":[1,0,0]}]}";
    var output = new EmbeddingGateway(json).embed(
      List.of("第一段", "第二段"),
      connection()
    );
    assertEquals(List.of(1.0, 0.0, 0.0), output.get(0));
    assertEquals("test-vector", seen.get("model"));
    assertEquals("Bearer unit-secret", authorization);
    assertFalse(connection().toString().contains("unit-secret"));
  }

  @Test
  void rejectsWrongDimensionsDuplicatesZeroVectorsAndProviderSecrets() {
    var gateway = new EmbeddingGateway(json);
    for (String value : List.of(
      "{\"data\":[{\"index\":0,\"embedding\":[1,2]}]}",
      "{\"data\":[{\"index\":0,\"embedding\":[0,0,0]}]}",
      "{\"error\":\"unit-secret\"}"
    )) {
      body = value;
      var failure = assertThrows(Problem.class, () ->
        gateway.embed(List.of("私有资料"), connection())
      );
      assertFalse(failure.getMessage().contains("unit-secret"));
    }
  }
}
