package com.xushi.api;

import static com.xushi.common.Fields.*;

import com.xushi.ai.*;
import com.xushi.auth.AuthService;
import com.xushi.common.Problem;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/model-config")
public class ModelConfigurationController {

  private final AuthService auth;
  private final ModelConfiguration config;
  private final ModelGateway gateway;

  public ModelConfigurationController(
    AuthService auth,
    ModelConfiguration config,
    ModelGateway gateway
  ) {
    this.auth = auth;
    this.config = config;
    this.gateway = gateway;
  }

  @GetMapping
  public Object get(HttpServletRequest r) {
    return config.get(auth.require(r));
  }

  @PutMapping
  public Object save(
    HttpServletRequest r,
    @RequestBody Map<String, Object> input
  ) {
    return config.save(auth.require(r), input);
  }

  @DeleteMapping
  public Object clear(
    HttpServletRequest r,
    @RequestBody Map<String, Object> input
  ) {
    return config.clear(auth.require(r), input);
  }

  @PostMapping("/test")
  public Object test(
    HttpServletRequest r,
    @RequestBody Map<String, Object> input
  ) {
    var connection = config.capture(auth.require(r));
    if (connection == null) throw new Problem("请先保存并启用模型配置");
    if (!same(input.get("revision"), connection.revision())) throw new Problem(
      "配置已更新，请重新读取后测试",
      409
    );
    gateway.test(connection);
    auth.admin(auth.require(r));
    if (!config.current(connection)) throw new Problem(
      "测试期间配置已更改，请重新测试",
      409
    );
    return map("ok", true, "message", "连接成功，当前接口支持模型JSON响应。 ");
  }
}
