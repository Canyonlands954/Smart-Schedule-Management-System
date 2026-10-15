package com.xushi.common;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

public final class Fields {

  public static final ZoneOffset ZONE = ZoneOffset.ofHours(8);

  private Fields() {}

  public static Map<String, Object> map(Object... values) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (int i = 0; i < values.length; i += 2) result.put(
      (String) values[i],
      values[i + 1]
    );
    return result;
  }

  public static String text(
    Object raw,
    String label,
    int max,
    boolean required
  ) {
    if (!(raw instanceof String)) throw new Problem(label + "格式不正确");
    String v = ((String) raw).strip();
    if (v.length() > max || (required && v.isEmpty())) throw new Problem(
      label + "不能为空且最多 " + max + " 个字符"
    );
    return v;
  }

  public static String s(Map<String, Object> m, String k) {
    return Objects.toString(m.get(k), "");
  }

  public static long id(Object raw) {
    if (raw instanceof Boolean || raw == null) throw new Problem(
      "编号格式不正确"
    );
    try {
      if (
        raw instanceof Number n && n.doubleValue() != n.longValue()
      ) throw new NumberFormatException();
      long v = Long.parseLong(raw.toString());
      if (v < 0) throw new NumberFormatException();
      return v;
    } catch (Exception e) {
      throw new Problem("编号须为非负整数");
    }
  }

  public static Long nullableId(Object raw) {
    return raw == null || "".equals(raw) ? null : id(raw);
  }

  public static int n(Map<String, Object> m, String k) {
    return ((Number) m.getOrDefault(k, 0)).intValue();
  }

  public static boolean same(Object a, Object b) {
    if (a instanceof Number && b instanceof Number) return (
      ((Number) a).longValue() == ((Number) b).longValue()
    );
    return Objects.equals(a, b);
  }

  public static OffsetDateTime time(Object raw) {
    try {
      return OffsetDateTime.parse((String) raw).withOffsetSameInstant(ZONE);
    } catch (Exception e) {
      throw new Problem("日期需要包含时区，请重新选择时间");
    }
  }

  public static String stamp(Object raw) {
    return time(raw).format(
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx")
    );
  }

  public static String now() {
    return OffsetDateTime.now(ZONE).format(
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSSxxx")
    );
  }

  public static void version(
    Map<String, Object> data,
    Map<String, Object> event
  ) {
    if (
      !same(data.get("expected_updated_at"), event.get("updated_at"))
    ) throw new Problem(
      "日程已被其他人更新，请刷新后重新操作",
      409,
      map("code", "version_conflict")
    );
  }

  @SuppressWarnings("unchecked")
  public static Map<String, Object> object(Object value) {
    return value instanceof Map<?, ?>
      ? (Map<String, Object>) value
      : new LinkedHashMap<>();
  }

  @SuppressWarnings("unchecked")
  public static List<Map<String, Object>> objects(Object value) {
    return value instanceof List<?>
      ? (List<Map<String, Object>>) value
      : new ArrayList<>();
  }
}
