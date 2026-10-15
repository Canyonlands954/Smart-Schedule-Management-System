package com.xushi;

import static org.junit.jupiter.api.Assertions.*;

import com.xushi.ai.*;
import com.xushi.common.Problem;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SecretBoxTest {

  @TempDir
  Path directory;

  @Test
  void encryptionSurvivesRestartAndUsesPrivateRandomMasterKey()
    throws Exception {
    var path = directory.resolve("secrets/model.key");
    var box = new SecretBox("", path.toString());
    String plain =
      "{\"api_key\":\"fixture-secret\",\"base_url\":\"https://example.test\"}";
    String first = box.encrypt(plain, true),
      second = box.encrypt(plain, false);
    assertNotEquals(first, second);
    assertFalse(first.contains("fixture-secret"));
    assertEquals(plain, new SecretBox("", path.toString()).decrypt(first));
    assertEquals(32, Base64.getDecoder().decode(Files.readString(path)).length);
    if (
      Files.getFileStore(path).supportsFileAttributeView("posix")
    ) assertEquals(
      Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
      Files.getPosixFilePermissions(path)
    );
    else {
      var acl = Files.getFileAttributeView(path, AclFileAttributeView.class);
      assertEquals(1, acl.getAcl().size());
      assertEquals(Files.getOwner(path), acl.getAcl().get(0).principal());
    }
  }

  @Test
  void tamperingWrongKeyAndMissingMasterFailClosed() throws Exception {
    var path = directory.resolve("model.key");
    var box = new SecretBox("", path.toString());
    String encrypted = box.encrypt("fixture-secret", true);
    String[] parts = encrypted.split("\\.");
    byte[] payload = Base64.getDecoder().decode(parts[2]);
    payload[0] ^= 1;
    String modified =
      parts[0] +
      "." +
      parts[1] +
      "." +
      Base64.getEncoder().encodeToString(payload);
    assertEquals(
      503,
      assertThrows(Problem.class, () -> box.decrypt(modified)).status
    );
    byte[] wrong = new byte[32];
    Arrays.fill(wrong, (byte) 1);
    var other = new SecretBox(
      Base64.getEncoder().encodeToString(wrong),
      path.toString()
    );
    assertThrows(Problem.class, () -> other.decrypt(encrypted));
    Files.delete(path);
    assertThrows(Problem.class, () -> box.decrypt(encrypted));
    assertThrows(Problem.class, () -> box.encrypt("replacement", false));
    assertFalse(Files.exists(path));
  }

  @Test
  void invalidMasterAndConnectionStringDoNotExposeSecrets() {
    var box = new SecretBox(
      "invalid-master-key",
      directory.resolve("model.key").toString()
    );
    var problem = assertThrows(Problem.class, () ->
      box.encrypt("fixture-secret", true)
    );
    assertFalse(problem.getMessage().contains("fixture-secret"));
    var connection = new ModelConfiguration.Connection(
      "https://example.test",
      "model",
      "fixture-secret",
      1
    );
    assertFalse(connection.toString().contains("fixture-secret"));
  }
}
