package com.xushi.api;

import static com.xushi.common.Fields.*;

import com.xushi.auth.AuthService;
import com.xushi.schedule.SchedulingPolicy;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/scheduling-policy")
public class SchedulingPolicyController {

  private final SchedulingPolicy policy;
  private final AuthService auth;

  public SchedulingPolicyController(SchedulingPolicy policy, AuthService auth) {
    this.policy = policy;
    this.auth = auth;
  }

  @GetMapping
  public Object get(HttpServletRequest r) {
    auth.require(r);
    return map("policy", policy.get());
  }

  @PutMapping
  public Object save(@RequestBody Map<String, Object> d, HttpServletRequest r) {
    return policy.save(auth.require(r), d);
  }
}
