package io.akka.changedetection.forms;

import io.akka.changedetection.jinja.PyValue;
import io.akka.changedetection.text.PythonText;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The kinds of control the shipped forms are made of. */
public final class Fields {

  private Fields() {}

  /** A single line of text. */
  public static class StringField extends Field {
    private final String inputType;

    public StringField(String name, String label) {
      this(name, label, "text");
    }

    public StringField(String name, String label, String inputType) {
      super(name, label);
      this.inputType = inputType;
    }

    @Override
    public String type() {
      return "StringField";
    }

    @Override
    public String render(Map<String, Object> attributes) {
      Map<String, Object> merged = new LinkedHashMap<>();
      merged.put("id", id());
      merged.put("name", id());
      merged.put("type", inputType);
      merged.put("value", data == null ? "" : PyValue.asString(data));
      merged.putAll(attributes);
      return "<input" + attributesOf(merged) + ">";
    }

    @Override
    public void populate(List<String> submitted, boolean present) {
      data = submitted.isEmpty() ? "" : submitted.get(0);
    }
  }

  /** A password, whose value is never written back into the page. */
  public static final class PasswordField extends StringField {
    public PasswordField(String name, String label) {
      super(name, label, "password");
    }

    @Override
    public String type() {
      return "PasswordField";
    }

    @Override
    public String render(Map<String, Object> attributes) {
      Map<String, Object> merged = new LinkedHashMap<>();
      merged.put("id", id());
      merged.put("name", id());
      merged.put("type", "password");
      merged.put("value", "");
      merged.putAll(attributes);
      return "<input" + attributesOf(merged) + ">";
    }
  }

  /** A value the form carries but does not show. */
  public static final class HiddenField extends StringField {
    public HiddenField(String name) {
      super(name, "", "hidden");
    }

    @Override
    public String type() {
      return "HiddenField";
    }
  }

  /** Several lines of text. */
  public static class TextAreaField extends Field {
    public TextAreaField(String name, String label) {
      super(name, label);
    }

    @Override
    public String type() {
      return "TextAreaField";
    }

    @Override
    public String render(Map<String, Object> attributes) {
      Map<String, Object> merged = new LinkedHashMap<>();
      merged.put("id", id());
      merged.put("name", id());
      merged.putAll(attributes);
      return "<textarea" + attributesOf(merged) + ">"
          + escaped(data == null ? "" : PyValue.asString(data))
          + "</textarea>";
    }

    @Override
    public void populate(List<String> submitted, boolean present) {
      data = submitted.isEmpty() ? "" : submitted.get(0);
    }
  }

  /**
   * A list of rules, written one per line.
   *
   * <p>The list is the value the rest of the system reads, and the text is only how it is
   * shown. Blank lines are dropped, because a rule that is the empty string matches every line
   * and would silently ignore the whole page.
   */
  public static final class StringListField extends TextAreaField {
    public StringListField(String name, String label) {
      super(name, label);
    }

    @Override
    public String type() {
      return "StringListField";
    }

    @Override
    public String render(Map<String, Object> attributes) {
      Map<String, Object> merged = new LinkedHashMap<>();
      merged.put("id", id());
      merged.put("name", id());
      merged.putAll(attributes);
      return "<textarea" + attributesOf(merged) + ">" + escaped(asText()) + "</textarea>";
    }

    private String asText() {
      if (data instanceof List<?> list) {
        List<String> lines = new ArrayList<>();
        for (Object item : list) {
          String line = PyValue.asString(item);
          if (!line.strip().isEmpty()) {
            lines.add(line.strip());
          }
        }
        return String.join("\r\n", lines);
      }
      return data == null ? "" : PyValue.asString(data);
    }

    @Override
    public void populate(List<String> submitted, boolean present) {
      String text = submitted.isEmpty() ? "" : submitted.get(0);
      if (text.strip().isEmpty()) {
        data = new ArrayList<String>();
        return;
      }
      List<String> values = new ArrayList<>();
      for (String line : PythonText.splitLines(text)) {
        // Kept as written, not trimmed: leading whitespace is meaningful in a rule that
        // matches indented text. Only the shown form is tidied.
        if (!line.strip().isEmpty()) {
          values.add(line);
        }
      }
      data = values;
    }
  }

  /**
   * A list of names and values, written one pair per line.
   *
   * <p>Used for the extra headers a watch sends. The separator is the first colon on the line,
   * because a header's value routinely contains colons -- an address, a time.
   */
  public static final class StringDictField extends TextAreaField {
    public StringDictField(String name, String label) {
      super(name, label);
    }

    @Override
    public String type() {
      return "StringDictKeyValue";
    }

    @Override
    public String render(Map<String, Object> attributes) {
      Map<String, Object> merged = new LinkedHashMap<>();
      merged.put("id", id());
      merged.put("name", id());
      merged.putAll(attributes);
      return "<textarea" + attributesOf(merged) + ">" + escaped(asText()) + "</textarea>";
    }

    private String asText() {
      if (data instanceof Map<?, ?> map) {
        List<String> lines = new ArrayList<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
          lines.add(entry.getKey() + ": " + entry.getValue());
        }
        return String.join("\r\n", lines);
      }
      return data == null ? "" : PyValue.asString(data);
    }

    @Override
    public void populate(List<String> submitted, boolean present) {
      String text = submitted.isEmpty() ? "" : submitted.get(0);
      Map<String, Object> values = new LinkedHashMap<>();
      List<String> complaints = new ArrayList<>();
      List<String> lines = new ArrayList<>();
      for (String line : text.split("\n", -1)) {
        if (!line.strip().isEmpty()) {
          lines.add(line.strip());
        }
      }
      for (int index = 0; index < lines.size(); index++) {
        String line = lines.get(index);
        int position = index + 1;
        int colon = line.indexOf(':');
        if (colon < 0) {
          complaints.add("Line " + position + " is missing a ':' separator.");
          continue;
        }
        String key = line.substring(0, colon).strip();
        String value = line.substring(colon + 1).strip();
        if (key.isEmpty()) {
          complaints.add("Line " + position + " has an empty key.");
        }
        if (value.isEmpty()) {
          complaints.add("Line " + position + " has an empty value.");
        }
        values.put(key, value);
      }
      data = values;
      if (!complaints.isEmpty()) {
        processErrors.add("Invalid input:\n" + String.join("\n", complaints));
      }
    }
  }

  /** A box that is either ticked or not. */
  public static final class BooleanField extends Field {
    public BooleanField(String name, String label) {
      super(name, label);
      this.data = Boolean.FALSE;
    }

    @Override
    public String type() {
      return "BooleanField";
    }

    @Override
    public String render(Map<String, Object> attributes) {
      Map<String, Object> merged = new LinkedHashMap<>();
      merged.put("id", id());
      merged.put("name", id());
      merged.put("type", "checkbox");
      merged.put("value", "y");
      if (PyValue.truthy(data)) {
        merged.put("checked", true);
      }
      merged.putAll(attributes);
      return "<input" + attributesOf(merged) + ">";
    }

    @Override
    public void populate(List<String> submitted, boolean present) {
      // The absence of a tick is how a box says "no", so a submission with the field missing
      // means false rather than unchanged.
      String value = submitted.isEmpty() ? "" : submitted.get(0);
      data = present && !value.isEmpty() && !value.equals("false");
    }
  }

  /**
   * A box with three positions: on, off, and take the global setting.
   *
   * <p>The third is not a nicety. Two of them are per-watch overrides of a global switch, and
   * folding them into two positions would mean a watch could never go back to following the
   * global one.
   */
  public static final class TernaryField extends Field {
    private String yesText = "Yes";
    private String noText = "No";
    private String noneText = "Main settings";

    public TernaryField(String name, String label) {
      super(name, label);
      this.data = null;
    }

    public TernaryField(String name, String label, String yesText, String noText) {
      this(name, label);
      this.yesText = yesText;
      this.noText = noText;
    }

    /** With two positions the third is hidden and an unanswered control reads as off. */
    private boolean booleanMode() {
      return PyValue.truthy(flag("boolean_mode"));
    }

    @Override
    public String type() {
      return "TernaryNoneBooleanField";
    }

    @Override
    public String render(Map<String, Object> attributes) {
      Object givenId = attributes.get("id");
      String fieldId = givenId == null ? id() : PyValue.asString(givenId);
      StringBuilder sb = new StringBuilder("<div class=\"ternary-radio-group pure-form\">");
      // The option texts may be markup -- an icon, a coloured word -- and are written through
      // as they were configured.
      sb.append(option(fieldId, "true", "_true", Boolean.TRUE.equals(data), yesText, ""));
      sb.append(option(fieldId, "false", "_false", Boolean.FALSE.equals(data), noText, ""));
      if (!booleanMode()) {
        sb.append(option(fieldId, "none", "_none", data == null, noneText, " ternary-default"));
      }
      return sb.append("</div>").toString();
    }

    private String option(
        String fieldId,
        String value,
        String suffix,
        boolean checked,
        String text,
        String extraClass) {
      return "<label class=\"ternary-radio-option\">"
          + "<input type=\"radio\" name=\""
          + escaped(id())
          + "\" value=\""
          + value
          + "\" id=\""
          + escaped(fieldId + suffix)
          + "\""
          + (checked ? " checked" : "")
          + " class=\"pure-radio\">"
          + "<span class=\"ternary-radio-label"
          + extraClass
          + "\">"
          + text
          + "</span></label>";
    }

    @Override
    public void populate(List<String> submitted, boolean present) {
      Object unanswered = booleanMode() ? Boolean.FALSE : null;
      String value = submitted.isEmpty() ? "" : submitted.get(0);
      if (value.isEmpty()) {
        data = unanswered;
      } else if (value.equalsIgnoreCase("true")) {
        data = Boolean.TRUE;
      } else if (value.equalsIgnoreCase("false")) {
        data = Boolean.FALSE;
      } else {
        data = unanswered;
      }
    }
  }

  /** A time of day, kept as the {@code HH:MM} the page submitted. */
  public static final class TimeStringField extends Field {
    public TimeStringField(String name, String label) {
      super(name, label);
      this.data = "";
    }

    @Override
    public String type() {
      return "TimeStringField";
    }

    @Override
    public String render(Map<String, Object> attributes) {
      Map<String, Object> merged = new LinkedHashMap<>();
      merged.put("id", id());
      merged.put("name", id());
      merged.put("type", "time");
      merged.put("value", data == null ? "" : PyValue.asString(data));
      merged.putAll(attributes);
      return "<input" + attributesOf(merged) + ">";
    }

    @Override
    public void populate(List<String> submitted, boolean present) {
      String value = submitted.isEmpty() ? "" : submitted.get(0);
      if (value.isEmpty() || value.split(":", -1).length != 2) {
        processErrors.add("Invalid time format. Use HH:MM.");
        return;
      }
      data = value;
    }
  }

  /**
   * A file chosen for upload.
   *
   * <p>The value is the uploaded content, which the request handler puts here after reading the
   * multipart body; the control itself only draws the picker.
   */
  public static final class FileField extends Field {
    private final List<String> allowedExtensions = new ArrayList<>();
    private String filename = "";

    public FileField(String name, String label, List<String> allowedExtensions) {
      super(name, label);
      this.allowedExtensions.addAll(allowedExtensions);
    }

    public void setUpload(String filename, byte[] content) {
      this.filename = filename == null ? "" : filename;
      this.data = content;
    }

    public byte[] upload() {
      return data instanceof byte[] bytes ? bytes : null;
    }

    @Override
    public String type() {
      return "FileField";
    }

    @Override
    public String render(Map<String, Object> attributes) {
      Map<String, Object> merged = new LinkedHashMap<>();
      merged.put("id", id());
      merged.put("name", id());
      merged.put("type", "file");
      merged.putAll(attributes);
      return "<input" + attributesOf(merged) + ">";
    }

    @Override
    public void populate(List<String> submitted, boolean present) {
      // The content arrives out of band; see setUpload.
    }

    @Override
    public boolean validate() {
      errors.clear();
      if (filename.isEmpty() || allowedExtensions.isEmpty()) {
        return true;
      }
      String lower = filename.toLowerCase(java.util.Locale.ROOT);
      for (String extension : allowedExtensions) {
        if (lower.endsWith("." + extension.toLowerCase(java.util.Locale.ROOT))) {
          return true;
        }
      }
      errors.add("Must be ." + String.join("/.", allowedExtensions) + " file!");
      return false;
    }

    @Override
    public Object attribute(String attributeName) {
      if (attributeName.equals("filename")) {
        return filename;
      }
      return super.attribute(attributeName);
    }
  }

  /** A whole number. */
  public static final class IntegerField extends Field {
    public IntegerField(String name, String label) {
      super(name, label);
    }

    @Override
    public String type() {
      return "IntegerField";
    }

    @Override
    public String render(Map<String, Object> attributes) {
      Map<String, Object> merged = new LinkedHashMap<>();
      merged.put("id", id());
      merged.put("name", id());
      merged.put("type", "number");
      merged.put("value", data == null ? "" : PyValue.asString(data));
      merged.putAll(attributes);
      return "<input" + attributesOf(merged) + ">";
    }

    @Override
    public void populate(List<String> submitted, boolean present) {
      String value = submitted.isEmpty() ? "" : submitted.get(0).strip();
      if (value.isEmpty()) {
        data = null;
        return;
      }
      try {
        data = Long.valueOf(value);
      } catch (NumberFormatException e) {
        data = null;
        processErrors.add("Not a valid integer value.");
      }
    }
  }

  /** A number that may have a fractional part. */
  public static final class FloatField extends Field {
    public FloatField(String name, String label) {
      super(name, label);
    }

    @Override
    public String type() {
      return "FloatField";
    }

    @Override
    public String render(Map<String, Object> attributes) {
      Map<String, Object> merged = new LinkedHashMap<>();
      merged.put("id", id());
      merged.put("name", id());
      merged.put("type", "number");
      merged.put("step", "any");
      merged.put("value", data == null ? "" : PyValue.asString(data));
      merged.putAll(attributes);
      return "<input" + attributesOf(merged) + ">";
    }

    @Override
    public void populate(List<String> submitted, boolean present) {
      String value = submitted.isEmpty() ? "" : submitted.get(0).strip();
      if (value.isEmpty()) {
        data = null;
        return;
      }
      try {
        data = Double.valueOf(value);
      } catch (NumberFormatException e) {
        data = null;
        processErrors.add("Not a valid float value.");
      }
    }
  }

  /** One choice from a list. */
  public static class SelectField extends Field {
    private final List<String[]> choices = new ArrayList<>();

    public SelectField(String name, String label, List<String[]> choices) {
      super(name, label);
      this.choices.addAll(choices);
    }

    public List<String[]> choices() {
      return choices;
    }

    public void setChoices(List<String[]> replacement) {
      choices.clear();
      choices.addAll(replacement);
    }

    @Override
    public String type() {
      return "SelectField";
    }

    @Override
    public String render(Map<String, Object> attributes) {
      Map<String, Object> merged = new LinkedHashMap<>();
      merged.put("id", id());
      merged.put("name", id());
      merged.putAll(attributes);
      StringBuilder sb = new StringBuilder("<select").append(attributesOf(merged)).append('>');
      String current = data == null ? "" : PyValue.asString(data);
      for (String[] choice : choices) {
        sb.append("<option value=\"").append(escaped(choice[0])).append('"');
        if (current.equals(choice[0])) {
          sb.append(" selected");
        }
        sb.append('>').append(escaped(choice[1])).append("</option>");
      }
      return sb.append("</select>").toString();
    }

    @Override
    public void populate(List<String> submitted, boolean present) {
      data = submitted.isEmpty() ? "" : submitted.get(0);
    }

    @Override
    public Object attribute(String attributeName) {
      if (attributeName.equals("choices")) {
        List<Object> out = new ArrayList<>();
        for (String[] choice : choices) {
          out.add(new PyValue.Tuple(choice[0], choice[1]));
        }
        return out;
      }
      return super.attribute(attributeName);
    }
  }

  /** Several choices from a list. */
  public static final class SelectMultipleField extends SelectField {
    public SelectMultipleField(String name, String label, List<String[]> choices) {
      super(name, label, choices);
      setData(new ArrayList<String>());
    }

    @Override
    public String type() {
      return "SelectMultipleField";
    }

    @Override
    public String render(Map<String, Object> attributes) {
      Map<String, Object> merged = new LinkedHashMap<>();
      merged.put("id", id());
      merged.put("name", id());
      merged.put("multiple", true);
      merged.putAll(attributes);
      StringBuilder sb = new StringBuilder("<select").append(attributesOf(merged)).append('>');
      List<Object> current = PyValue.iterate(data());
      List<String> selected = new ArrayList<>();
      for (Object item : current) {
        selected.add(PyValue.asString(item));
      }
      for (String[] choice : choices()) {
        sb.append("<option value=\"").append(escaped(choice[0])).append('"');
        if (selected.contains(choice[0])) {
          sb.append(" selected");
        }
        sb.append('>').append(escaped(choice[1])).append("</option>");
      }
      return sb.append("</select>").toString();
    }

    @Override
    public void populate(List<String> submitted, boolean present) {
      setData(new ArrayList<>(submitted));
    }
  }

  /** One choice from a list, shown as a row of buttons. */
  public static final class RadioField extends SelectField {
    public RadioField(String name, String label, List<String[]> choices) {
      super(name, label, choices);
    }

    @Override
    public String type() {
      return "RadioField";
    }

    @Override
    public String render(Map<String, Object> attributes) {
      StringBuilder sb = new StringBuilder("<ul id=\"" + escaped(id()) + "\">");
      String current = data() == null ? "" : PyValue.asString(data());
      int index = 0;
      for (String[] choice : choices()) {
        String optionId = id() + "-" + index++;
        Map<String, Object> merged = new LinkedHashMap<>();
        merged.put("id", optionId);
        merged.put("name", id());
        merged.put("type", "radio");
        merged.put("value", choice[0]);
        if (current.equals(choice[0])) {
          merged.put("checked", true);
        }
        merged.putAll(attributes);
        sb.append("<li><input").append(attributesOf(merged)).append('>')
            .append("<label for=\"").append(escaped(optionId)).append("\">")
            .append(escaped(choice[1])).append("</label></li>");
      }
      return sb.append("</ul>").toString();
    }
  }

  /** A button that submits the form. */
  public static final class SubmitField extends Field {
    public SubmitField(String name, String label) {
      super(name, label);
    }

    @Override
    public String type() {
      return "SubmitField";
    }

    @Override
    public String render(Map<String, Object> attributes) {
      Map<String, Object> merged = new LinkedHashMap<>();
      merged.put("id", id());
      merged.put("name", id());
      merged.put("type", "submit");
      merged.put("value", label);
      merged.putAll(attributes);
      return "<input" + attributesOf(merged) + ">";
    }

    @Override
    public void populate(List<String> submitted, boolean present) {
      data = present;
    }
  }
}
