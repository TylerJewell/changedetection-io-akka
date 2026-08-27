package io.akka.changedetection.forms;

import io.akka.changedetection.jinja.PyValue;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * A set of controls, as the shipped templates read one.
 *
 * <p>A template reaches a control by name -- {@code form.url} -- so the form has to answer an
 * attribute lookup with the control rather than with its value, and it has to keep the order the
 * controls were declared in because several templates walk the whole form.
 */
public class Form implements PyValue.Attributed, Iterable<Object> {

  protected final Map<String, Field> fields = new LinkedHashMap<>();
  private final List<String> formErrors = new ArrayList<>();
  private String prefix = "";

  public <T extends Field> T add(T field) {
    fields.put(field.name(), field);
    field.setQualifier(prefix);
    return field;
  }

  public Field field(String name) {
    return fields.get(name);
  }

  public Map<String, Field> fields() {
    return fields;
  }

  public List<String> formErrors() {
    return formErrors;
  }

  public void setPrefix(String prefix) {
    this.prefix = prefix;
    for (Field field : fields.values()) {
      field.setQualifier(prefix);
    }
  }

  public String prefix() {
    return prefix;
  }

  /** Walking a form yields its controls, which is how the shipped macros lay one out. */
  @Override
  public Iterator<Object> iterator() {
    return new ArrayList<Object>(fields.values()).iterator();
  }

  /** Fills the controls from what a submission carried. */
  public void populate(Map<String, List<String>> submitted) {
    for (Field field : fields.values()) {
      if (field instanceof Nested nested) {
        nested.populateFrom(submitted);
      } else if (field instanceof Repeated repeated) {
        repeated.populateFrom(submitted);
      } else {
        String key = field.id();
        field.populate(submitted.getOrDefault(key, List.of()), submitted.containsKey(key));
      }
    }
  }

  /** Fills the controls from stored values. */
  public void fill(Map<String, Object> values) {
    for (Map.Entry<String, Field> entry : fields.entrySet()) {
      if (values.containsKey(entry.getKey())) {
        entry.getValue().setData(values.get(entry.getKey()));
      }
    }
  }

  /** The controls' values, as the rest of the system stores them. */
  public Map<String, Object> data() {
    Map<String, Object> out = new LinkedHashMap<>();
    for (Map.Entry<String, Field> entry : fields.entrySet()) {
      if (entry.getValue() instanceof Fields.SubmitField) {
        continue;
      }
      out.put(entry.getKey(), entry.getValue().data());
    }
    return out;
  }

  /** Whether every control is acceptable; the messages are left on the controls. */
  public boolean validate() {
    formErrors.clear();
    boolean valid = true;
    for (Field field : fields.values()) {
      if (!field.validate()) {
        valid = false;
      }
    }
    return valid;
  }

  /** True when anything at all failed, which several templates ask before drawing a banner. */
  public boolean hasErrors() {
    return !errors().isEmpty();
  }

  /** Every message, keyed by the control it belongs to, for the interface's error summary. */
  public Map<String, Object> errors() {
    Map<String, Object> out = new LinkedHashMap<>();
    for (Map.Entry<String, Field> entry : fields.entrySet()) {
      Field field = entry.getValue();
      if (field instanceof Nested nested) {
        Map<String, Object> inner = nested.inner().errors();
        if (!inner.isEmpty()) {
          out.put(entry.getKey(), inner);
        }
      } else if (!field.errors().isEmpty()) {
        out.put(entry.getKey(), field.errors());
      }
    }
    if (!formErrors.isEmpty()) {
      out.put("form", new ArrayList<>(formErrors));
    }
    return out;
  }

  @Override
  public Object attribute(String name) {
    Field field = fields.get(name);
    if (field != null) {
      return field;
    }
    return switch (name) {
      case "errors" -> errors();
      case "form_errors" -> formErrors;
      case "data" -> data();
      default -> PyValue.UNDEFINED;
    };
  }

  /**
   * A form nested inside another, which the templates reach through {@code .form}.
   *
   * <p>Used for the parts of a watch that are groups of their own -- the check interval, the
   * weekly schedule -- so that each part renders as a unit and submits under one prefix.
   */
  public static class Nested extends Field implements Iterable<Object> {
    private final Form inner;
    private final List<String> topErrors = new ArrayList<>();

    public Nested(String name, String label, Form inner) {
      super(name, label);
      this.inner = inner;
      inner.setPrefix(id());
    }

    public Form inner() {
      return inner;
    }

    public List<String> topErrors() {
      return topErrors;
    }

    @Override
    void setQualifier(String newQualifier) {
      super.setQualifier(newQualifier);
      inner.setPrefix(id());
    }

    @Override
    public Iterator<Object> iterator() {
      return inner.iterator();
    }

    @Override
    public String type() {
      return "FormField";
    }

    /**
     * One row per control, the control before its label.
     *
     * <p>Reversed from the usual order because the groups drawn this way are rows of checkboxes
     * and short numbers, where the label reads as a caption to the right of the control.
     */
    @Override
    public String render(Map<String, Object> attributes) {
      StringBuilder sb = new StringBuilder();
      sb.append("<table id=\"").append(escaped(id())).append("\">");
      StringBuilder hidden = new StringBuilder();
      for (Field field : inner.fields().values()) {
        if (field.type().equals("HiddenField")) {
          hidden.append(field.renderWithDefaults());
          continue;
        }
        sb.append("<tr><td>")
            .append(hidden)
            .append(field.renderWithDefaults())
            .append("</td><th>")
            .append(new Label(field.id(), field.labelText()))
            .append("</th></tr>");
        hidden.setLength(0);
      }
      sb.append("</table>").append(hidden);
      return sb.toString();
    }

    @Override
    public void populate(List<String> submitted, boolean present) {
      // A group reads the submission itself, under its own prefix; see populateFrom.
    }

    public void populateFrom(Map<String, List<String>> submitted) {
      inner.populate(submitted);
    }

    @Override
    public boolean validate() {
      errors.clear();
      topErrors.clear();
      return inner.validate();
    }

    @Override
    public Object data() {
      return inner.data();
    }

    @Override
    public void setData(Object data) {
      if (data instanceof Map<?, ?> map) {
        inner.fill(asStringKeyed(map));
      }
    }

    @Override
    public Object attribute(String attributeName) {
      switch (attributeName) {
        case "form":
          return inner;
        case "errors":
          return inner.errors();
        case "top_errors":
          return topErrors;
        default:
          break;
      }
      Field nested = inner.field(attributeName);
      if (nested != null) {
        return nested;
      }
      return super.attribute(attributeName);
    }
  }

  /**
   * A control that repeats, as the proxy list and the browser-step list do.
   *
   * <p>The templates walk the list and render each entry, so an entry is a group in its own right
   * whose name carries its position -- {@code browser_steps-0} -- which is also what makes a
   * submission reassemble in the order it was drawn in.
   */
  public static class Repeated extends Field implements Iterable<Object> {
    private final Supplier<Form> factory;
    private final List<Nested> entries = new ArrayList<>();
    private final int minimumEntries;

    public Repeated(String name, String label, Supplier<Form> factory, int minimumEntries) {
      super(name, label);
      this.factory = factory;
      this.minimumEntries = minimumEntries;
      ensureMinimum();
    }

    private Nested entryAt(int index) {
      Nested entry = new Nested(name + "-" + index, label + "-" + index, factory.get());
      entry.setQualifier(qualifier);
      return entry;
    }

    private void ensureMinimum() {
      while (entries.size() < minimumEntries) {
        entries.add(entryAt(entries.size()));
      }
    }

    public List<Nested> entries() {
      return entries;
    }

    @Override
    void setQualifier(String newQualifier) {
      super.setQualifier(newQualifier);
      for (Nested entry : entries) {
        entry.setQualifier(newQualifier);
      }
    }

    @Override
    public Iterator<Object> iterator() {
      return new ArrayList<Object>(entries).iterator();
    }

    @Override
    public String type() {
      return "FieldList";
    }

    @Override
    public String render(Map<String, Object> attributes) {
      StringBuilder sb = new StringBuilder();
      for (Nested entry : entries) {
        sb.append(entry.render(attributes));
      }
      return sb.toString();
    }

    @Override
    public void populate(List<String> submitted, boolean present) {
      // The list reads the submission itself; see populateFrom.
    }

    /**
     * Reads however many entries the submission carried.
     *
     * <p>Counts upward from zero and stops at the first gap. A removed row leaves no gap because
     * the interface renumbers the rows that remain before submitting.
     */
    public void populateFrom(Map<String, List<String>> submitted) {
      int count = 0;
      while (true) {
        String probe = (qualifier.isEmpty() ? "" : qualifier + "-") + name + "-" + count + "-";
        boolean any = false;
        for (String key : submitted.keySet()) {
          if (key.startsWith(probe)) {
            any = true;
            break;
          }
        }
        if (!any) {
          break;
        }
        count++;
      }
      entries.clear();
      for (int index = 0; index < count; index++) {
        Nested entry = entryAt(index);
        entry.populateFrom(submitted);
        entries.add(entry);
      }
      ensureMinimum();
    }

    @Override
    public Object data() {
      List<Object> out = new ArrayList<>();
      for (Nested entry : entries) {
        out.add(entry.data());
      }
      return out;
    }

    @Override
    public void setData(Object data) {
      if (!(data instanceof List<?> list)) {
        return;
      }
      entries.clear();
      for (Object item : list) {
        Nested entry = entryAt(entries.size());
        if (item instanceof Map<?, ?> map) {
          entry.inner().fill(asStringKeyed(map));
        }
        entries.add(entry);
      }
      ensureMinimum();
    }

    @Override
    public boolean validate() {
      errors.clear();
      boolean valid = true;
      for (Nested entry : entries) {
        if (!entry.validate()) {
          valid = false;
        }
      }
      return valid;
    }

    @Override
    public Object attribute(String attributeName) {
      switch (attributeName) {
        case "entries":
          return new ArrayList<Object>(entries);
        case "errors":
          List<Object> perEntry = new ArrayList<>();
          for (Nested entry : entries) {
            perEntry.add(entry.inner().errors());
          }
          return perEntry;
        default:
          return super.attribute(attributeName);
      }
    }
  }

  static Map<String, Object> asStringKeyed(Map<?, ?> map) {
    Map<String, Object> typed = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      typed.put(String.valueOf(entry.getKey()), entry.getValue());
    }
    return typed;
  }
}
