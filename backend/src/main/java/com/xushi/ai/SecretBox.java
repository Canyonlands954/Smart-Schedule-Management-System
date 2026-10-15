package com.xushi.ai;

import com.xushi.common.Problem;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.SecureRandom;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** The database stores authenticated ciphertext; the master key stays outside it. */
@Component
public class SecretBox {

  private final String master;
  private final Path file;
  private final SecureRandom random = new SecureRandom();
  private static final byte[] AAD = "xushi:model-configuration:v1".getBytes(
    StandardCharsets.UTF_8
  );

  public SecretBox(
    @Value("${schedule.ai.encryption-key}") String master,
    @Value("${schedule.ai.encryption-key-file}") String path
  ) {
    this.master = master;
    file = Path.of(path).toAbsolutePath().normalize();
  }

  private void protect(Path path) throws Exception {
    if (
      Files.getFileStore(path).supportsFileAttributeView("posix")
    ) Files.setPosixFilePermissions(
      path,
      Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
    );
    else {
      var acl = Files.getFileAttributeView(path, AclFileAttributeView.class);
      if (acl == null) throw new IllegalStateException();
      var entry = AclEntry.newBuilder()
        .setType(AclEntryType.ALLOW)
        .setPrincipal(Files.getOwner(path))
        .setPermissions(EnumSet.allOf(AclEntryPermission.class))
        .build();
      acl.setAcl(List.of(entry));
    }
  }

  private byte[] decode(String value) {
    byte[] key = Base64.getDecoder().decode(value.strip());
    if (key.length != 32) throw new IllegalArgumentException();
    return key;
  }

  private synchronized byte[] key(boolean create) throws Exception {
    if (!master.isBlank()) return decode(master);
    if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
      if (
        !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
      ) throw new IllegalStateException();
      return decode(Files.readString(file, StandardCharsets.US_ASCII));
    }
    if (!create) throw new IllegalStateException();
    Files.createDirectories(file.getParent());
    Path temp = Files.createTempFile(file.getParent(), "model-key-", ".tmp");
    try {
      protect(temp);
      byte[] key = new byte[32];
      random.nextBytes(key);
      Files.writeString(
        temp,
        Base64.getEncoder().encodeToString(key),
        StandardCharsets.US_ASCII
      );
      Files.move(temp, file);
      return key;
    } finally {
      Files.deleteIfExists(temp);
    }
  }

  public String encrypt(String plaintext, boolean create) {
    byte[] key = null;
    try {
      key = key(create);
      byte[] nonce = new byte[12];
      random.nextBytes(nonce);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
        Cipher.ENCRYPT_MODE,
        new SecretKeySpec(key, "AES"),
        new GCMParameterSpec(128, nonce)
      );
      cipher.updateAAD(AAD);
      return (
        "v1." +
        Base64.getEncoder().encodeToString(nonce) +
        "." +
        Base64.getEncoder().encodeToString(
          cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8))
        )
      );
    } catch (Exception e) {
      throw unavailable();
    } finally {
      if (key != null) Arrays.fill(key, (byte) 0);
    }
  }

  public String decrypt(String encrypted) {
    byte[] key = null;
    try {
      key = key(false);
      String[] parts = encrypted.split("\\.", -1);
      if (
        parts.length != 3 || !parts[0].equals("v1")
      ) throw new IllegalArgumentException();
      byte[] nonce = Base64.getDecoder().decode(parts[1]);
      if (nonce.length != 12) throw new IllegalArgumentException();
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
        Cipher.DECRYPT_MODE,
        new SecretKeySpec(key, "AES"),
        new GCMParameterSpec(128, nonce)
      );
      cipher.updateAAD(AAD);
      return new String(
        cipher.doFinal(Base64.getDecoder().decode(parts[2])),
        StandardCharsets.UTF_8
      );
    } catch (Exception e) {
      throw unavailable();
    } finally {
      if (key != null) Arrays.fill(key, (byte) 0);
    }
  }

  private Problem unavailable() {
    return new Problem(
      "服务器加密密钥不可用或配置校验失败，请恢复原加密密钥后重试。",
      503
    );
  }
}
