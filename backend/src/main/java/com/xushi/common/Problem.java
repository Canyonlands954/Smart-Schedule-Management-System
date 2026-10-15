package com.xushi.common;

import java.util.Map;

public class Problem extends RuntimeException {

  public final int status;
  public final Map<String, Object> details;

  public Problem(String message) {
    this(message, 400);
  }

  public Problem(String message, int status) {
    this(message, status, Map.of());
  }

  public Problem(String message, int status, Map<String, Object> details) {
    super(message);
    this.status = status;
    this.details = details;
  }
}
