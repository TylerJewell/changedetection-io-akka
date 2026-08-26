package io.akka.changedetection.jinja;

import java.util.List;
import java.util.Map;

/** The shapes a parsed template is made of. */
public sealed interface Node {

  // ------------------------------------------------------------- statements

  record Template(List<Node> body, String name) implements Node {}

  record Raw(String text) implements Node {}

  record Output(Node expression) implements Node {}

  record If(List<Branch> branches, List<Node> orElse) implements Node {}

  record Branch(Node condition, List<Node> body) {}

  record For(
      List<String> targets,
      Node iterable,
      Node condition,
      List<Node> body,
      List<Node> orElse,
      boolean recursive)
      implements Node {}

  record Set(List<String> targets, Node value, List<Node> body, Node filter) implements Node {}

  record Macro(String name, List<Parameter> parameters, List<Node> body) implements Node {}

  record Parameter(String name, Node defaultValue) {}

  record CallBlock(Node call, List<Parameter> parameters, List<Node> body) implements Node {}

  record FilterBlock(Node filter, List<Node> body) implements Node {}

  record Include(Node template, boolean ignoreMissing, boolean withContext) implements Node {}

  record Import(Node template, String target, boolean withContext) implements Node {}

  record FromImport(Node template, List<String[]> names, boolean withContext) implements Node {}

  record Extends(Node template) implements Node {}

  record Block(String name, List<Node> body, boolean scoped) implements Node {}

  record With(List<String> targets, List<Node> values, List<Node> body) implements Node {}

  record Now(Node timezone, Node offset, boolean subtract, Node format) implements Node {}

  // ------------------------------------------------------------ expressions

  record Literal(Object value) implements Node {}

  record Name(String name) implements Node {}

  record ListLiteral(List<Node> items) implements Node {}

  record TupleLiteral(List<Node> items) implements Node {}

  record DictLiteral(List<Node[]> entries) implements Node {}

  record Attribute(Node target, String name) implements Node {}

  record Subscript(Node target, Node index) implements Node {}

  record Slice(Node target, Node start, Node stop, Node step) implements Node {}

  record Call(Node callee, List<Node> positional, Map<String, Node> keyword, Node varargs,
      Node kwargs) implements Node {}

  record Filter(Node target, String name, List<Node> positional, Map<String, Node> keyword)
      implements Node {}

  record Test(Node target, String name, List<Node> arguments, boolean negated) implements Node {}

  record Binary(String operator, Node left, Node right) implements Node {}

  record Unary(String operator, Node operand) implements Node {}

  record Compare(Node left, List<Object[]> operations) implements Node {}

  record And(Node left, Node right) implements Node {}

  record Or(Node left, Node right) implements Node {}

  record Not(Node operand) implements Node {}

  record Conditional(Node body, Node condition, Node orElse) implements Node {}

  record Concat(List<Node> parts) implements Node {}
}
