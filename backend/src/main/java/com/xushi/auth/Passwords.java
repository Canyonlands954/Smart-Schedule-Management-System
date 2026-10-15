package com.xushi.auth;

import com.xushi.common.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.HexFormat;
import org.bouncycastle.crypto.generators.SCrypt;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class Passwords {

  private final Pbkdf2PasswordEncoder encoder =
    Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8();

  public String encode(Object raw) {
    if (
      !(raw instanceof String password) ||
      password.length() < 10 ||
      password.length() > 128
    ) throw new Problem("密码需要 10–128 个字符");
    return "{pbkdf2}" + encoder.encode(password);
  }

  public boolean matches(Object raw, String encoded) {
    if (!(raw instanceof String p) || p.length() > 128) return false;
    try {
      if (encoded.startsWith("{pbkdf2}")) return encoder.matches(
        p,
        encoded.substring(8)
      );
      String[] parts = encoded.split(":");
      if (parts.length != 2) return false;
      byte[] actual = SCrypt.generate(
        p.getBytes(StandardCharsets.UTF_8),
        HexFormat.of().parseHex(parts[0]),
        16384,
        8,
        1,
        64
      );
      return MessageDigest.isEqual(actual, HexFormat.of().parseHex(parts[1]));
    } catch (Exception e) {
      return false;
    }
  }

  public static String digest(String value) {
    try {
      return HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(
          value.getBytes(StandardCharsets.UTF_8)
        )
      );
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public static String token() {
    byte[] b = new byte[32];
    new SecureRandom().nextBytes(b);
    return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(b);
  }
}
