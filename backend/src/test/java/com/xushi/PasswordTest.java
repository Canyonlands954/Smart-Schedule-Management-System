package com.xushi;

import static org.junit.jupiter.api.Assertions.*;

import com.xushi.auth.Passwords;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import org.bouncycastle.crypto.generators.SCrypt;
import org.junit.jupiter.api.Test;

class PasswordTest {

  @Test
  void importedPythonHashAndNewPasswordEncoding() {
    Passwords service = new Passwords();
    String password = " OldPassword2026! ",
      salt = "00112233445566778899aabbccddeeff";
    String legacy =
      salt +
      ":" +
      HexFormat.of().formatHex(
        SCrypt.generate(
          password.getBytes(StandardCharsets.UTF_8),
          HexFormat.of().parseHex(salt),
          16384,
          8,
          1,
          64
        )
      );
    assertTrue(service.matches(password, legacy));
    assertFalse(service.matches("incorrect", legacy));
    String upgraded = service.encode(password);
    assertTrue(service.matches(password, upgraded));
    assertFalse(service.matches(password.strip(), upgraded));
  }
}
