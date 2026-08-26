package io.akka.changedetection.jinja;

import java.util.ArrayList;
import java.util.List;

/**
 * A template split into the pieces the parser reads.
 *
 * <p>Two levels: the template as a whole is raw text with statement, expression and comment
 * blocks cut out of it, and the inside of a block is an ordinary stream of names, literals and
 * operators. Keeping them separate is what lets a template contain markup that looks like an
 * expression -- a stylesheet's braces, say -- without the outer level trying to read it.
 */
public final class Lexer {

  /** What a token is. */
  public enum Kind {
    RAW,
    VARIABLE_START,
    VARIABLE_END,
    BLOCK_START,
    BLOCK_END,
    NAME,
    STRING,
    INTEGER,
    FLOAT,
    OPERATOR,
    EOF
  }

  /** One token, with where it came from so an error can name a line. */
  public record Token(Kind kind, String value, int line) {
    @Override
    public String toString() {
      return kind + "(" + value + ")";
    }
  }

  private static final String[] OPERATORS = {
    "//", "**", "==", "!=", ">=", "<=", "//", "|", "~", "(", ")", "[", "]", "{", "}", ",", ":",
    ".", "=", "+", "-", "*", "/", "%", "<", ">"
  };

  private final String source;
  private int position;
  private int line = 1;
  private final List<Token> tokens = new ArrayList<>();

  private Lexer(String source) {
    this.source = source;
  }

  public static List<Token> tokenize(String source) {
    Lexer lexer = new Lexer(source);
    lexer.run();
    return lexer.tokens;
  }

  private void run() {
    StringBuilder raw = new StringBuilder();
    while (position < source.length()) {
      int next = source.indexOf('{', position);
      if (next < 0 || next + 1 >= source.length()) {
        raw.append(source, position, source.length());
        position = source.length();
        break;
      }
      char marker = source.charAt(next + 1);
      if (marker != '{' && marker != '%' && marker != '#') {
        raw.append(source, position, next + 1);
        position = next + 1;
        continue;
      }
      raw.append(source, position, next);
      position = next;

      boolean trimBefore = next + 2 < source.length() && source.charAt(next + 2) == '-';
      if (trimBefore) {
        stripTrailingWhitespace(raw);
      }

      if (marker == '#') {
        int end = source.indexOf("#}", position + 2);
        boolean trimAfter = end > 0 && source.charAt(end - 1) == '-';
        countLines(position, end < 0 ? source.length() : end + 2);
        position = end < 0 ? source.length() : end + 2;
        if (trimAfter) {
          skipLeadingWhitespace();
        }
        continue;
      }

      emitRaw(raw);

      if (marker == '{') {
        tokens.add(new Token(Kind.VARIABLE_START, "{{", line));
        position += trimBefore ? 3 : 2;
        lexInside("}}", Kind.VARIABLE_END);
      } else {
        tokens.add(new Token(Kind.BLOCK_START, "{%", line));
        position += trimBefore ? 3 : 2;
        if (peekRawBlock()) {
          continue;
        }
        lexInside("%}", Kind.BLOCK_END);
      }
    }
    emitRaw(raw);
    tokens.add(new Token(Kind.EOF, "", line));
  }

  /** A raw block is copied out verbatim, tags and all. */
  private boolean peekRawBlock() {
    int save = position;
    while (position < source.length() && Character.isWhitespace(source.charAt(position))) {
      position++;
    }
    if (!source.startsWith("raw", position)) {
      position = save;
      return false;
    }
    int end = source.indexOf("%}", position);
    if (end < 0) {
      position = save;
      return false;
    }
    String inner = source.substring(position, end).strip();
    if (!inner.equals("raw") && !inner.equals("raw -")) {
      position = save;
      return false;
    }
    tokens.remove(tokens.size() - 1);
    position = end + 2;
    int closing = indexOfEndRaw(position);
    String body = closing < 0 ? source.substring(position) : source.substring(position, closing);
    countLines(position, closing < 0 ? source.length() : closing);
    tokens.add(new Token(Kind.RAW, body, line));
    if (closing >= 0) {
      int close = source.indexOf("%}", closing);
      position = close < 0 ? source.length() : close + 2;
    } else {
      position = source.length();
    }
    return true;
  }

  private int indexOfEndRaw(int from) {
    int at = from;
    while (true) {
      int open = source.indexOf("{%", at);
      if (open < 0) {
        return -1;
      }
      int close = source.indexOf("%}", open);
      if (close < 0) {
        return -1;
      }
      String inner = source.substring(open + 2, close).replace("-", "").strip();
      if (inner.equals("endraw")) {
        return open;
      }
      at = close + 2;
    }
  }

  private void emitRaw(StringBuilder raw) {
    if (raw.length() > 0) {
      tokens.add(new Token(Kind.RAW, raw.toString(), line));
      countNewlines(raw);
      raw.setLength(0);
    }
  }

  private void countNewlines(CharSequence text) {
    for (int i = 0; i < text.length(); i++) {
      if (text.charAt(i) == '\n') {
        line++;
      }
    }
  }

  private void countLines(int from, int to) {
    for (int i = from; i < to && i < source.length(); i++) {
      if (source.charAt(i) == '\n') {
        line++;
      }
    }
  }

  private static void stripTrailingWhitespace(StringBuilder raw) {
    int end = raw.length();
    while (end > 0 && Character.isWhitespace(raw.charAt(end - 1))) {
      end--;
    }
    raw.setLength(end);
  }

  private void skipLeadingWhitespace() {
    while (position < source.length() && Character.isWhitespace(source.charAt(position))) {
      if (source.charAt(position) == '\n') {
        line++;
      }
      position++;
    }
  }

  private void lexInside(String closing, Kind closeKind) {
    while (position < source.length()) {
      char c = source.charAt(position);
      if (c == '\n') {
        line++;
        position++;
        continue;
      }
      if (Character.isWhitespace(c)) {
        position++;
        continue;
      }
      if (source.startsWith("-" + closing, position)) {
        tokens.add(new Token(closeKind, closing, line));
        position += closing.length() + 1;
        skipLeadingWhitespace();
        return;
      }
      if (source.startsWith(closing, position)) {
        tokens.add(new Token(closeKind, closing, line));
        position += closing.length();
        return;
      }
      if (c == '"' || c == '\'') {
        tokens.add(new Token(Kind.STRING, readString(c), line));
        continue;
      }
      if (Character.isDigit(c)
          || (c == '.' && position + 1 < source.length()
              && Character.isDigit(source.charAt(position + 1)))) {
        readNumber();
        continue;
      }
      if (Character.isLetter(c) || c == '_') {
        int start = position;
        while (position < source.length()
            && (Character.isLetterOrDigit(source.charAt(position))
                || source.charAt(position) == '_')) {
          position++;
        }
        tokens.add(new Token(Kind.NAME, source.substring(start, position), line));
        continue;
      }
      String operator = readOperator();
      if (operator == null) {
        throw new JinjaException("unexpected character '" + c + "' on line " + line);
      }
      tokens.add(new Token(Kind.OPERATOR, operator, line));
    }
    throw new JinjaException("unclosed block starting before line " + line);
  }

  private String readOperator() {
    for (String operator : OPERATORS) {
      if (source.startsWith(operator, position)) {
        position += operator.length();
        return operator;
      }
    }
    return null;
  }

  private void readNumber() {
    int start = position;
    boolean isFloat = false;
    while (position < source.length()) {
      char c = source.charAt(position);
      if (Character.isDigit(c) || c == '_') {
        position++;
      } else if (c == '.'
          && position + 1 < source.length()
          && Character.isDigit(source.charAt(position + 1))
          && !isFloat) {
        isFloat = true;
        position++;
      } else if ((c == 'e' || c == 'E')
          && position + 1 < source.length()
          && (Character.isDigit(source.charAt(position + 1))
              || source.charAt(position + 1) == '-'
              || source.charAt(position + 1) == '+')) {
        isFloat = true;
        position += 2;
      } else {
        break;
      }
    }
    String text = source.substring(start, position).replace("_", "");
    tokens.add(new Token(isFloat ? Kind.FLOAT : Kind.INTEGER, text, line));
  }

  private String readString(char quote) {
    position++;
    StringBuilder sb = new StringBuilder();
    while (position < source.length()) {
      char c = source.charAt(position);
      if (c == '\\' && position + 1 < source.length()) {
        char escaped = source.charAt(position + 1);
        switch (escaped) {
          case 'n' -> sb.append('\n');
          case 't' -> sb.append('\t');
          case 'r' -> sb.append('\r');
          case '\\' -> sb.append('\\');
          case '\'' -> sb.append('\'');
          case '"' -> sb.append('"');
          case '0' -> sb.append('\0');
          default -> sb.append('\\').append(escaped);
        }
        position += 2;
        continue;
      }
      if (c == quote) {
        position++;
        return sb.toString();
      }
      if (c == '\n') {
        line++;
      }
      sb.append(c);
      position++;
    }
    throw new JinjaException("unterminated string on line " + line);
  }
}
