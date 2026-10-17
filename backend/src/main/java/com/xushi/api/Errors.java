package com.xushi.api;

import static com.xushi.common.Fields.*;

import com.xushi.common.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.dao.*;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class Errors {

  private final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(
    Errors.class
  );

  @ExceptionHandler(Problem.class)
  public ResponseEntity<?> problem(Problem e) {
    Map<String, Object> b = map("error", e.getMessage());
    b.putAll(e.details);
    return ResponseEntity.status(e.status).body(b);
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  public ResponseEntity<?> constraint(Exception e) {
    return ResponseEntity.status(409).body(
      map("error", "账号或名称已存在，或数据仍被关联使用，请检查后重试。")
    );
  }

  @ExceptionHandler(
    org.springframework.web.multipart.MaxUploadSizeExceededException.class
  )
  public ResponseEntity<?> uploadSize(Exception e) {
    return ResponseEntity.status(413).body(
      map("error", "文件不能超过10MB，请拆分后上传。")
    );
  }

  @ExceptionHandler({
    HttpMessageNotReadableException.class,
    IllegalArgumentException.class,
  })
  public ResponseEntity<?> input(Exception e) {
    return ResponseEntity.badRequest().body(map("error", "输入数据格式不正确"));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<?> error(Exception e, HttpServletRequest r) {
    log.error("Request failed {}", r.getAttribute("request_id"), e);
    return ResponseEntity.status(500).body(
      map(
        "error",
        "服务器处理失败，请联系管理员。",
        "request_id",
        r.getAttribute("request_id")
      )
    );
  }
}
