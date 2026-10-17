package com.xushi.api;

import static com.xushi.common.Fields.*;

import com.xushi.auth.AuthService;
import com.xushi.common.*;
import com.xushi.knowledge.*;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
public class KnowledgeController {

  private final AuthService auth;
  private final KnowledgeService service;
  private final KnowledgeRepository repo;
  private final KnowledgeAnswerService answers;
  private final EmbeddingConfiguration config;
  private final EmbeddingGateway embeddings;
  private final Json json;

  public KnowledgeController(
    AuthService auth,
    KnowledgeService service,
    KnowledgeRepository repo,
    KnowledgeAnswerService answers,
    EmbeddingConfiguration config,
    EmbeddingGateway embeddings,
    Json json
  ) {
    this.auth = auth;
    this.service = service;
    this.repo = repo;
    this.answers = answers;
    this.config = config;
    this.embeddings = embeddings;
    this.json = json;
  }

  @GetMapping("/knowledge/bases")
  public Object bases(HttpServletRequest r) {
    return service.bases(auth.require(r));
  }

  @PostMapping("/knowledge/bases")
  public Object createBase(
    HttpServletRequest r,
    @RequestBody Map<String, Object> d
  ) {
    return service.saveBase(auth.require(r), null, d);
  }

  @PutMapping("/knowledge/bases/{id}")
  public Object editBase(
    HttpServletRequest r,
    @PathVariable long id,
    @RequestBody Map<String, Object> d
  ) {
    return service.saveBase(auth.require(r), id, d);
  }

  @GetMapping("/knowledge/documents")
  public Object documents(
    HttpServletRequest r,
    @RequestParam(required = false) Long base_id
  ) {
    return service.documents(auth.require(r), base_id);
  }

  @GetMapping("/knowledge/documents/{id}/versions")
  public Object versions(HttpServletRequest r, @PathVariable long id) {
    return service.versions(auth.require(r), id);
  }

  @PostMapping(
    value = "/knowledge/documents",
    consumes = MediaType.MULTIPART_FORM_DATA_VALUE
  )
  public Object upload(
    HttpServletRequest r,
    @RequestParam("file") MultipartFile file,
    @RequestParam("metadata") String metadata
  ) {
    return service.upload(auth.require(r), null, file, json.read(metadata));
  }

  @PostMapping(
    value = "/knowledge/documents/{id}/versions",
    consumes = MediaType.MULTIPART_FORM_DATA_VALUE
  )
  public Object version(
    HttpServletRequest r,
    @PathVariable long id,
    @RequestParam("file") MultipartFile file,
    @RequestParam("metadata") String metadata
  ) {
    return service.upload(auth.require(r), id, file, json.read(metadata));
  }

  @PostMapping("/knowledge/documents/{id}/{action:publish|unpublish|delete}")
  public Object action(
    HttpServletRequest r,
    @PathVariable long id,
    @PathVariable String action,
    @RequestBody Map<String, Object> d
  ) {
    return service.action(auth.require(r), id, action, d);
  }

  @PostMapping("/knowledge/versions/{id}/retry")
  public Object retry(HttpServletRequest r, @PathVariable long id) {
    return service.retry(auth.require(r), id);
  }

  @GetMapping("/knowledge/versions/{id}/preview")
  public Object preview(
    HttpServletRequest r,
    @PathVariable long id,
    @RequestParam(defaultValue = "0") int offset
  ) {
    return service.preview(auth.require(r), id, offset);
  }

  @GetMapping("/knowledge/versions/{id}/file")
  public Object versionFile(HttpServletRequest r, @PathVariable long id) {
    var v = service.versionFile(auth.require(r), id);
    return ResponseEntity.ok()
      .contentType(MediaType.APPLICATION_OCTET_STREAM)
      .header(
        HttpHeaders.CONTENT_DISPOSITION,
        ContentDisposition.attachment()
          .filename(s(v, "filename"), StandardCharsets.UTF_8)
          .build()
          .toString()
      )
      .body(new FileSystemResource(service.path(s(v, "storage_key"))));
  }

  @GetMapping("/knowledge/documents/{id}/file")
  public Object file(HttpServletRequest r, @PathVariable long id) {
    var v = service.file(auth.require(r), id);
    return ResponseEntity.ok()
      .contentType(MediaType.APPLICATION_OCTET_STREAM)
      .header(
        HttpHeaders.CONTENT_DISPOSITION,
        ContentDisposition.attachment()
          .filename(s(v, "filename"), StandardCharsets.UTF_8)
          .build()
          .toString()
      )
      .body(new FileSystemResource(service.path(s(v, "storage_key"))));
  }

  @GetMapping("/knowledge/chunks/{id}")
  public Object chunk(HttpServletRequest r, @PathVariable long id) {
    var c = repo.chunk(auth.require(r), id);
    var view = repo.citation(c);
    view.put("content", c.get("content"));
    return view;
  }

  @PostMapping("/knowledge/search")
  public Object search(
    HttpServletRequest r,
    @RequestBody Map<String, Object> d
  ) {
    return answers.search(r, d);
  }

  @PostMapping("/knowledge/ask")
  public Object ask(HttpServletRequest r, @RequestBody Map<String, Object> d) {
    return answers.ask(r, d);
  }

  @GetMapping("/knowledge/queries/{id}")
  public Object query(HttpServletRequest r, @PathVariable String id) {
    return answers.load(auth.require(r), id);
  }

  @PostMapping("/knowledge/queries/{id}/feedback")
  public Object feedback(
    HttpServletRequest r,
    @PathVariable String id,
    @RequestBody Map<String, Object> d
  ) {
    return answers.feedback(auth.require(r), id, d);
  }

  @GetMapping("/knowledge/stats")
  public Object stats(HttpServletRequest r) {
    return service.stats(auth.require(r));
  }

  @GetMapping("/admin/embedding-config")
  public Object config(HttpServletRequest r) {
    return config.get(auth.require(r));
  }

  @PutMapping("/admin/embedding-config")
  public Object save(HttpServletRequest r, @RequestBody Map<String, Object> d) {
    return config.save(auth.require(r), d);
  }

  @DeleteMapping("/admin/embedding-config")
  public Object clear(
    HttpServletRequest r,
    @RequestBody Map<String, Object> d
  ) {
    return config.clear(auth.require(r), d);
  }

  @PostMapping("/admin/embedding-config/test")
  public Object test(HttpServletRequest r, @RequestBody Map<String, Object> d) {
    var c = config.capture(auth.require(r));
    if (c == null) throw new Problem("请先保存并启用向量配置");
    if (!same(d.get("revision"), c.revision())) throw new Problem(
      "配置已更新，请重新读取",
      409
    );
    embeddings.embed(List.of("知识库连接测试"), c);
    auth.admin(auth.require(r));
    if (!config.current(c)) throw new Problem("测试期间配置已变更", 409);
    return map("message", "向量接口连接成功，返回维度与配置一致。");
  }
}
