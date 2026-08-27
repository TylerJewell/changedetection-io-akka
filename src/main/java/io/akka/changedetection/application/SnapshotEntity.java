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

  public Effect<String> store(Store command) {
    String text = command.text() == null ? "" : command.text();
    return effects()
        .updateState(new Snapshot(compress(text), text.length(), command.kind()))
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
