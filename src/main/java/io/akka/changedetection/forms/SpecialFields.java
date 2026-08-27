package io.akka.changedetection.forms;

import io.akka.changedetection.jinja.PyValue;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** The controls that exist for one purpose each and carry that purpose's rules with them. */
public final class SpecialFields {

  private SpecialFields() {}

  /**
   * A password, stored as a salted derivation and never as itself.
   *
   * <p>The control shows nothing back: what is stored cannot be turned back into the password,
   * and an empty submission means "leave it alone" rather than "clear it".
   */
  public static final class SaltedPasswordField extends Fields.StringField {
    private static final int ITERATIONS = 100_000;
    private static final int SALT_BYTES = 32;
    private static final int KEY_BITS = 256;

    private String derived = "";

    public SaltedPasswordField(String name, String label) {
      super(name, label);
    }

    @Override
    public String type() {
      return "SaltyPasswordField";
    }

    /** The stored form, empty when nothing new was entered. */
    public String derived() {
      return derived;
    }

    @Override
    public String render(Map<String, Object> attributes) {
      Map<String, Object> merged = new LinkedHashMap<>();
      merged.put("id", id());
      merged.put("name", id());
      merged.put("type", "password");
      merged.put("value", "");
      merged.putAll(attributes);
      return "<input" + attributesOf(merged) + ">";
    }

    @Override
    public void populate(List<String> submitted, boolean present) {
      if (submitted.isEmpty()) {
        data = Boolean.FALSE;
        return;
      }
      if (submitted.get(0).strip().isEmpty()) {
        data = "";
        return;
      }
      derived = derive(submitted.get(0));
      data = "";
    }

    /** A fresh salt for every password, stored alongside the derivation it produced. */
    public static String derive(String password) {
      byte[] salt = new byte[SALT_BYTES];
      new SecureRandom().nextBytes(salt);
      byte[] key = pbkdf2(password, salt);
      byte[] stored = new byte[salt.length + key.length];
      System.arraycopy(salt, 0, stored, 0, salt.length);
      System.arraycopy(key, 0, stored, salt.length, key.length);
      return Base64.getEncoder().encodeToString(stored);
    }

    /** Whether a password matches what was stored, comparing in constant time. */
    public static boolean matches(String password, String stored) {
      if (stored == null || stored.isBlank()) {
        return false;
      }
      byte[] raw;
      try {
        raw = Base64.getDecoder().decode(stored);
      } catch (IllegalArgumentException e) {
        return false;
      }
      if (raw.length <= SALT_BYTES) {
        return false;
      }
      byte[] salt = new byte[SALT_BYTES];
      System.arraycopy(raw, 0, salt, 0, SALT_BYTES);
      byte[] expected = new byte[raw.length - SALT_BYTES];
      System.arraycopy(raw, SALT_BYTES, expected, 0, expected.length);
      byte[] actual = pbkdf2(password, salt);
      return java.security.MessageDigest.isEqual(expected, actual);
    }

    private static byte[] pbkdf2(String password, byte[] salt) {
      try {
        PBEKeySpec spec =
            new PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_BITS);
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec)
            .getEncoded();
      } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
        throw new IllegalStateException("password hashing is unavailable", e);
      }
    }
  }

  /**
   * The groups a watch belongs to, shown by name and stored by identifier.
   *
   * <p>Shown by name because an identifier means nothing to the person editing; the submission
   * is turned back into identifiers by the handler, which is where a name that does not exist
   * yet becomes a new group.
   */
  public static final class TagField extends Fields.StringField {
    private final Map<String, Object> tags;

    public TagField(String name, String label, Map<String, Object> tags) {
      super(name, label);
      this.tags = tags;
    }

    @Override
    public String type() {
      return "StringTagUUID";
    }

    @Override
    public String render(Map<String, Object> attributes) {
      Map<String, Object> merged = new LinkedHashMap<>();
      merged.put("id", id());
      merged.put("name", id());
      merged.put("type", "text");
      merged.put("value", asText());
      merged.putAll(attributes);
      return "<input" + attributesOf(merged) + ">";
    }

    private String asText() {
      if (data instanceof List<?> list) {
        List<String> titles = new ArrayList<>();
        for (Object identifier : list) {
          Object tag = tags.get(PyValue.asString(identifier));
          if (tag instanceof Map<?, ?> map) {
            Object title = map.get("title");
            if (title != null && !String.valueOf(title).isEmpty()) {
              titles.add(String.valueOf(title));
            }
          }
        }
        return String.join(", ", titles);
      }
      if (data == null || PyValue.asString(data).isEmpty()) {
        return "";
      }
      return "error";
    }

    @Override
    public void populate(List<String> submitted, boolean present) {
      // The names are turned into identifiers by the handler, which alone can create a group.
      data = submitted.isEmpty() ? "" : submitted.get(0);
    }
  }

  /** The one field a watch's extra headers file contributes, shown but never submitted. */
  public static String headersFromText(String text) {
    StringBuilder sb = new StringBuilder();
    for (String line : text.split("\n", -1)) {
      String stripped = line.strip();
      if (stripped.startsWith("#") || !stripped.contains(":")) {
        continue;
      }
      sb.append(stripped).append('\n');
    }
    return sb.toString();
  }

  static String utf8(String value) {
    return new String(value.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
  }
}
