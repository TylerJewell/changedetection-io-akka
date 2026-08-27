package io.akka.changedetection.jinja;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The names a template reads without ever being given.
 *
 * <p>A notification body is written by whoever configured the watch, and a name that does not
 * exist renders as nothing rather than as an error -- so a body that says {@code {{ diffff }}}
 * would silently send an empty notification forever. Naming those at the point the body is saved
 * is what turns that into a message the person can act on.
 */
public final class Undeclared {

  private Undeclared() {}

  /** The names read but never bound, in the order they are first read. */
  public static Set<String> in(Node.Template template) {
    Set<String> found = new LinkedHashSet<>();
    Set<String> bound = new LinkedHashSet<>();
    walkAll(template.body(), bound, found);
    return found;
  }

  private static void walkAll(List<Node> nodes, Set<String> bound, Set<String> found) {
    if (nodes == null) {
      return;
    }
    for (Node node : nodes) {
      walk(node, bound, found);
    }
  }

  private static void walk(Node node, Set<String> bound, Set<String> found) {
    if (node == null) {
      return;
    }
    if (node instanceof Node.Name name) {
      if (!bound.contains(name.name())) {
        found.add(name.name());
      }
      return;
    }
    if (node instanceof Node.Template template) {
      walkAll(template.body(), bound, found);
      return;
    }
    if (node instanceof Node.Raw) {
      return;
    }
    if (node instanceof Node.Literal) {
      return;
    }
    if (node instanceof Node.Output output) {
      walk(output.expression(), bound, found);
      return;
    }
    if (node instanceof Node.If branchNode) {
      for (Node.Branch branch : branchNode.branches()) {
        walk(branch.condition(), bound, found);
        walkAll(branch.body(), bound, found);
      }
      walkAll(branchNode.orElse(), bound, found);
      return;
    }
    if (node instanceof Node.For loop) {
      walk(loop.iterable(), bound, found);
      Set<String> inner = new LinkedHashSet<>(bound);
      inner.addAll(loop.targets());
      inner.add("loop");
      walk(loop.condition(), inner, found);
      walkAll(loop.body(), inner, found);
      walkAll(loop.orElse(), bound, found);
      return;
    }
    if (node instanceof Node.Set assignment) {
      walk(assignment.value(), bound, found);
      walk(assignment.filter(), bound, found);
      walkAll(assignment.body(), bound, found);
      bound.addAll(assignment.targets());
      return;
    }
    if (node instanceof Node.Macro macro) {
      bound.add(macro.name());
      Set<String> inner = new LinkedHashSet<>(bound);
      for (Node.Parameter parameter : macro.parameters()) {
        walk(parameter.defaultValue(), bound, found);
        inner.add(parameter.name());
      }
      inner.add("caller");
      inner.add("varargs");
      inner.add("kwargs");
      walkAll(macro.body(), inner, found);
      return;
    }
    if (node instanceof Node.CallBlock callBlock) {
      walk(callBlock.call(), bound, found);
      Set<String> inner = new LinkedHashSet<>(bound);
      for (Node.Parameter parameter : callBlock.parameters()) {
        walk(parameter.defaultValue(), bound, found);
        inner.add(parameter.name());
      }
      walkAll(callBlock.body(), inner, found);
      return;
    }
    if (node instanceof Node.FilterBlock filterBlock) {
      walk(filterBlock.filter(), bound, found);
      walkAll(filterBlock.body(), bound, found);
      return;
    }
    if (node instanceof Node.Include include) {
      walk(include.template(), bound, found);
      return;
    }
    if (node instanceof Node.Import importNode) {
      walk(importNode.template(), bound, found);
      bound.add(importNode.target());
      return;
    }
    if (node instanceof Node.FromImport fromImport) {
      walk(fromImport.template(), bound, found);
      for (String[] pair : fromImport.names()) {
        bound.add(pair[1] == null ? pair[0] : pair[1]);
      }
      return;
    }
    if (node instanceof Node.Extends extendsNode) {
      walk(extendsNode.template(), bound, found);
      return;
    }
    if (node instanceof Node.Block block) {
      walkAll(block.body(), bound, found);
      return;
    }
    if (node instanceof Node.With with) {
      walkAll(with.values(), bound, found);
      Set<String> inner = new LinkedHashSet<>(bound);
      inner.addAll(with.targets());
      walkAll(with.body(), inner, found);
      return;
    }
    if (node instanceof Node.Now now) {
      walk(now.timezone(), bound, found);
      walk(now.offset(), bound, found);
      walk(now.format(), bound, found);
      return;
    }
    if (node instanceof Node.ListLiteral list) {
      walkAll(list.items(), bound, found);
      return;
    }
    if (node instanceof Node.TupleLiteral tuple) {
      walkAll(tuple.items(), bound, found);
      return;
    }
    if (node instanceof Node.DictLiteral dict) {
      for (Node[] entry : dict.entries()) {
        walk(entry[0], bound, found);
        walk(entry[1], bound, found);
      }
      return;
    }
    if (node instanceof Node.Attribute attribute) {
      walk(attribute.target(), bound, found);
      return;
    }
    if (node instanceof Node.Subscript subscript) {
      walk(subscript.target(), bound, found);
      walk(subscript.index(), bound, found);
      return;
    }
    if (node instanceof Node.Slice slice) {
      walk(slice.target(), bound, found);
      walk(slice.start(), bound, found);
      walk(slice.stop(), bound, found);
      walk(slice.step(), bound, found);
      return;
    }
    if (node instanceof Node.Call call) {
      walk(call.callee(), bound, found);
      walkAll(call.positional(), bound, found);
      walkAll(values(call.keyword()), bound, found);
      walk(call.varargs(), bound, found);
      walk(call.kwargs(), bound, found);
      return;
    }
    if (node instanceof Node.Filter filter) {
      walk(filter.target(), bound, found);
      walkAll(filter.positional(), bound, found);
      walkAll(values(filter.keyword()), bound, found);
      return;
    }
    if (node instanceof Node.Test test) {
      walk(test.target(), bound, found);
      walkAll(test.arguments(), bound, found);
      return;
    }
    if (node instanceof Node.Binary binary) {
      walk(binary.left(), bound, found);
      walk(binary.right(), bound, found);
      return;
    }
    if (node instanceof Node.Unary unary) {
      walk(unary.operand(), bound, found);
      return;
    }
    if (node instanceof Node.Compare compare) {
      walk(compare.left(), bound, found);
      for (Object[] operation : compare.operations()) {
        walk((Node) operation[1], bound, found);
      }
      return;
    }
    if (node instanceof Node.And and) {
      walk(and.left(), bound, found);
      walk(and.right(), bound, found);
      return;
    }
    if (node instanceof Node.Or or) {
      walk(or.left(), bound, found);
      walk(or.right(), bound, found);
      return;
    }
    if (node instanceof Node.Not not) {
      walk(not.operand(), bound, found);
      return;
    }
    if (node instanceof Node.Conditional conditional) {
      walk(conditional.condition(), bound, found);
      walk(conditional.body(), bound, found);
      walk(conditional.orElse(), bound, found);
      return;
    }
    if (node instanceof Node.Concat concat) {
      walkAll(concat.parts(), bound, found);
    }
  }

  private static List<Node> values(Map<String, Node> keyword) {
    List<Node> nodes = new ArrayList<>();
    if (keyword != null) {
      nodes.addAll(keyword.values());
    }
    return nodes;
  }
}
