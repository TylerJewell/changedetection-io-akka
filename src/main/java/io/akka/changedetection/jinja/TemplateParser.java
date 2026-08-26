package io.akka.changedetection.jinja;

import io.akka.changedetection.jinja.Lexer.Kind;
import io.akka.changedetection.jinja.Lexer.Token;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A token stream turned into the tree the interpreter walks. */
public final class TemplateParser {

  private static final Set<String> KEYWORD_LITERALS =
      Set.of("true", "false", "none", "True", "False", "None");

  private final List<Token> tokens;
  private final String name;
  private int position;

  private TemplateParser(List<Token> tokens, String name) {
    this.tokens = tokens;
    this.name = name;
  }

  public static Node.Template parse(String source, String name) {
    TemplateParser parser = new TemplateParser(Lexer.tokenize(source), name);
    List<Node> body = parser.parseBody(Set.of());
    parser.expect(Kind.EOF);
    return new Node.Template(body, name);
  }

  /** Parses one expression on its own, for a notification body or a URL template. */
  public static Node parseExpression(String source) {
    TemplateParser parser = new TemplateParser(Lexer.tokenize("{{" + source + "}}"), "<expr>");
    parser.expect(Kind.VARIABLE_START);
    Node expression = parser.expression();
    parser.expect(Kind.VARIABLE_END);
    return expression;
  }

  // ------------------------------------------------------------- statements

  private List<Node> parseBody(Set<String> stopAt) {
    List<Node> body = new ArrayList<>();
    while (true) {
      Token token = peek();
      if (token.kind() == Kind.EOF) {
        if (!stopAt.isEmpty()) {
          throw new JinjaException(
              "template " + name + " ended while looking for " + stopAt);
        }
        return body;
      }
      if (token.kind() == Kind.RAW) {
        next();
        body.add(new Node.Raw(token.value()));
        continue;
      }
      if (token.kind() == Kind.VARIABLE_START) {
        next();
        Node expression = expression();
        expect(Kind.VARIABLE_END);
        body.add(new Node.Output(expression));
        continue;
      }
      if (token.kind() == Kind.BLOCK_START) {
        Token keyword = tokens.get(position + 1);
        if (keyword.kind() == Kind.NAME && stopAt.contains(keyword.value())) {
          return body;
        }
        next();
        body.add(statement());
        continue;
      }
      throw new JinjaException("unexpected " + token + " in " + name);
    }
  }

  private Node statement() {
    Token keyword = expect(Kind.NAME);
    switch (keyword.value()) {
      case "if":
        return ifStatement();
      case "for":
        return forStatement();
      case "set":
        return setStatement();
      case "macro":
        return macroStatement();
      case "call":
        return callStatement();
      case "filter":
        return filterStatement();
      case "include":
        return includeStatement();
      case "import":
        return importStatement();
      case "from":
        return fromImportStatement();
      case "extends": {
        Node template = expression();
        expect(Kind.BLOCK_END);
        return new Node.Extends(template);
      }
      case "block":
        return blockStatement();
      case "with":
        return withStatement();
      case "now":
        return nowStatement();
      case "do": {
        Node expression = expression();
        expect(Kind.BLOCK_END);
        return new Node.Output(new Node.Call(new Node.Name("__discard__"),
            List.of(expression), Map.of(), null, null));
      }
      default:
        throw new JinjaException("unknown tag '" + keyword.value() + "' in " + name);
    }
  }

  private Node ifStatement() {
    List<Node.Branch> branches = new ArrayList<>();
    Node condition = expression();
    expect(Kind.BLOCK_END);
    List<Node> body = parseBody(Set.of("elif", "else", "endif"));
    branches.add(new Node.Branch(condition, body));

    List<Node> orElse = new ArrayList<>();
    while (true) {
      expect(Kind.BLOCK_START);
      String keyword = expect(Kind.NAME).value();
      if (keyword.equals("elif")) {
        Node elseIfCondition = expression();
        expect(Kind.BLOCK_END);
        branches.add(
            new Node.Branch(elseIfCondition, parseBody(Set.of("elif", "else", "endif"))));
        continue;
      }
      if (keyword.equals("else")) {
        expect(Kind.BLOCK_END);
        orElse = parseBody(Set.of("endif"));
        expect(Kind.BLOCK_START);
        expect(Kind.NAME);
        expect(Kind.BLOCK_END);
        break;
      }
      expect(Kind.BLOCK_END);
      break;
    }
    return new Node.If(branches, orElse);
  }

  private Node forStatement() {
    List<String> targets = nameList();
    Token in = expect(Kind.NAME);
    if (!in.value().equals("in")) {
      throw new JinjaException("expected 'in' in a for tag, found " + in.value());
    }
    // The iterable is parsed without the inline conditional form, because the "if" that
    // follows it belongs to the loop. Read the other way round, "for x in xs if x > 2" is a
    // loop over a conditional expression, which is empty whenever the condition is false.
    Node iterable = orExpression();
    Node condition = null;
    if (peek().kind() == Kind.NAME && peek().value().equals("if")) {
      next();
      condition = expression();
    }
    boolean recursive = false;
    if (peek().kind() == Kind.NAME && peek().value().equals("recursive")) {
      next();
      recursive = true;
    }
    expect(Kind.BLOCK_END);
    List<Node> body = parseBody(Set.of("else", "endfor"));
    List<Node> orElse = new ArrayList<>();
    expect(Kind.BLOCK_START);
    String keyword = expect(Kind.NAME).value();
    if (keyword.equals("else")) {
      expect(Kind.BLOCK_END);
      orElse = parseBody(Set.of("endfor"));
      expect(Kind.BLOCK_START);
      expect(Kind.NAME);
    }
    expect(Kind.BLOCK_END);
    return new Node.For(targets, iterable, condition, body, orElse, recursive);
  }

  private Node setStatement() {
    List<String> targets = nameList();
    if (peek().kind() == Kind.OPERATOR && peek().value().equals("=")) {
      next();
      Node value = expression();
      if (peek().kind() == Kind.OPERATOR && peek().value().equals(",")) {
        List<Node> items = new ArrayList<>();
        items.add(value);
        while (peek().kind() == Kind.OPERATOR && peek().value().equals(",")) {
          next();
          items.add(expression());
        }
        value = new Node.TupleLiteral(items);
      }
      expect(Kind.BLOCK_END);
      return new Node.Set(targets, value, null, null);
    }
    Node filter = null;
    if (peek().kind() == Kind.OPERATOR && peek().value().equals("|")) {
      next();
      filter = filterChain(new Node.Name("__block__"));
    }
    expect(Kind.BLOCK_END);
    List<Node> body = parseBody(Set.of("endset"));
    expect(Kind.BLOCK_START);
    expect(Kind.NAME);
    expect(Kind.BLOCK_END);
    return new Node.Set(targets, null, body, filter);
  }

  private Node macroStatement() {
    String macroName = expect(Kind.NAME).value();
    List<Node.Parameter> parameters = parameterList();
    expect(Kind.BLOCK_END);
    List<Node> body = parseBody(Set.of("endmacro"));
    expect(Kind.BLOCK_START);
    expect(Kind.NAME);
    expect(Kind.BLOCK_END);
    return new Node.Macro(macroName, parameters, body);
  }

  private Node callStatement() {
    List<Node.Parameter> parameters = new ArrayList<>();
    if (peek().kind() == Kind.OPERATOR && peek().value().equals("(")) {
      parameters = parameterList();
    }
    Node call = expression();
    expect(Kind.BLOCK_END);
    List<Node> body = parseBody(Set.of("endcall"));
    expect(Kind.BLOCK_START);
    expect(Kind.NAME);
    expect(Kind.BLOCK_END);
    return new Node.CallBlock(call, parameters, body);
  }

  private Node filterStatement() {
    Node filter = filterChain(new Node.Name("__block__"));
    expect(Kind.BLOCK_END);
    List<Node> body = parseBody(Set.of("endfilter"));
    expect(Kind.BLOCK_START);
    expect(Kind.NAME);
    expect(Kind.BLOCK_END);
    return new Node.FilterBlock(filter, body);
  }

  private Node includeStatement() {
    Node template = expression();
    boolean ignoreMissing = false;
    boolean withContext = true;
    while (peek().kind() == Kind.NAME) {
      String word = next().value();
      if (word.equals("ignore")) {
        expect(Kind.NAME);
        ignoreMissing = true;
      } else if (word.equals("with")) {
        expect(Kind.NAME);
        withContext = true;
      } else if (word.equals("without")) {
        expect(Kind.NAME);
        withContext = false;
      }
    }
    expect(Kind.BLOCK_END);
    return new Node.Include(template, ignoreMissing, withContext);
  }

  private Node importStatement() {
    Node template = expression();
    expect(Kind.NAME);
    String target = expect(Kind.NAME).value();
    boolean withContext = contextClause();
    expect(Kind.BLOCK_END);
    return new Node.Import(template, target, withContext);
  }

  private Node fromImportStatement() {
    Node template = expression();
    Token importKeyword = expect(Kind.NAME);
    if (!importKeyword.value().equals("import")) {
      throw new JinjaException("expected 'import' after a from tag");
    }
    List<String[]> names = new ArrayList<>();
    while (true) {
      String imported = expect(Kind.NAME).value();
      String alias = imported;
      if (peek().kind() == Kind.NAME && peek().value().equals("as")) {
        next();
        alias = expect(Kind.NAME).value();
      }
      names.add(new String[] {imported, alias});
      if (peek().kind() == Kind.OPERATOR && peek().value().equals(",")) {
        next();
        continue;
      }
      break;
    }
    boolean withContext = contextClause();
    expect(Kind.BLOCK_END);
    return new Node.FromImport(template, names, withContext);
  }

  private boolean contextClause() {
    boolean withContext = false;
    while (peek().kind() == Kind.NAME
        && (peek().value().equals("with") || peek().value().equals("without"))) {
      withContext = next().value().equals("with");
      expect(Kind.NAME);
    }
    return withContext;
  }

  private Node blockStatement() {
    String blockName = expect(Kind.NAME).value();
    boolean scoped = false;
    while (peek().kind() == Kind.NAME) {
      String word = next().value();
      if (word.equals("scoped")) {
        scoped = true;
      }
    }
    expect(Kind.BLOCK_END);
    List<Node> body = parseBody(Set.of("endblock"));
    expect(Kind.BLOCK_START);
    expect(Kind.NAME);
    if (peek().kind() == Kind.NAME) {
      next();
    }
    expect(Kind.BLOCK_END);
    return new Node.Block(blockName, body, scoped);
  }

  private Node withStatement() {
    List<String> targets = new ArrayList<>();
    List<Node> values = new ArrayList<>();
    while (peek().kind() == Kind.NAME) {
      targets.add(next().value());
      expect(Kind.OPERATOR);
      values.add(expression());
      if (peek().kind() == Kind.OPERATOR && peek().value().equals(",")) {
        next();
      }
    }
    expect(Kind.BLOCK_END);
    List<Node> body = parseBody(Set.of("endwith"));
    expect(Kind.BLOCK_START);
    expect(Kind.NAME);
    expect(Kind.BLOCK_END);
    return new Node.With(targets, values, body);
  }

  private Node nowStatement() {
    Node timezone = expression();
    Node offset = null;
    boolean subtract = false;
    if (peek().kind() == Kind.OPERATOR
        && (peek().value().equals("+") || peek().value().equals("-"))) {
      subtract = next().value().equals("-");
      offset = expression();
    }
    Node format = null;
    if (peek().kind() == Kind.OPERATOR && peek().value().equals(",")) {
      next();
      format = expression();
    }
    expect(Kind.BLOCK_END);
    return new Node.Now(timezone, offset, subtract, format);
  }

  private List<String> nameList() {
    List<String> names = new ArrayList<>();
    boolean parenthesised = peek().kind() == Kind.OPERATOR && peek().value().equals("(");
    if (parenthesised) {
      next();
    }
    while (true) {
      names.add(expect(Kind.NAME).value());
      if (peek().kind() == Kind.OPERATOR && peek().value().equals(",")) {
        next();
        continue;
      }
      break;
    }
    if (parenthesised) {
      expect(Kind.OPERATOR);
    }
    return names;
  }

  private List<Node.Parameter> parameterList() {
    List<Node.Parameter> parameters = new ArrayList<>();
    expectOperator("(");
    while (!(peek().kind() == Kind.OPERATOR && peek().value().equals(")"))) {
      String parameterName = expect(Kind.NAME).value();
      Node defaultValue = null;
      if (peek().kind() == Kind.OPERATOR && peek().value().equals("=")) {
        next();
        defaultValue = expression();
      }
      parameters.add(new Node.Parameter(parameterName, defaultValue));
      if (peek().kind() == Kind.OPERATOR && peek().value().equals(",")) {
        next();
      }
    }
    expectOperator(")");
    return parameters;
  }

  // ------------------------------------------------------------ expressions

  public Node expression() {
    return conditional();
  }

  private Node conditional() {
    Node body = orExpression();
    if (peek().kind() == Kind.NAME && peek().value().equals("if")) {
      next();
      Node condition = orExpression();
      Node orElse = null;
      if (peek().kind() == Kind.NAME && peek().value().equals("else")) {
        next();
        orElse = conditional();
      }
      return new Node.Conditional(body, condition, orElse);
    }
    return body;
  }

  private Node orExpression() {
    Node left = andExpression();
    while (peek().kind() == Kind.NAME && peek().value().equals("or")) {
      next();
      left = new Node.Or(left, andExpression());
    }
    return left;
  }

  private Node andExpression() {
    Node left = notExpression();
    while (peek().kind() == Kind.NAME && peek().value().equals("and")) {
      next();
      left = new Node.And(left, notExpression());
    }
    return left;
  }

  private Node notExpression() {
    if (peek().kind() == Kind.NAME && peek().value().equals("not")) {
      next();
      return new Node.Not(notExpression());
    }
    return comparison();
  }

  private Node comparison() {
    Node left = concat();
    List<Object[]> operations = new ArrayList<>();
    while (true) {
      Token token = peek();
      if (token.kind() == Kind.OPERATOR
          && Set.of("==", "!=", "<", ">", "<=", ">=").contains(token.value())) {
        next();
        operations.add(new Object[] {token.value(), concat()});
        continue;
      }
      if (token.kind() == Kind.NAME && token.value().equals("in")) {
        next();
        operations.add(new Object[] {"in", concat()});
        continue;
      }
      if (token.kind() == Kind.NAME && token.value().equals("not")
          && tokens.get(position + 1).kind() == Kind.NAME
          && tokens.get(position + 1).value().equals("in")) {
        next();
        next();
        operations.add(new Object[] {"notin", concat()});
        continue;
      }
      break;
    }
    return operations.isEmpty() ? left : new Node.Compare(left, operations);
  }

  private Node concat() {
    Node left = additive();
    if (peek().kind() == Kind.OPERATOR && peek().value().equals("~")) {
      List<Node> parts = new ArrayList<>();
      parts.add(left);
      while (peek().kind() == Kind.OPERATOR && peek().value().equals("~")) {
        next();
        parts.add(additive());
      }
      return new Node.Concat(parts);
    }
    return left;
  }

  private Node additive() {
    Node left = multiplicative();
    while (peek().kind() == Kind.OPERATOR
        && (peek().value().equals("+") || peek().value().equals("-"))) {
      String operator = next().value();
      left = new Node.Binary(operator, left, multiplicative());
    }
    return left;
  }

  private Node multiplicative() {
    Node left = unary();
    while (peek().kind() == Kind.OPERATOR
        && Set.of("*", "/", "//", "%").contains(peek().value())) {
      String operator = next().value();
      left = new Node.Binary(operator, left, unary());
    }
    return left;
  }

  private Node unary() {
    Token token = peek();
    if (token.kind() == Kind.OPERATOR && (token.value().equals("-") || token.value().equals("+"))) {
      next();
      return new Node.Unary(token.value(), unary());
    }
    return power();
  }

  private Node power() {
    Node left = postfix(primary());
    if (peek().kind() == Kind.OPERATOR && peek().value().equals("**")) {
      next();
      return new Node.Binary("**", left, unary());
    }
    return left;
  }

  private Node postfix(Node target) {
    while (true) {
      Token token = peek();
      if (token.kind() == Kind.OPERATOR && token.value().equals(".")) {
        next();
        Token attribute = next();
        target = new Node.Attribute(target, attribute.value());
        continue;
      }
      if (token.kind() == Kind.OPERATOR && token.value().equals("[")) {
        next();
        target = subscript(target);
        continue;
      }
      if (token.kind() == Kind.OPERATOR && token.value().equals("(")) {
        next();
        target = callArguments(target);
        continue;
      }
      if (token.kind() == Kind.OPERATOR && token.value().equals("|")) {
        next();
        target = filterChain(target);
        continue;
      }
      if (token.kind() == Kind.NAME && token.value().equals("is")) {
        next();
        boolean negated = false;
        if (peek().kind() == Kind.NAME && peek().value().equals("not")) {
          next();
          negated = true;
        }
        String testName = expect(Kind.NAME).value();
        List<Node> arguments = new ArrayList<>();
        if (peek().kind() == Kind.OPERATOR && peek().value().equals("(")) {
          next();
          while (!(peek().kind() == Kind.OPERATOR && peek().value().equals(")"))) {
            arguments.add(expression());
            if (peek().kind() == Kind.OPERATOR && peek().value().equals(",")) {
              next();
            }
          }
          expectOperator(")");
        } else if (canStartExpression(peek()) && !isKeywordBoundary(peek())) {
          arguments.add(concat());
        }
        target = new Node.Test(target, testName, arguments, negated);
        continue;
      }
      return target;
    }
  }

  private boolean isKeywordBoundary(Token token) {
    return token.kind() == Kind.NAME
        && Set.of("and", "or", "not", "if", "else", "in", "is", "recursive").contains(token.value());
  }

  private boolean canStartExpression(Token token) {
    return switch (token.kind()) {
      case NAME, STRING, INTEGER, FLOAT -> true;
      case OPERATOR -> Set.of("(", "[", "{", "-").contains(token.value());
      default -> false;
    };
  }

  private Node subscript(Node target) {
    Node start = null;
    Node stop = null;
    Node step = null;
    boolean isSlice = false;

    if (!(peek().kind() == Kind.OPERATOR && peek().value().equals(":"))) {
      start = expression();
    }
    if (peek().kind() == Kind.OPERATOR && peek().value().equals(":")) {
      isSlice = true;
      next();
      if (!(peek().kind() == Kind.OPERATOR
          && (peek().value().equals("]") || peek().value().equals(":")))) {
        stop = expression();
      }
      if (peek().kind() == Kind.OPERATOR && peek().value().equals(":")) {
        next();
        if (!(peek().kind() == Kind.OPERATOR && peek().value().equals("]"))) {
          step = expression();
        }
      }
    }
    expectOperator("]");
    return isSlice ? new Node.Slice(target, start, stop, step) : new Node.Subscript(target, start);
  }

  private Node callArguments(Node callee) {
    List<Node> positional = new ArrayList<>();
    Map<String, Node> keyword = new LinkedHashMap<>();
    Node varargs = null;
    Node kwargs = null;

    while (!(peek().kind() == Kind.OPERATOR && peek().value().equals(")"))) {
      if (peek().kind() == Kind.OPERATOR && peek().value().equals("**")) {
        next();
        kwargs = expression();
      } else if (peek().kind() == Kind.OPERATOR && peek().value().equals("*")) {
        next();
        varargs = expression();
      } else if (peek().kind() == Kind.NAME
          && tokens.get(position + 1).kind() == Kind.OPERATOR
          && tokens.get(position + 1).value().equals("=")) {
        String argumentName = next().value();
        next();
        keyword.put(argumentName, expression());
      } else {
        positional.add(expression());
      }
      if (peek().kind() == Kind.OPERATOR && peek().value().equals(",")) {
        next();
      }
    }
    expectOperator(")");
    return new Node.Call(callee, positional, keyword, varargs, kwargs);
  }

  private Node filterChain(Node target) {
    String filterName = expect(Kind.NAME).value();
    List<Node> positional = new ArrayList<>();
    Map<String, Node> keyword = new LinkedHashMap<>();
    if (peek().kind() == Kind.OPERATOR && peek().value().equals("(")) {
      next();
      while (!(peek().kind() == Kind.OPERATOR && peek().value().equals(")"))) {
        if (peek().kind() == Kind.NAME
            && tokens.get(position + 1).kind() == Kind.OPERATOR
            && tokens.get(position + 1).value().equals("=")) {
          String argumentName = next().value();
          next();
          keyword.put(argumentName, expression());
        } else {
          positional.add(expression());
        }
        if (peek().kind() == Kind.OPERATOR && peek().value().equals(",")) {
          next();
        }
      }
      expectOperator(")");
    }
    return new Node.Filter(target, filterName, positional, keyword);
  }

  private Node primary() {
    Token token = next();
    switch (token.kind()) {
      case STRING: {
        StringBuilder value = new StringBuilder(token.value());
        while (peek().kind() == Kind.STRING) {
          value.append(next().value());
        }
        return new Node.Literal(value.toString());
      }
      case INTEGER:
        return new Node.Literal(Long.valueOf(token.value()));
      case FLOAT:
        return new Node.Literal(Double.valueOf(token.value()));
      case NAME:
        if (KEYWORD_LITERALS.contains(token.value())) {
          return switch (token.value()) {
            case "true", "True" -> new Node.Literal(Boolean.TRUE);
            case "false", "False" -> new Node.Literal(Boolean.FALSE);
            default -> new Node.Literal(null);
          };
        }
        return new Node.Name(token.value());
      case OPERATOR:
        switch (token.value()) {
          case "(": {
            if (peek().kind() == Kind.OPERATOR && peek().value().equals(")")) {
              next();
              return new Node.TupleLiteral(List.of());
            }
            Node first = expression();
            if (peek().kind() == Kind.OPERATOR && peek().value().equals(",")) {
              List<Node> items = new ArrayList<>();
              items.add(first);
              while (peek().kind() == Kind.OPERATOR && peek().value().equals(",")) {
                next();
                if (peek().kind() == Kind.OPERATOR && peek().value().equals(")")) {
                  break;
                }
                items.add(expression());
              }
              expectOperator(")");
              return new Node.TupleLiteral(items);
            }
            expectOperator(")");
            return first;
          }
          case "[": {
            List<Node> items = new ArrayList<>();
            while (!(peek().kind() == Kind.OPERATOR && peek().value().equals("]"))) {
              items.add(expression());
              if (peek().kind() == Kind.OPERATOR && peek().value().equals(",")) {
                next();
              }
            }
            expectOperator("]");
            return new Node.ListLiteral(items);
          }
          case "{": {
            List<Node[]> entries = new ArrayList<>();
            while (!(peek().kind() == Kind.OPERATOR && peek().value().equals("}"))) {
              Node key = expression();
              expectOperator(":");
              Node value = expression();
              entries.add(new Node[] {key, value});
              if (peek().kind() == Kind.OPERATOR && peek().value().equals(",")) {
                next();
              }
            }
            expectOperator("}");
            return new Node.DictLiteral(entries);
          }
          default:
            break;
        }
        break;
      default:
        break;
    }
    throw new JinjaException("unexpected " + token + " in " + name + " on line " + token.line());
  }

  // ----------------------------------------------------------------- tokens

  private Token peek() {
    return tokens.get(position);
  }

  private Token next() {
    return tokens.get(position++);
  }

  private Token expect(Kind kind) {
    Token token = next();
    if (token.kind() != kind) {
      throw new JinjaException(
          "expected " + kind + " but found " + token + " in " + name + " on line " + token.line());
    }
    return token;
  }

  private void expectOperator(String value) {
    Token token = next();
    if (token.kind() != Kind.OPERATOR || !token.value().equals(value)) {
      throw new JinjaException(
          "expected '" + value + "' but found " + token + " in " + name
              + " on line " + token.line());
    }
  }
}
