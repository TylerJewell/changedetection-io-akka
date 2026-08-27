package io.akka.changedetection.forms;

import io.akka.changedetection.jinja.Filters;
import io.akka.changedetection.jinja.PyValue;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One control on a form, as the shipped templates expect to find it.
 *
 * <p>The templates render a control by <em>calling</em> it and passing markup attributes --
 * {@code field(class="x")} -- and read {@code field.label}, {@code field.errors},
 * {@code field.id} and {@code field.type} around it. Reproducing that shape is what lets the
 * templates be shipped unchanged; a control that were merely a value would need every one of
 * them rewritten.
 */
public abstract class Field implements PyValue.Callable, PyValue.Attributed {

  /** The label, which the templates print and also read the text of. */
  public static final class Label implements PyValue.Attributed, CharSequence {
    private final String forId;
    private final String text;

    Label(String forId, String text) {
      this.forId = forId;
      this.text = text;
    }

    public String text() {
      return text;
    }

    @Override
    public Object attribute(String name) {
      return switch (name) {
        case "text" -> text;
        case "field_id" -> forId;
        default -> PyValue.UNDEFINED;
      };
    }

    private String rendered() {
      return "<label for=\"" + Filters.escapeHtml(forId) + "\">"
          + Filters.escapeHtml(text) + "</label>";
    }

    @Override
    public int length() {
      return rendered().length();
    }

    @Override
    public char charAt(int index) {
      return rendered().charAt(index);
    }

    @Override
    public CharSequence subSequence(int start, int end) {
      return rendered().subSequence(start, end);
    }

    @Override
    public String toString() {
      return rendered();
    }
  }

  protected String name;
  protected String label;
  protected String description = "";
  protected Object data;
  protected final List<String> errors = new ArrayList<>();
  /** Messages raised while reading the submission, which outlive a later re-check. */
  protected final List<String> processErrors = new ArrayList<>();
  protected boolean required;
  protected Map<String, String> renderAttributes = new LinkedHashMap<>();
  /** The enclosing group's prefix; empty at the top level. */
  protected String qualifier = "";
  /** Flags a template sets on a control just before rendering it. */
  protected final Map<String, Object> templateFlags = new LinkedHashMap<>();

  protected Field(String name, String label) {
    this.name = name;
    this.label = label;
  }

  public String name() {
    return name;
  }

  /**
   * What the control is called in the markup, and therefore in a submission.
   *
   * <p>A control inside a group carries the group's prefix, which is what lets a submission be
   * split back into groups without the server knowing the layout in advance.
   */
  public String id() {
    return qualifier.isEmpty() ? name : qualifier + "-" + name;
  }

  void setQualifier(String qualifier) {
    this.qualifier = qualifier == null ? "" : qualifier;
  }

  public String labelText() {
    return label;
  }

  public void setLabelText(String text) {
    this.label = text;
  }

  public Object flag(String flagName) {
    return templateFlags.get(flagName);
  }

  public Object data() {
    return data;
  }

  public void setData(Object data) {
    this.data = data;
  }

  public List<String> errors() {
    return errors;
  }

  /**
   * One rule a value has to satisfy.
   *
   * <p>A check that fails adds its own message; the empty message set is what "acceptable"
   * means, so a check that raises nothing has passed.
   */
  public interface Check {
    void run(Field field);
  }

  private final List<Check> checks = new ArrayList<>();

  @SuppressWarnings("unchecked")
  public <T extends Field> T required() {
    this.required = true;
    return (T) this;
  }

  @SuppressWarnings("unchecked")
  public <T extends Field> T describedAs(String description) {
    this.description = description;
    return (T) this;
  }

  @SuppressWarnings("unchecked")
  public <T extends Field> T renderedWith(String attribute, String value) {
    renderAttributes.put(attribute, value);
    return (T) this;
  }

  @SuppressWarnings("unchecked")
  public <T extends Field> T checking(Check check) {
    checks.add(check);
    return (T) this;
  }

  @SuppressWarnings("unchecked")
  public <T extends Field> T startingAs(Object initial) {
    this.data = initial;
    return (T) this;
  }

  public void fail(String message) {
    errors.add(message);
  }

  /** What this field is called in the templates that branch on the kind of control. */
  public abstract String type();

  /** The control's markup, with whatever attributes the template passed. */
  public abstract String render(Map<String, Object> attributes);

  /** Reads this field's value out of what a form submission carried. */
  public abstract void populate(List<String> submitted, boolean present);

  /** Whether the value is acceptable, adding a message to {@link #errors()} when not. */
  public boolean validate() {
    errors.clear();
    errors.addAll(processErrors);
    if (required && (data == null || String.valueOf(data).isBlank())) {
      errors.add("This field is required.");
      return false;
    }
    for (Check check : checks) {
      check.run(this);
    }
    return errors.isEmpty();
  }

  @Override
  public Object call(List<Object> positional, Map<String, Object> keyword) {
    Map<String, Object> attributes = new LinkedHashMap<>();
    for (Map.Entry<String, String> entry : renderAttributes.entrySet()) {
      attributes.put(entry.getKey(), entry.getValue());
    }
    attributes.putAll(keyword);
    return new PyValue.Markup(render(attributes));
  }

  /** The control's markup with only the attributes it was declared with. */
  public String renderWithDefaults() {
    Map<String, Object> attributes = new LinkedHashMap<>();
    for (Map.Entry<String, String> entry : renderAttributes.entrySet()) {
      attributes.put(entry.getKey(), entry.getValue());
    }
    return render(attributes);
  }

  @Override
  public Object attribute(String attributeName) {
    return switch (attributeName) {
      case "name" -> id();
      case "id" -> id();
      case "label" -> new Label(id(), label);
      case "description" -> description;
      case "data" -> data == null ? PyValue.UNDEFINED : data;
      case "errors" -> errors;
      case "top_errors" -> new ArrayList<String>();
      case "type" -> type();
      case "flags" -> Map.of("required", required);
      case "render_kw" -> renderAttributes;
      // A macro turns a control into a variant of itself just before rendering it, which the
      // language it was written in allows by assigning an attribute mid-expression.
      case "__setattr__" ->
          (PyValue.Callable)
              (positional, keyword) -> {
                if (positional.size() >= 2) {
                  templateFlags.put(PyValue.asString(positional.get(0)), positional.get(1));
                }
                return "";
              };
      default -> {
        Object flag = templateFlags.get(attributeName);
        yield flag == null ? PyValue.UNDEFINED : flag;
      }
    };
  }

  /** The attributes a template passed, written into the tag. */
  protected static String attributesOf(Map<String, Object> attributes) {
    StringBuilder sb = new StringBuilder();
    for (Map.Entry<String, Object> entry : attributes.entrySet()) {
      String key = entry.getKey();
      // The templates write the reserved words with a trailing underscore, as the language
      // they were written in requires; the markup wants them without.
      if (key.endsWith("_")) {
        key = key.substring(0, key.length() - 1);
      }
      key = key.replace('_', '-');
      Object value = entry.getValue();
      if (value == null || Boolean.FALSE.equals(value)) {
        continue;
      }
      if (Boolean.TRUE.equals(value)) {
        sb.append(' ').append(key);
        continue;
      }
      sb.append(' ')
          .append(key)
          .append("=\"")
          .append(Filters.escapeHtml(PyValue.asString(value)))
          .append('"');
    }
    return sb.toString();
  }

  protected static String escaped(Object value) {
    return value == null ? "" : Filters.escapeHtml(PyValue.asString(value));
  }
}
