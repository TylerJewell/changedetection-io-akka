package io.akka.changedetection.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.keyvalueentity.KeyValueEntity;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * One stored version of a watched page.
 *
 * <p>Kept apart from the watch because a watch may hold hundreds of these and they are read one
 * at a time -- to show a difference, or to answer whether a line has ever been seen. Holding
 * them inside the watch would mean loading every version to answer any question about it.
 *
 * <p>Compressed, because a page routinely runs to hundreds of kilobytes of largely repeated
 * text and this is state that is stored and replicated whole.
 */
@Component(id = "snapshot")
public class SnapshotEntity extends KeyValueEntity<SnapshotEntity.Snapshot> {

  /** The stored text, compressed, with what it was before compression. */
  public record Snapshot(byte[] compressed, int textLength, String kind) {

    public static Snapshot empty() {
      return new Snapshot(new byte[0], 0, "");
    }

    public boolean exists() {
      return textLength > 0 || compressed.length > 0;
    }
  }

  public record Store(String text, String kind) {}

  /** The key one version is stored under. */
  public static String id(String watchUuid, long timestamp, String kind) {
    return watchUuid + ":" + timestamp + (kind == null || kind.isEmpty() ? "" : ":" + kind);
  }

  @Override
  public Snapshot emptyState() {
    return Snapshot.empty();
  }

  /**
   * How large one stored version may be once compressed.
   *
   * <p>A record this runtime replicates has a ceiling past which it stops replicating, and a
   * watched page has no size limit of its own -- a document store, a large feed or a page
   * that embeds its own data will pass it. A version too large to keep is refused with the
   * reason rather than stored and silently unreplicated, and the check that produced it
   * reports the refusal against the watch.
   */
  static final int MAXIMUM_COMPRESSED_BYTES = 900 * 1024;

  public Effect<String> store(Store command) {
    String text = command.text() == null ? "" : command.text();
    byte[] compressed = compress(text);
    if (compressed.length > MAXIMUM_COMPRESSED_BYTES) {
      return effects()
          .error(
              "This version is "
                  + (compressed.length / 1024)
                  + "kB compressed, past the "
                  + (MAXIMUM_COMPRESSED_BYTES / 1024)
                  + "kB a stored version may be. Narrow what is compared with a filter.");
    }
    return effects()
        .updateState(new Snapshot(compressed, text.length(), command.kind()))
        .thenReply("ok");
  }

  public ReadOnlyEffect<String> read() {
    return effects().reply(decompress(currentState().compressed()));
  }

  public Effect<String> remove() {
    return effects().deleteEntity().thenReply("ok");
  }

  static byte[] compress(String text) {
    byte[] raw = text.getBytes(StandardCharsets.UTF_8);
    Deflater deflater = new Deflater(Deflater.BEST_SPEED);
    deflater.setInput(raw);
    deflater.finish();
    ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, raw.length / 4));
    byte[] buffer = new byte[8192];
    while (!deflater.finished()) {
      int written = deflater.deflate(buffer);
      out.write(buffer, 0, written);
    }
    deflater.end();
    return out.toByteArray();
  }

  static String decompress(byte[] compressed) {
    if (compressed == null || compressed.length == 0) {
      return "";
    }
    Inflater inflater = new Inflater();
    inflater.setInput(compressed);
    ByteArrayOutputStream out = new ByteArrayOutputStream(compressed.length * 4);
    byte[] buffer = new byte[8192];
    try {
      while (!inflater.finished()) {
        int written = inflater.inflate(buffer);
        if (written == 0 && inflater.needsInput()) {
          break;
        }
        out.write(buffer, 0, written);
      }
    } catch (Exception e) {
      return "";
    } finally {
      inflater.end();
    }
    return out.toString(StandardCharsets.UTF_8);
  }
}
