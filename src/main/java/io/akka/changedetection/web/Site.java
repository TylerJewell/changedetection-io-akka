package io.akka.changedetection.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/** The things the interface needs that are the same for every request. */
public final class Site {

  /** The version this rebuild reports, which the interface shows and the API returns. */
  public static final String VERSION = readVersion();

  private static final Instant STARTED = Instant.now();

  private static volatile byte[] secret;

  /**
   * The watches asked to check that have not started yet.
   *
   * <p>Held here rather than derived, because a watch that has been asked for but has not begun
   * is invisible in its own state -- and that gap is exactly what the queue page is for.
   */
  private static final Set<String> QUEUED = new LinkedHashSet<>();

  private Site() {}

  public static String datastorePath() {
    String configured = System.getenv("DATASTORE_PATH");
    return configured == null || configured.isBlank() ? "./datastore" : configured;
  }

  public static Instant startedAt() {
    return STARTED;
  }

  /**
   * The key the session cookie is signed with.
   *
   * <p>Kept on disk beside the data, so that a restart does not sign everyone out. Generated on
   * first use; a directory that cannot be written falls back to a key held only in memory,
   * which signs people out on restart but never leaves the service unable to start.
   */
  public static byte[] secret() {
    byte[] current = secret;
    if (current != null) {
      return current;
    }
    synchronized (Site.class) {
      if (secret != null) {
        return secret;
      }
      secret = loadOrCreateSecret();
      return secret;
    }
  }

  private static byte[] loadOrCreateSecret() {
    Path path = Path.of(datastorePath(), "secret.txt");
    try {
      if (Files.isRegularFile(path)) {
        String stored = Files.readString(path, StandardCharsets.UTF_8).strip();
        if (!stored.isEmpty()) {
          return stored.getBytes(StandardCharsets.UTF_8);
        }
      }
    } catch (IOException e) {
      // Unreadable is the same as absent: a new one is made below.
    }
    byte[] fresh = new byte[32];
    new SecureRandom().nextBytes(fresh);
    String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(fresh);
    try {
      Files.createDirectories(path.getParent());
      Files.writeString(path, encoded, StandardCharsets.UTF_8);
    } catch (IOException e) {
      // Not written: sessions last only as long as this process, which is worse than keeping
      // them but better than refusing to serve at all.
    }
    return encoded.getBytes(StandardCharsets.UTF_8);
  }

  public static void queue(String uuid) {
    synchronized (QUEUED) {
      QUEUED.add(uuid);
    }
    announceQueue();
  }

  public static void queueAll(Collection<String> uuids) {
    synchronized (QUEUED) {
      QUEUED.addAll(uuids);
    }
    announceQueue();
  }

  public static void unqueue(String uuid) {
    synchronized (QUEUED) {
      QUEUED.remove(uuid);
    }
    announceQueue();
  }

  public static Set<String> queued() {
    synchronized (QUEUED) {
      return new LinkedHashSet<>(QUEUED);
    }
  }

  public static int queueSize() {
    synchronized (QUEUED) {
      return QUEUED.size();
    }
  }

  public static void clearQueue() {
    synchronized (QUEUED) {
      QUEUED.clear();
    }
    announceQueue();
  }

  /**
   * Tells every open page how long the queue is now.
   *
   * <p>Announced from here rather than from each caller, because the number the page shows is
   * this set's size and nothing else can be sure it changed.
   */
  private static void announceQueue() {
    int size;
    java.util.List<String> waiting;
    synchronized (QUEUED) {
      size = QUEUED.size();
      waiting = new java.util.ArrayList<>(QUEUED);
    }
    StreamHub.publish("queue_size", java.util.Map.of("q_length", size));
    StreamHub.publish(
        "checking_now", java.util.Map.of("checking_now", waiting.size(), "uuids", waiting));
  }

  private static String readVersion() {
    try (var stream = Site.class.getResourceAsStream("/changedetection/version.txt")) {
      if (stream == null) {
        return "0.0.0";
      }
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8).strip();
    } catch (IOException e) {
      return "0.0.0";
    }
  }
}
