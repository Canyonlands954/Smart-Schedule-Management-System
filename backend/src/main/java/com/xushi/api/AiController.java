package com.xushi.api;

import com.xushi.ai.AiService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ai")
public class AiController {

  private final AiService service;

  public AiController(AiService service) {
    this.service = service;
  }

  @GetMapping("/conversations/{id}")
  public Object conversation(@PathVariable String id, HttpServletRequest r) {
    return service.load(r, id);
  }

  @PostMapping("/parse")
  public Object parse(
    @RequestBody Map<String, Object> data,
    HttpServletRequest r
  ) {
    return service.parse(r, data);
  }
}
