package io.akka.changedetection.fetchers;

import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * The character set of a page that declares none.
 *
 * <p>The last resort, reached only when the server said nothing, there was no byte-order mark
 * and the document declares nothing either. Reading a page in the wrong set does not fail --
 * it silently produces different text, so every check after it compares that text against the
 * next wrong reading.
 */
public final class CharsetGuess {

  private CharsetGuess() {}

  public static Charset detect(byte[] body) {
    if (isValid(body, StandardCharsets.UTF_8)) {
      return StandardCharsets.UTF_8;
    }
    // Nothing else can fail on arbitrary bytes, so this is where the guess stops; the same
    // fallback the original's detector converges on for western European pages.
    return Charset.forName("windows-1252");
  }

  private static boolean isValid(byte[] body, Charset charset) {
    CharsetDecoder decoder = charset.newDecoder();
    decoder.onMalformedInput(CodingErrorAction.REPORT);
    decoder.onUnmappableCharacter(CodingErrorAction.REPORT);
    try {
      decoder.decode(java.nio.ByteBuffer.wrap(body));
      return true;
    } catch (Exception e) {
      return false;
    }
  }
}
