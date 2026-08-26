package io.akka.changedetection.jinja;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** A parsed template turned into output. */
public final class Interpreter {

  private final Environment environment;

  public Interpreter(Environment environment) {
    this.environment = environment;
  }

  /** The names in scope, chained so an inner scope can read an outer one but not write it. */
  static final class Scope {
    private final Scope parent;
    private final Map<String, Object> values = new LinkedHashMap<>();

    Scope(Scope parent) {
      this.parent = parent;
    }

    Object get(String name) {
      Scope scope = this;
      while (scope != null) {
        if (scope.values.containsKey(name)) {
          return scope.values.get(name);
        }
        scope = scope.parent;
      }
      return null;
    }

    boolean has(String name) {
      Scope scope = this;
      while (scope != null) {
        if (scope.values.containsKey(name)) {
          return true;
        }
        scope = scope.parent;
      }
      return false;
    }

    void put(String name, Object value) {
      values.put(name, value);
    }

    /**
     * Writes to whichever scope already holds the name.
     *
     * <p>A template that sets a running total before a loop and adds to it inside the loop
     * expects the total to survive the loop. Writing to the innermost scope would leave the
     * outer one untouched and the total would come out as whatever it was before.
     */
    void assign(String name, Object value) {
      Scope scope = this;
      while (scope != null) {
        if (scope.values.containsKey(name)) {
          scope.values.put(name, value);
          return;
        }
        scope = scope.parent;
      }
      values.put(name, value);
    }

    Map<String, Object> flatten() {
      Map<String, Object> out = new LinkedHashMap<>();
      if (parent != null) {
        out.putAll(parent.flatten());
      }
      out.putAll(values);
      return out;
    }
  }

  /** What a template is producing while it runs. */
  private static final class Frame {
    final StringBuilder out = new StringBuilder();
    final Map<String, List<Node>> blocks = new LinkedHashMap<>();
    final Map<String, Integer> blockDepth = new LinkedHashMap<>();
    String extendsName;
    Map<String, Object> extendsContext;
  }

  /** A macro as something a template can call. */
  final class MacroValue implements PyValue.Callable, PyValue.Attributed {
    private final String name;
    private final List<Node.Parameter> parameters;
    private final List<Node> body;
    private final Scope closure;

    MacroValue(String name, List<Node.Parameter> parameters, List<Node> body, Scope closure) {
      this.name = name;
      this.parameters = parameters;
      this.body = body;
      this.closure = closure;
    }

    @Override
    public Object call(List<Object> positional, Map<String, Object> keyword) {
      Scope scope = new Scope(closure);
      Map<String, Object> leftover = new LinkedHashMap<>(keyword);
      for (int i = 0; i < parameters.size(); i++) {
        Node.Parameter parameter = parameters.get(i);
        Object value;
        if (i < positional.size()) {
          value = positional.get(i);
        } else if (leftover.containsKey(parameter.name())) {
          value = leftover.remove(parameter.name());
        } else if (parameter.defaultValue() != null) {
          value = evaluate(parameter.defaultValue(), scope);
        } else {
          value = PyValue.UNDEFINED;
        }
        scope.put(parameter.name(), value);
      }
      // Whatever the caller passed and the macro did not declare is available as `kwargs`,
      // which is how every field-rendering macro in this interface forwards markup attributes
      // straight through to the widget it wraps.
      scope.put("kwargs", leftover);
      if (!callers.isEmpty()) {
        scope.put("caller", callers.peek());
      }
      scope.put("varargs", positional.size() > parameters.size()
          ? new ArrayList<>(positional.subList(parameters.size(), positional.size()))
          : new ArrayList<>());

      Frame frame = new Frame();
      execute(body, scope, frame);
      return new PyValue.Markup(frame.out.toString());
    }

    @Override
    public Object attribute(String attributeName) {
      if (attributeName.equals("name")) {
        return name;
      }
      return PyValue.UNDEFINED;
    }
  }

  /** Block definitions along the inheritance chain, most-derived first. */
  private Map<String, List<List<Node>>> overrides = new LinkedHashMap<>();

  /** How far down the chain each block is currently rendering, for a super call. */
  private Map<String, Integer> blockDepth = new LinkedHashMap<>();

  /** Every block a template defines, including ones nested inside other statements. */
  private void collectBlocks(List<Node> body, Map<String, List<Node>> into) {
    for (Node node : body) {
      if (node instanceof Node.Block block) {
        into.putIfAbsent(block.name(), block.body());
        collectBlocks(block.body(), into);
      } else if (node instanceof Node.If ifNode) {
        for (Node.Branch branch : ifNode.branches()) {
          collectBlocks(branch.body(), into);
        }
        collectBlocks(ifNode.orElse(), into);
      } else if (node instanceof Node.For forNode) {
        collectBlocks(forNode.body(), into);
        collectBlocks(forNode.orElse(), into);
      } else if (node instanceof Node.With withNode) {
        collectBlocks(withNode.body(), into);
      } else if (node instanceof Node.FilterBlock filterBlock) {
        collectBlocks(filterBlock.body(), into);
      }
    }
  }

  public String render(Node.Template template, Map<String, Object> context) {
    Scope scope = rootScope();
    if (context != null) {
      for (Map.Entry<String, Object> entry : context.entrySet()) {
        scope.put(entry.getKey(), entry.getValue());
      }
    }
    return renderChain(template, scope);
  }

  /**
   * A template and everything it extends, rendered as one.
   *
   * <p>A child template does not produce output of its own: it supplies blocks, and the
   * outermost ancestor is what is rendered. So the chain is walked to the top first, the
   * block definitions are stacked most-derived first, and only the top of the chain is put
   * into the output. The stacking is what makes a super call work -- it renders the next
   * definition down.
   */
  private String renderChain(Node.Template template, Scope scope) {
    Map<String, List<List<Node>>> chain = new LinkedHashMap<>();
    List<Node.Template> ancestry = new ArrayList<>();
    Node.Template current = template;

    while (current != null) {
      ancestry.add(current);
      Map<String, List<Node>> local = new LinkedHashMap<>();
      collectBlocks(current.body(), local);
      for (Map.Entry<String, List<Node>> entry : local.entrySet()) {
        chain.computeIfAbsent(entry.getKey(), key -> new ArrayList<>()).add(entry.getValue());
      }
      String parent = findExtends(current.body(), scope);
      current = parent == null ? null : environment.template(parent);
    }

    Map<String, List<List<Node>>> previousOverrides = overrides;
    Map<String, Integer> previousDepth = blockDepth;
    overrides = chain;
    blockDepth = new LinkedHashMap<>();
    try {
      // Every template that only supplies blocks still runs its own top-level statements,
      // so a macro or a value it defines outside a block is in scope by the time the
      // outermost one renders. Their output is not part of the page and is discarded.
      for (int i = ancestry.size() - 2; i >= 0; i--) {
        execute(ancestry.get(i).body(), scope, new Frame());
      }
      Frame frame = new Frame();
      execute(ancestry.get(ancestry.size() - 1).body(), scope, frame);
      return frame.out.toString();
    } finally {
      overrides = previousOverrides;
      blockDepth = previousDepth;
    }
  }

  private String findExtends(List<Node> body, Scope scope) {
    for (Node node : body) {
      if (node instanceof Node.Extends extendsNode) {
        return PyValue.asString(evaluate(extendsNode.template(), scope));
      }
    }
    return null;
  }

  private void execute(List<Node> body, Scope scope, Frame frame) {
    for (Node node : body) {
      executeOne(node, scope, frame);
    }
  }

  private void executeOne(Node node, Scope scope, Frame frame) {
    if (node instanceof Node.Raw raw) {
      frame.out.append(raw.text());
      return;
    }
    if (node instanceof Node.Output output) {
      Object value = evaluate(output.expression(), scope);
      if (value == PyValue.UNDEFINED) {
        return;
      }
      frame.out.append(finish(value));
      return;
    }
    if (node instanceof Node.If ifNode) {
      for (Node.Branch branch : ifNode.branches()) {
        if (PyValue.truthy(evaluate(branch.condition(), scope))) {
          // A conditional opens no scope: a value set inside one is still set after it.
          execute(branch.body(), scope, frame);
          return;
        }
      }
      execute(ifNode.orElse(), scope, frame);
      return;
    }
    if (node instanceof Node.For forNode) {
      executeFor(forNode, scope, frame);
      return;
    }
    if (node instanceof Node.Set setNode) {
      executeSet(setNode, scope, frame);
      return;
    }
    if (node instanceof Node.Macro macro) {
      scope.assign(macro.name(), new MacroValue(macro.name(), macro.parameters(), macro.body(), scope));
      return;
    }
    if (node instanceof Node.CallBlock callBlock) {
      executeCallBlock(callBlock, scope, frame);
      return;
    }
    if (node instanceof Node.FilterBlock filterBlock) {
      Frame inner = new Frame();
      execute(filterBlock.body(), new Scope(scope), inner);
      Scope filterScope = new Scope(scope);
      filterScope.put("__block__", new PyValue.Markup(inner.out.toString()));
      frame.out.append(finish(evaluate(filterBlock.filter(), filterScope)));
      return;
    }
    if (node instanceof Node.Include include) {
      executeInclude(include, scope, frame);
      return;
    }
    if (node instanceof Node.Import importNode) {
      Node.Template imported = environment.template(PyValue.asString(evaluate(importNode.template(), scope)));
      scope.assign(importNode.target(), moduleOf(imported, scope, importNode.withContext()));
      return;
    }
    if (node instanceof Node.FromImport fromImport) {
      Node.Template imported =
          environment.template(PyValue.asString(evaluate(fromImport.template(), scope)));
      Map<String, Object> module = moduleOf(imported, scope, fromImport.withContext());
      for (String[] pair : fromImport.names()) {
        scope.assign(pair[1], module.getOrDefault(pair[0], PyValue.UNDEFINED));
      }
      return;
    }
    if (node instanceof Node.Extends extendsNode) {
      frame.extendsName = PyValue.asString(evaluate(extendsNode.template(), scope));
      return;
    }
    if (node instanceof Node.Block block) {
      executeBlock(block, scope, frame);
      return;
    }
    if (node instanceof Node.With withNode) {
      Scope inner = new Scope(scope);
      for (int i = 0; i < withNode.targets().size(); i++) {
        inner.put(withNode.targets().get(i), evaluate(withNode.values().get(i), scope));
      }
      execute(withNode.body(), inner, frame);
      return;
    }
    if (node instanceof Node.Now now) {
      frame.out.append(renderNow(now, scope));
      return;
    }
    throw new JinjaException("cannot execute " + node.getClass().getSimpleName());
  }

  private void executeFor(Node.For forNode, Scope scope, Frame frame) {
    List<Object> items = PyValue.iterate(evaluate(forNode.iterable(), scope));
    if (forNode.condition() != null) {
      List<Object> kept = new ArrayList<>();
      for (Object item : items) {
        Scope test = new Scope(scope);
        bind(forNode.targets(), item, test);
        if (PyValue.truthy(evaluate(forNode.condition(), test))) {
          kept.add(item);
        }
      }
      items = kept;
    }
    if (items.isEmpty()) {
      execute(forNode.orElse(), new Scope(scope), frame);
      return;
    }
    for (int index = 0; index < items.size(); index++) {
      Scope inner = new Scope(scope);
      bind(forNode.targets(), items.get(index), inner);
      Map<String, Object> loop = new LinkedHashMap<>();
      loop.put("index", (long) index + 1);
      loop.put("index0", (long) index);
      loop.put("revindex", (long) items.size() - index);
      loop.put("revindex0", (long) items.size() - index - 1);
      loop.put("first", index == 0);
      loop.put("last", index == items.size() - 1);
      loop.put("length", (long) items.size());
      loop.put("previtem", index > 0 ? items.get(index - 1) : PyValue.UNDEFINED);
      loop.put("nextitem", index < items.size() - 1 ? items.get(index + 1) : PyValue.UNDEFINED);
      inner.put("loop", loop);
      execute(forNode.body(), inner, frame);
    }
  }

  private void bind(List<String> targets, Object item, Scope scope) {
    if (targets.size() == 1) {
      scope.put(targets.get(0), item);
      return;
    }
    List<Object> parts = item instanceof Object[] array ? List.of(array) : PyValue.iterate(item);
    for (int i = 0; i < targets.size(); i++) {
      scope.put(targets.get(i), i < parts.size() ? parts.get(i) : PyValue.UNDEFINED);
    }
  }

  private void executeSet(Node.Set setNode, Scope scope, Frame frame) {
    Object value;
    if (setNode.value() != null) {
      value = evaluate(setNode.value(), scope);
    } else {
      Frame inner = new Frame();
      execute(setNode.body(), new Scope(scope), inner);
      value = new PyValue.Markup(inner.out.toString());
      if (setNode.filter() != null) {
        Scope filterScope = new Scope(scope);
        filterScope.put("__block__", value);
        value = evaluate(setNode.filter(), filterScope);
      }
    }
    // The write lands in the scope the tag is in, and a loop body is a scope of its own --
    // so a total accumulated inside a loop does not survive it. That is what the original
    // does, and a template written against it uses a namespace object where it needs one.
    if (setNode.targets().size() == 1) {
      scope.put(setNode.targets().get(0), value);
      return;
    }
    List<Object> parts = PyValue.iterate(value);
    for (int i = 0; i < setNode.targets().size(); i++) {
      scope.put(setNode.targets().get(i), i < parts.size() ? parts.get(i) : PyValue.UNDEFINED);
    }
  }

  /** The body a call tag wraps, waiting for the macro it was given to invoke it. */
  private final java.util.ArrayDeque<Object> callers = new java.util.ArrayDeque<>();

  private void executeCallBlock(Node.CallBlock callBlock, Scope scope, Frame frame) {
    MacroValue caller =
        new MacroValue("caller", callBlock.parameters(), callBlock.body(), scope);
    callers.push(caller);
    try {
      frame.out.append(finish(evaluate(callBlock.call(), scope)));
    } finally {
      callers.pop();
    }
  }

  private void executeInclude(Node.Include include, Scope scope, Frame frame) {
    Object nameValue = evaluate(include.template(), scope);
    List<Object> candidates = nameValue instanceof List<?> list
        ? new ArrayList<>(list) : List.of(nameValue);
    for (Object candidate : candidates) {
      try {
        Node.Template template = environment.template(PyValue.asString(candidate));
        Scope inner = include.withContext() ? new Scope(scope) : rootScope();
        frame.out.append(renderChain(template, inner));
        return;
      } catch (JinjaException e) {
        if (!include.ignoreMissing() && candidates.size() == 1) {
          throw e;
        }
      }
    }
    if (!include.ignoreMissing()) {
      throw new JinjaException("no template found among " + candidates);
    }
  }

  private Scope rootScope() {
    Scope scope = new Scope(null);
    for (Map.Entry<String, Object> entry : environment.globals().entrySet()) {
      scope.put(entry.getKey(), entry.getValue());
    }
    return scope;
  }

  private Map<String, Object> moduleOf(Node.Template template, Scope scope, boolean withContext) {
    Scope inner = withContext ? new Scope(scope) : rootScope();
    Frame frame = new Frame();
    execute(template.body(), inner, frame);
    return inner.flatten();
  }

  private void executeBlock(Node.Block block, Scope scope, Frame frame) {
    List<List<Node>> stack = overrides.get(block.name());
    int depth = blockDepth.getOrDefault(block.name(), 0);
    List<Node> body =
        stack != null && depth < stack.size() ? stack.get(depth) : block.body();

    Scope inner = new Scope(scope);
    inner.put("super", (PyValue.Callable) (positional, keyword) -> {
      if (stack == null || depth + 1 >= stack.size()) {
        return new PyValue.Markup("");
      }
      blockDepth.put(block.name(), depth + 1);
      try {
        Frame superFrame = new Frame();
        execute(stack.get(depth + 1), new Scope(scope), superFrame);
        return new PyValue.Markup(superFrame.out.toString());
      } finally {
        blockDepth.put(block.name(), depth);
      }
    });
    execute(body, inner, frame);
  }

  private String renderNow(Node.Now now, Scope scope) {
    String timezone = now.timezone() == null ? null : PyValue.asString(evaluate(now.timezone(), scope));
    ZonedDateTime moment = environment.now(timezone);
    if (now.offset() != null) {
      String offset = PyValue.asString(evaluate(now.offset(), scope));
      moment = applyOffset(moment, offset, now.subtract());
    }
    String format = now.format() == null
        ? environment.datetimeFormat()
        : PyValue.asString(evaluate(now.format(), scope));
    return strftime(moment, format);
  }

  private ZonedDateTime applyOffset(ZonedDateTime moment, String offset, boolean subtract) {
    ZonedDateTime result = moment;
    for (String part : offset.split(",")) {
      String[] pair = part.split("=", 2);
      if (pair.length != 2) {
        continue;
      }
      String unit = pair[0].strip();
      long amount;
      try {
        amount = Long.parseLong(pair[1].strip());
      } catch (NumberFormatException e) {
        continue;
      }
      long signed = subtract ? -amount : amount;
      result = switch (unit) {
        case "years" -> result.plusYears(signed);
        case "months" -> result.plusMonths(signed);
        case "weeks" -> result.plusWeeks(signed);
        case "days" -> result.plusDays(signed);
        case "hours" -> result.plusHours(signed);
        case "minutes" -> result.plusMinutes(signed);
        case "seconds" -> result.plusSeconds(signed);
        case "microseconds" -> result.plusNanos(signed * 1000);
        case "weekday" -> result.with(
            java.time.temporal.TemporalAdjusters.nextOrSame(
                java.time.DayOfWeek.of((int) amount + 1)));
        default -> result;
      };
    }
    return result;
  }

  /** The date formatting codes the templates use. */
  public static String strftime(ZonedDateTime moment, String format) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < format.length(); i++) {
      char c = format.charAt(i);
      if (c != '%' || i + 1 >= format.length()) {
        sb.append(c);
        continue;
      }
      char code = format.charAt(++i);
      sb.append(switch (code) {
        case 'a' -> moment.format(DateTimeFormatter.ofPattern("EEE", Locale.ENGLISH));
        case 'A' -> moment.format(DateTimeFormatter.ofPattern("EEEE", Locale.ENGLISH));
        case 'b' -> moment.format(DateTimeFormatter.ofPattern("MMM", Locale.ENGLISH));
        case 'B' -> moment.format(DateTimeFormatter.ofPattern("MMMM", Locale.ENGLISH));
        case 'd' -> String.format("%02d", moment.getDayOfMonth());
        case 'H' -> String.format("%02d", moment.getHour());
        case 'I' -> String.format("%02d", (moment.getHour() % 12 == 0 ? 12 : moment.getHour() % 12));
        case 'j' -> String.format("%03d", moment.getDayOfYear());
        case 'm' -> String.format("%02d", moment.getMonthValue());
        case 'M' -> String.format("%02d", moment.getMinute());
        case 'p' -> moment.getHour() < 12 ? "AM" : "PM";
        case 'S' -> String.format("%02d", moment.getSecond());
        case 'y' -> String.format("%02d", moment.getYear() % 100);
        case 'Y' -> String.valueOf(moment.getYear());
        case 'Z' -> moment.getZone().getDisplayName(java.time.format.TextStyle.SHORT, Locale.ENGLISH);
        case 'z' -> moment.format(DateTimeFormatter.ofPattern("Z"));
        case 's' -> String.valueOf(moment.toEpochSecond());
        case '%' -> "%";
        default -> "%" + code;
      });
    }
    return sb.toString();
  }

  private String finish(Object value) {
    if (value instanceof PyValue.Markup markup) {
      return markup.value();
    }
    String text = PyValue.asString(value);
    return environment.autoescape() ? Filters.escapeHtml(text) : text;
  }

  // ----------------------------------------------------------- expressions

  Object evaluate(Node node, Scope scope) {
    if (node instanceof Node.Literal literal) {
      return literal.value();
    }
    if (node instanceof Node.Name name) {
      if (scope.has(name.name())) {
        return scope.get(name.name());
      }
      return environment.global(name.name());
    }
    if (node instanceof Node.ListLiteral list) {
      List<Object> out = new ArrayList<>();
      for (Node item : list.items()) {
        out.add(evaluate(item, scope));
      }
      return out;
    }
    if (node instanceof Node.TupleLiteral tuple) {
      List<Object> out = new ArrayList<>();
      for (Node item : tuple.items()) {
        out.add(evaluate(item, scope));
      }
      return out;
    }
    if (node instanceof Node.DictLiteral dict) {
      Map<Object, Object> out = new LinkedHashMap<>();
      for (Node[] entry : dict.entries()) {
        out.put(evaluate(entry[0], scope), evaluate(entry[1], scope));
      }
      return out;
    }
    if (node instanceof Node.Attribute attribute) {
      return PyValue.getAttribute(evaluate(attribute.target(), scope), attribute.name());
    }
    if (node instanceof Node.Subscript subscript) {
      return PyValue.getItem(evaluate(subscript.target(), scope), evaluate(subscript.index(), scope));
    }
    if (node instanceof Node.Slice slice) {
      return evaluateSlice(slice, scope);
    }
    if (node instanceof Node.Call call) {
      return evaluateCall(call, scope);
    }
    if (node instanceof Node.Filter filter) {
      Object value = evaluate(filter.target(), scope);
      List<Object> positional = new ArrayList<>();
      for (Node argument : filter.positional()) {
        positional.add(evaluate(argument, scope));
      }
      Map<String, Object> keyword = new LinkedHashMap<>();
      for (Map.Entry<String, Node> entry : filter.keyword().entrySet()) {
        keyword.put(entry.getKey(), evaluate(entry.getValue(), scope));
      }
      return environment.applyFilter(filter.name(), value, positional, keyword);
    }
    if (node instanceof Node.Test test) {
      Object value = evaluate(test.target(), scope);
      List<Object> arguments = new ArrayList<>();
      for (Node argument : test.arguments()) {
        arguments.add(evaluate(argument, scope));
      }
      boolean result = environment.applyTest(test.name(), value, arguments);
      return test.negated() != result;
    }
    if (node instanceof Node.Binary binary) {
      Object left = evaluate(binary.left(), scope);
      Object right = evaluate(binary.right(), scope);
      return binary.operator().equals("+")
          ? PyValue.add(left, right)
          : PyValue.arithmetic(left, right, binary.operator());
    }
    if (node instanceof Node.Unary unary) {
      Object operand = evaluate(unary.operand(), scope);
      if (unary.operator().equals("-")) {
        return PyValue.isIntegral(operand)
            ? (Object) (-Filters.toLong(operand, 0L))
            : (Object) (-PyValue.toDouble(operand));
      }
      return operand;
    }
    if (node instanceof Node.Compare compare) {
      Object left = evaluate(compare.left(), scope);
      for (Object[] operation : compare.operations()) {
        Object right = evaluate((Node) operation[1], scope);
        if (!compareOne(left, (String) operation[0], right)) {
          return Boolean.FALSE;
        }
        left = right;
      }
      return Boolean.TRUE;
    }
    if (node instanceof Node.And and) {
      Object left = evaluate(and.left(), scope);
      return PyValue.truthy(left) ? evaluate(and.right(), scope) : left;
    }
    if (node instanceof Node.Or or) {
      Object left = evaluate(or.left(), scope);
      return PyValue.truthy(left) ? left : evaluate(or.right(), scope);
    }
    if (node instanceof Node.Not not) {
      return !PyValue.truthy(evaluate(not.operand(), scope));
    }
    if (node instanceof Node.Conditional conditional) {
      if (PyValue.truthy(evaluate(conditional.condition(), scope))) {
        return evaluate(conditional.body(), scope);
      }
      return conditional.orElse() == null
          ? PyValue.UNDEFINED
          : evaluate(conditional.orElse(), scope);
    }
    if (node instanceof Node.Concat concat) {
      StringBuilder sb = new StringBuilder();
      for (Node part : concat.parts()) {
        sb.append(PyValue.asString(evaluate(part, scope)));
      }
      return sb.toString();
    }
    throw new JinjaException("cannot evaluate " + node.getClass().getSimpleName());
  }

  private boolean compareOne(Object left, String operator, Object right) {
    return switch (operator) {
      case "==" -> PyValue.equal(left, right);
      case "!=" -> !PyValue.equal(left, right);
      case "<" -> PyValue.compare(left, right) < 0;
      case "<=" -> PyValue.compare(left, right) <= 0;
      case ">" -> PyValue.compare(left, right) > 0;
      case ">=" -> PyValue.compare(left, right) >= 0;
      case "in" -> PyValue.contains(right, left);
      case "notin" -> !PyValue.contains(right, left);
      default -> throw new JinjaException("unknown comparison " + operator);
    };
  }

  private Object evaluateSlice(Node.Slice slice, Scope scope) {
    Object target = evaluate(slice.target(), scope);
    List<Object> items = target instanceof CharSequence
        ? null
        : PyValue.iterate(target);
    int size = target instanceof CharSequence s ? s.length() : items.size();

    Integer start = slice.start() == null ? null : (int) Filters.toLong(evaluate(slice.start(), scope), 0L);
    Integer stop = slice.stop() == null ? null : (int) Filters.toLong(evaluate(slice.stop(), scope), 0L);
    int step = slice.step() == null ? 1 : (int) Filters.toLong(evaluate(slice.step(), scope), 1L);

    int from = start == null ? (step > 0 ? 0 : size - 1) : (start < 0 ? size + start : start);
    int to = stop == null ? (step > 0 ? size : -1) : (stop < 0 ? size + stop : stop);
    from = Math.max(step > 0 ? 0 : -1, Math.min(from, step > 0 ? size : size - 1));
    to = Math.max(step > 0 ? 0 : -1, Math.min(to, size));

    if (target instanceof CharSequence text) {
      StringBuilder sb = new StringBuilder();
      for (int i = from; step > 0 ? i < to : i > to; i += step) {
        if (i >= 0 && i < size) {
          sb.append(text.charAt(i));
        }
      }
      return sb.toString();
    }
    List<Object> out = new ArrayList<>();
    for (int i = from; step > 0 ? i < to : i > to; i += step) {
      if (i >= 0 && i < size) {
        out.add(items.get(i));
      }
    }
    return out;
  }

  private Object evaluateCall(Node.Call call, Scope scope) {
    Object callee = evaluate(call.callee(), scope);
    List<Object> positional = new ArrayList<>();
    for (Node argument : call.positional()) {
      positional.add(evaluate(argument, scope));
    }
    if (call.varargs() != null) {
      positional.addAll(PyValue.iterate(evaluate(call.varargs(), scope)));
    }
    Map<String, Object> keyword = new LinkedHashMap<>();
    for (Map.Entry<String, Node> entry : call.keyword().entrySet()) {
      keyword.put(entry.getKey(), evaluate(entry.getValue(), scope));
    }
    if (call.kwargs() != null) {
      Object extra = evaluate(call.kwargs(), scope);
      if (extra instanceof Map<?, ?> map) {
        for (Map.Entry<?, ?> entry : map.entrySet()) {
          keyword.put(PyValue.asString(entry.getKey()), entry.getValue());
        }
      }
    }
    if (callee instanceof PyValue.Callable callable) {
      return callable.call(positional, keyword);
    }
    if (callee == PyValue.UNDEFINED) {
      throw new JinjaException("cannot call an undefined value");
    }
    throw new JinjaException("cannot call " + PyValue.repr(callee));
  }

}
