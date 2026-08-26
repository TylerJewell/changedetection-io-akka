package io.akka.changedetection.text.inscriptis;

import java.util.ArrayList;
import java.util.List;

/** The left indentation and bullet that go in front of a line. */
public final class Prefix {

  private int currentPadding = 0;
  private final List<Integer> paddings = new ArrayList<>();
  private final List<String> bullets = new ArrayList<>();
  private boolean consumed = false;

  public void registerPrefix(int paddingInline, String bullet) {
    currentPadding += paddingInline;
    paddings.add(paddingInline);
    bullets.add(bullet == null ? "" : bullet);
  }

  public void removeLastPrefix() {
    if (paddings.isEmpty()) {
      return;
    }
    currentPadding -= paddings.remove(paddings.size() - 1);
    bullets.remove(bullets.size() - 1);
  }

  /** The innermost bullet not yet written, cleared as it is handed out. */
  public String popNextBullet() {
    int nextBulletIdx = 0;
    boolean found = false;
    for (int idx = 0; idx < bullets.size(); idx++) {
      String val = bullets.get(bullets.size() - 1 - idx);
      if (val != null && !val.isEmpty()) {
        nextBulletIdx = -idx - 1;
        found = true;
        break;
      }
    }
    if (!found) {
      nextBulletIdx = 0;
    }
    if (nextBulletIdx == 0) {
      return "";
    }
    int index = bullets.size() + nextBulletIdx;
    String bullet = bullets.get(index);
    bullets.set(index, "");
    return bullet;
  }

  public String first() {
    if (consumed) {
      return "";
    }
    consumed = true;
    String bullet = popNextBullet();
    int pad = currentPadding - bullet.length();
    return (pad > 0 ? " ".repeat(pad) : "") + bullet;
  }

  public String unconsumedBullet() {
    if (consumed) {
      return "";
    }
    String bullet = popNextBullet();
    if (bullet.isEmpty()) {
      return "";
    }
    int padding = currentPadding - (paddings.isEmpty() ? 0 : paddings.get(paddings.size() - 1));
    int pad = padding - bullet.length();
    return (pad > 0 ? " ".repeat(pad) : "") + bullet;
  }

  public String rest() {
    return " ".repeat(Math.max(0, currentPadding));
  }

  public int currentPadding() {
    return currentPadding;
  }

  public void setConsumed(boolean consumed) {
    this.consumed = consumed;
  }
}
