package com.xushi.common;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class Json {

  public final ObjectMapper mapper;

  public Json(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  public String write(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid JSON", e);
    }
  }

  public Map<String, Object> read(Object value) {
    try {
      return mapper.readValue(
        value.toString(),
        new TypeReference<LinkedHashMap<String, Object>>() {}
      );
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid JSON", e);
    }
  }

  public Map<String, Object> copy(Map<String, Object> value) {
    return read(write(value));
  }

  public Object value(Object raw) {
    try {
      return mapper.readValue(raw.toString(), Object.class);
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid JSON", e);
    }
  }
}
