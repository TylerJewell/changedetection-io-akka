package io.akka.changedetection.forms;

import io.akka.changedetection.jinja.PyValue;
import io.akka.changedetection.model.LlmSettings;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/** Every form the interface puts on a page, built the way the shipped templates read one. */
public final class Forms {

  public static final String REQUIRE_ONE_TIME_PART =
      "At least one time interval (weeks, days, hours, minutes, or seconds) must be specified.";
  public static final String REQUIRE_ONE_TIME_PART_WHEN_NOT_GLOBAL =
      "At least one time interval (weeks, days, hours, minutes, or seconds) must be specified "
          + "when not using global settings.";
  public static final String ZERO_OR_MORE_SECONDS = "Should contain zero or more seconds";

  public static final String DEFAULT_NOTIFICATION_BODY = "{{ watch_url }} had a change.";
  public static final String DEFAULT_NOTIFICATION_TITLE =
      "ChangeDetection.io Notification - {{ watch_url }}";

  public static final String LLM_INTENT_WATCH_PLACEHOLDER =
      "e.g. Alert me when the price drops below $300, or a new product is launched. "
          + "Ignore footer and navigation changes.";
  public static final String LLM_INTENT_TAG_PLACEHOLDER =
      "e.g. Flag price changes or new product launches across all watches in this group";

  private Forms() {}

  /**
   * A group that is only allowed to be empty when another control says it may be.
   *
   * <p>The message belongs to the group rather than to any one control in it, because what is
   * wrong is that all five are blank at once, not that any particular one is.
   */
  public static final class ConditionalGroup extends Form.Nested {
    private final String conditionOn;
    private final String message;
    private final Predicate<Form> hasAnyValue;
    private Form enclosing;

    public ConditionalGroup(
        String name,
        String label,
        Form inner,
        String conditionOn,
        String message,
        Predicate<Form> hasAnyValue) {
      super(name, label, inner);
      this.conditionOn = conditionOn;
      this.message = message;
      this.hasAnyValue = hasAnyValue;
    }

    public void attachTo(Form enclosing) {
      this.enclosing = enclosing;
    }

    @Override
    public boolean validate() {
      boolean valid = super.validate();
      if (enclosing == null || conditionOn == null) {
        return valid;
      }
      Field condition = enclosing.field(conditionOn);
      if (condition == null || PyValue.truthy(condition.data())) {
        return valid;
      }
      if (!hasAnyValue.test(inner())) {
        topErrors().add(message);
        return false;
      }
      return valid;
    }
  }

  /** A group that must always carry a value, whatever else the form says. */
  public static final class RequiredGroup extends Form.Nested {
    private final String message;
    private final Predicate<Form> hasAnyValue;

    public RequiredGroup(
        String name, String label, Form inner, String message, Predicate<Form> hasAnyValue) {
      super(name, label, inner);
      this.message = message;
      this.hasAnyValue = hasAnyValue;
    }

    @Override
    public boolean validate() {
      boolean valid = super.validate();
      if (!hasAnyValue.test(inner())) {
        inner().formErrors().add(message);
        return false;
      }
      return valid;
    }
  }

  /** True when any part of an interval was given a number above zero. */
  public static boolean intervalHasValue(Form interval) {
    for (String part : List.of("weeks", "days", "hours", "minutes", "seconds")) {
      Field field = interval.field(part);
      if (field == null) {
        continue;
      }
      Object value = field.data();
      if (value instanceof Number number && number.longValue() > 0) {
        return true;
      }
      if (value instanceof CharSequence text && !text.toString().strip().isEmpty()) {
        try {
          if (Long.parseLong(text.toString().strip()) > 0) {
            return true;
          }
        } catch (NumberFormatException e) {
          // A value that is not a number was already reported by the control itself.
        }
      }
    }
    return false;
  }

  public static Form timeDuration() {
    Form form = new Form();
    form.add(
        new Fields.SelectField("hours", "hours", Choices.hoursOfDay()).<Fields.SelectField>
            startingAs("24"));
    form.add(
        new Fields.SelectField("minutes", "minutes", Choices.minutesOfHour())
            .<Fields.SelectField>startingAs("00"));
    return form;
  }

  public static Form scheduleDay(String label) {
    Form form = new Form();
    form.add(new Fields.BooleanField("enabled", label).<Fields.BooleanField>startingAs(true));
    form.add(
        new Fields.TimeStringField("start_time", "Start At").<Fields.TimeStringField>
            startingAs("00:00"));
    form.add(new Form.Nested("duration", "Run duration", timeDuration()));
    return form;
  }

  public static Form scheduleLimit() {
    Form form = new Form();
    form.add(
        new Fields.BooleanField("enabled", "Use time scheduler")
            .<Fields.BooleanField>startingAs(false));
    for (String day : Choices.DAYS_OF_WEEK) {
      // The label sits on the day's own checkbox rather than on the group, because the group's
      // label would not line up with a control nested two levels down.
      form.add(new Form.Nested(day, "", scheduleDay(Choices.dayLabel(day))));
    }
    form.add(
        new Fields.StringField("timezone", "Optional timezone to run in")
            .<Fields.StringField>renderedWith("list", "timezones")
            .checking(Checks.timezoneName()));
    return form;
  }

  public static Form timeBetweenCheck() {
    Form form = new Form();
    for (String[] part :
        new String[][] {
          {"weeks", "Weeks"},
          {"days", "Days"},
          {"hours", "Hours"},
          {"minutes", "Minutes"},
          {"seconds", "Seconds"}
        }) {
      form.add(
          new Fields.IntegerField(part[0], part[1])
              .<Fields.IntegerField>checking(Checks.numberRange(0.0, null, ZERO_OR_MORE_SECONDS))
              // The floor the rule carries is put on the control too, so a browser refuses a
              // negative before the submission is made rather than after.
              .renderedWith("min", "0"));
    }
    return form;
  }

  public static Form conditionRow() {
    Form form = new Form();
    form.add(new Fields.SelectField("field", "Field", Choices.conditionFields()));
    form.add(new Fields.SelectField("operator", "Operator", Choices.conditionOperators()));
    form.add(
        new Fields.StringField("value", "Value").<Fields.StringField>
            renderedWith("placeholder", "A value"));
    return form;
  }

  /** A rule row is either wholly filled in or wholly blank; a half-filled one is an error. */
  public static boolean validateConditionRow(Form row) {
    Object operator = row.field("operator").data();
    Object field = row.field("field").data();
    Object value = row.field("value").data();
    boolean anySet = set(operator) || set(field) || set(value);
    if (!anySet) {
      return true;
    }
    if (!set(operator)) {
      row.field("operator").fail("Operator is required.");
      return false;
    }
    if (!set(field)) {
      row.field("field").fail("Field is required.");
      return false;
    }
    if (!set(value)) {
      row.field("value").fail("Value is required.");
      return false;
    }
    return true;
  }

  private static boolean set(Object value) {
    if (value == null || Boolean.FALSE.equals(value)) {
      return false;
    }
    String text = PyValue.asString(value);
    return !text.isEmpty() && !text.equals("None");
  }

  public static Form browserStep() {
    Form form = new Form();
    form.add(
        new Fields.SelectField("operation", "Operation", Choices.browserStepOperations()));
    form.add(
        new Fields.StringField("selector", "Selector")
            .<Fields.StringField>renderedWith("placeholder", "CSS or xPath selector"));
    form.add(
        new Fields.StringField("optional_value", "value")
            .<Fields.StringField>renderedWith("placeholder", "Value"));
    return form;
  }

  public static Form extraProxy() {
    Form form = new Form();
    form.add(
        new Fields.StringField("proxy_name", "Name")
            .<Fields.StringField>renderedWith("placeholder", "Name"));
    form.add(
        new Fields.StringField("proxy_url", "Proxy URL")
            .<Fields.StringField>renderedWith(
                "placeholder", "socks5:// or regular proxy http://user:pass@...:3128")
            .<Fields.StringField>renderedWith("size", "50")
            .<Fields.StringField>checking(
                Checks.startsWithPattern(
                    "^(https?|socks5)://",
                    "Proxy URLs must start with http://, https:// or socks5://"))
            .checking(Checks.simpleUrl("Invalid URL.")));
    return form;
  }

  public static Form extraBrowser() {
    Form form = new Form();
    form.add(
        new Fields.StringField("browser_name", "Name")
            .<Fields.StringField>renderedWith("placeholder", "Name"));
    form.add(
        new Fields.StringField("browser_connection_url", "Browser connection URL")
            .<Fields.StringField>renderedWith(
                "placeholder", "wss://brightdata... wss://oxylabs etc")
            .<Fields.StringField>renderedWith("size", "50")
            .<Fields.StringField>checking(
                Checks.startsWithPattern(
                    "^(wss?|ws)://", "Browser URLs must start with wss:// or ws://"))
            .checking(Checks.simpleUrl("Invalid URL.")));
    return form;
  }

  public static Form defaultUserAgents() {
    Form form = new Form();
    form.add(
        new Fields.StringField("html_requests", "Plaintext requests")
            .<Fields.StringField>renderedWith("placeholder", "<default>"));
    // Only offered when a browser is actually configured, matching the original: a field for a
    // browser that is not attached would silently do nothing.
    if (System.getenv("PLAYWRIGHT_DRIVER_URL") != null
        || System.getenv("WEBDRIVER_URL") != null) {
      form.add(
          new Fields.StringField("html_webdriver", "Chrome requests")
              .<Fields.StringField>renderedWith("placeholder", "<default>"));
    }
    return form;
  }

  /** The short form on the main page for adding a watch. */
  public static Form quickWatch(Map<String, Object> tags) {
    Form form = new Form();
    form.add(new Fields.StringField("url", "URL").checking(Checks.url()));
    form.add(new SpecialFields.TagField("tags", "Group tag", tags));
    form.add(
        new Fields.SubmitField("watch_submit_button", "Watch")
            .renderedWith("class", "pure-button pure-button-primary"));
    form.add(
        new Fields.RadioField("processor", "Processor", Choices.processors())
            .<Fields.RadioField>startingAs(Choices.defaultProcessor()));
    form.add(
        new Fields.SubmitField("edit_and_watch_submit_button", "Edit > Watch")
            .renderedWith("class", "pure-button pure-button-primary"));
    return form;
  }

  /** The controls a watch and the global settings both have. */
  private static void addCommonSettings(Form form, Map<String, Object> extraTokens) {
    form.add(
        new Fields.RadioField("fetch_backend", "Fetch Method", Choices.fetchers()));
    form.add(
        new Fields.TextAreaField("notification_body", "Notification Body")
            .<Fields.TextAreaField>startingAs(DEFAULT_NOTIFICATION_BODY)
            .checking(Checks.jinjaTemplate(extraTokens)));
    form.add(
        new Fields.SelectField(
            "notification_format", "Notification format", Choices.notificationFormats()));
    form.add(
        new Fields.StringField("notification_title", "Notification Title")
            .<Fields.StringField>startingAs(DEFAULT_NOTIFICATION_TITLE)
            .checking(Checks.jinjaTemplate(extraTokens)));
    form.add(
        new Fields.StringListField("notification_urls", "Notification URL List")
            .<Fields.StringListField>checking(Checks.notificationTargets())
            .checking(Checks.jinjaTemplate(extraTokens)));
    form.add(
        new Fields.RadioField(
                "processor", "Processor - What do you want to achieve?", Choices.processors())
            .<Fields.RadioField>startingAs(Choices.defaultProcessor()));
    form.add(
        new Fields.StringField(
                "scheduler_timezone_default", "Default timezone for watch check scheduler")
            .<Fields.StringField>renderedWith("list", "timezones")
            .checking(Checks.timezoneName()));
    form.add(
        new Fields.IntegerField("webdriver_delay", "Wait seconds before extracting text")
            .checking(
                Checks.numberRange(1.0, null, "Should contain one or more seconds")));
  }

  /**
   * The form for editing one watch.
   *
   * <p>The processor decides which extra controls appear, and the extra controls are named with
   * a prefix so that they are stored beside the watch rather than inside it.
   */
  public static Form watch(String processor, Map<String, Object> tags,
      Map<String, Object> extraTokens) {
    Form form = new Form();
    addCommonSettings(form, extraTokens);

    form.add(new Fields.StringField("url", "Web Page URL").checking(Checks.url()));
    form.add(
        new SpecialFields.TagField("tags", "Group Tag", tags).startingAs(""));

    ConditionalGroup interval =
        new ConditionalGroup(
            "time_between_check",
            "Time Between Check",
            timeBetweenCheck(),
            "time_between_check_use_default",
            REQUIRE_ONE_TIME_PART_WHEN_NOT_GLOBAL,
            Forms::intervalHasValue);
    interval.attachTo(form);
    form.add(interval);

    form.add(new Form.Nested("time_schedule_limit", "", scheduleLimit()));
    form.add(
        new Fields.BooleanField(
                "time_between_check_use_default",
                "Use global settings for time between check and scheduler.")
            .<Fields.BooleanField>startingAs(false));

    form.add(
        new Fields.TextAreaField("llm_intent", "AI Change Intent")
            .<Fields.TextAreaField>renderedWith("rows", "5")
            .<Fields.TextAreaField>renderedWith("placeholder", LLM_INTENT_WATCH_PLACEHOLDER)
            .checking(Checks.maximumLength(2000, "Field cannot be longer than 2000 characters.")));
    form.add(
        new Fields.TextAreaField("llm_change_summary", "AI Change Summary")
            .<Fields.TextAreaField>renderedWith("rows", "5")
            .<Fields.TextAreaField>startingAs("")
            .checking(Checks.maximumLength(2000, "Field cannot be longer than 2000 characters.")));
    form.add(
        new Fields.RadioField(
                "llm_change_summary_mode",
                "How this prompt combines with the inherited one",
                Choices.promptModes())
            .<Fields.RadioField>startingAs(LlmSettings.PROMPT_MODE_REPLACE));

    form.add(
        new Fields.StringListField("include_filters", "CSS/JSONPath/JQ/XPath Filters")
            .<Fields.StringListField>startingAs("")
            .checking(Checks.selectors(true, true)));
    form.add(
        new Fields.StringListField("subtractive_selectors", "Remove elements")
            .checking(Checks.selectors(true, false)));
    form.add(new Fields.StringListField("extract_lines_containing", "Extract lines containing"));
    form.add(
        new Fields.StringListField("extract_text", "Extract text")
            .checking(Checks.regexList()));
    form.add(new Fields.StringField("title", "Title").startingAs(""));
    form.add(
        new Fields.StringListField("ignore_text", "Ignore lines containing")
            .checking(Checks.regexList()));
    form.add(new Fields.StringDictField("headers", "Request headers"));
    form.add(new Fields.TextAreaField("body", "Request body"));
    form.add(
        new Fields.SelectField("method", "Request method", Choices.requestMethods())
            .<Fields.SelectField>startingAs("GET"));
    form.add(
        new Fields.BooleanField(
                "ignore_status_codes",
                "Ignore status codes (process non-2xx status codes as normal)")
            .<Fields.BooleanField>startingAs(false));
    form.add(
        new Fields.BooleanField(
                "check_unique_lines", "Only trigger when unique lines appear in all history")
            .<Fields.BooleanField>startingAs(false));
    form.add(
        new Fields.BooleanField("remove_duplicate_lines", "Remove duplicate lines of text")
            .<Fields.BooleanField>startingAs(false));
    form.add(
        new Fields.BooleanField("sort_text_alphabetically", "Sort text alphabetically")
            .<Fields.BooleanField>startingAs(false));
    form.add(new Fields.TernaryField("strip_ignored_lines", "Strip ignored lines"));
    form.add(
        new Fields.BooleanField(
                "trim_text_whitespace", "Trim whitespace before and after text")
            .<Fields.BooleanField>startingAs(false));

    form.add(
        new Fields.BooleanField("filter_text_added", "Added lines")
            .<Fields.BooleanField>startingAs(true));
    form.add(
        new Fields.BooleanField("filter_text_replaced", "Replaced/changed lines")
            .<Fields.BooleanField>startingAs(true));
    form.add(
        new Fields.BooleanField("filter_text_removed", "Removed lines")
            .<Fields.BooleanField>startingAs(true));

    form.add(
        new Fields.StringListField(
                "trigger_text", "Keyword triggers - Trigger/wait for text")
            .checking(Checks.regexList()));
    form.add(new Form.Repeated("browser_steps", "Browser Step", Forms::browserStep, 10));
    form.add(
        new Fields.StringListField(
                "text_should_not_be_present", "Block change-detection while text matches")
            .checking(Checks.regexList()));
    form.add(
        new Fields.TextAreaField(
                "webdriver_js_execute_code", "Execute JavaScript before change detection")
            .renderedWith("rows", "5"));

    form.add(
        new Fields.SubmitField("save_button", "Save")
            .renderedWith("class", "pure-button pure-button-primary"));

    form.add(new Fields.RadioField("proxy", "Proxy", new ArrayList<>()));
    form.add(
        new Fields.BooleanField(
                "filter_failure_notification_send",
                "Send a notification when the filter can no longer be found on the page")
            .<Fields.BooleanField>startingAs(false));
    form.add(new Fields.TernaryField("notification_muted", "Notifications", "Muted", "On"));
    form.add(
        new Fields.BooleanField(
                "notification_screenshot",
                "Attach screenshot to notification (where possible)")
            .<Fields.BooleanField>startingAs(false));

    form.add(
        new Fields.RadioField(
                "conditions_match_logic",
                "Match",
                List.of(
                    new String[] {"ALL", "Match all of the following"},
                    new String[] {"ANY", "Match any of the following"}))
            .<Fields.RadioField>startingAs("ALL"));
    form.add(new Form.Repeated("conditions", "Condition", Forms::conditionRow, 1));
    form.add(new Fields.TernaryField("use_page_title_in_list", "Use page <title> in list"));
    form.add(
        new Fields.IntegerField(
                "history_snapshot_max_length", "Number of history items per watch to keep")
            .<Fields.IntegerField>renderedWith("style", "width: 5em;")
            .checking(Checks.numberRange(2.0, null, "Number must be at least 2.")));

    addProcessorSettings(form, processor);
    return form;
  }

  /** The controls that belong to one kind of watch and are stored beside it. */
  private static void addProcessorSettings(Form form, String processor) {
    if ("restock_diff".equals(processor)) {
      Form restock = new Form();
      restock.add(
          new Fields.RadioField(
                  "in_stock_processing", "Re-stock detection", Choices.restockProcessing())
              .<Fields.RadioField>startingAs("in_stock_only"));
      restock.add(
          new Fields.FloatField("price_change_min", "Below price to trigger notification")
              .<Fields.FloatField>renderedWith("placeholder", "No limit")
              .renderedWith("size", "10"));
      restock.add(
          new Fields.FloatField("price_change_max", "Above price to trigger notification")
              .<Fields.FloatField>renderedWith("placeholder", "No limit")
              .renderedWith("size", "10"));
      restock.add(
          new Fields.FloatField(
                  "price_change_threshold_percent",
                  "Threshold (%) for price changes since the previous check")
              .<Fields.FloatField>renderedWith("placeholder", "0%")
              .<Fields.FloatField>renderedWith("size", "5")
              .checking(Checks.numberRange(0.0, 100.0, "Should be between 0 and 100")));
      restock.add(
          new Fields.BooleanField("follow_price_changes", "Follow price changes")
              .<Fields.BooleanField>startingAs(true));
      form.add(new Form.Nested("processor_config_restock_diff", "", restock));
      return;
    }
    if ("image_ssim_diff".equals(processor)) {
      form.add(
          new Fields.IntegerField(
                  "processor_config_min_change_percentage", "Minimum Change Percentage")
              .<Fields.IntegerField>renderedWith("placeholder", "Use global default (0.1)")
              .checking(Checks.numberRange(1.0, 100.0, "Must be between 0 and 100")));
      form.add(
          new Fields.SelectField(
                  "processor_config_pixel_difference_threshold_sensitivity",
                  "Pixel Difference Sensitivity",
                  Choices.screenshotSensitivity())
              .<Fields.SelectField>startingAs(""));
      form.add(
          new Fields.StringField("processor_config_bounding_box", "Bounding Box")
              .<Fields.StringField>renderedWith("style", "display: none;")
              .<Fields.StringField>renderedWith("id", "bounding_box")
              .<Fields.StringField>checking(
                  Checks.maximumLength(100, "Bounding box value is too long"))
              .checking(Checks.boundingBox()));
      form.add(
          new Fields.StringField("processor_config_selection_mode", "Selection Mode")
              .<Fields.StringField>renderedWith("style", "display: none;")
              .<Fields.StringField>renderedWith("id", "selection_mode")
              .<Fields.StringField>checking(
                  Checks.maximumLength(20, "Selection mode value is too long"))
              .checking(Checks.selectionMode()));
    }
  }

  /** The tab a kind of watch adds to the edit page, or nothing when it adds none. */
  public static String processorTabLabel(String processor) {
    return switch (processor) {
      case "restock_diff" -> "Restock & Price Detection";
      case "image_ssim_diff" -> "Screenshot Comparison";
      default -> null;
    };
  }

  public static Form globalSettingsRequests() {
    Form form = new Form();
    form.add(
        new RequiredGroup(
            "time_between_check",
            "Time Between Check",
            timeBetweenCheck(),
            REQUIRE_ONE_TIME_PART,
            Forms::intervalHasValue));
    form.add(new Form.Nested("time_schedule_limit", "", scheduleLimit()));
    form.add(new Fields.RadioField("proxy", "Default proxy", new ArrayList<>()));
    form.add(
        new Fields.IntegerField("jitter_seconds", "Random jitter seconds ± check")
            .<Fields.IntegerField>renderedWith("style", "width: 5em;")
            .checking(Checks.numberRange(0.0, null, ZERO_OR_MORE_SECONDS)));
    form.add(
        new Fields.IntegerField("workers", "Number of fetch workers")
            .<Fields.IntegerField>renderedWith("style", "width: 5em;")
            .checking(Checks.numberRange(1.0, 50.0, "Should be between 1 and 50")));
    form.add(
        new Fields.IntegerField("timeout", "Requests timeout in seconds")
            .<Fields.IntegerField>renderedWith("style", "width: 5em;")
            .checking(Checks.numberRange(1.0, 999.0, "Should be between 1 and 999")));
    form.add(new Form.Repeated("extra_proxies", "Proxy", Forms::extraProxy, 5));
    form.add(new Form.Repeated("extra_browsers", "Browser", Forms::extraBrowser, 5));
    form.add(
        new Form.Nested("default_ua", "Default User-Agent overrides", defaultUserAgents()));
    return form;
  }

  public static Form globalSettingsUi() {
    Form form = new Form();
    form.add(
        new Fields.BooleanField("open_diff_in_new_tab", "Open 'History' page in a new tab")
            .<Fields.BooleanField>startingAs(true));
    form.add(
        new Fields.BooleanField("socket_io_enabled", "Realtime UI Updates Enabled")
            .<Fields.BooleanField>startingAs(true));
    form.add(
        new Fields.BooleanField("favicons_enabled", "Favicons Enabled")
            .<Fields.BooleanField>startingAs(true));
    form.add(
        new Fields.BooleanField("use_page_title_in_list", "Use page <title> in watch overview list"));
    form.add(
        new Fields.SelectField("timeago_format", "Relative time format", Choices.timeagoFormats())
            .<Fields.SelectField>startingAs("long"));
    form.add(
        new Fields.SelectField("sidebar_mode", "Navigation sidebar", Choices.sidebarModes())
            .<Fields.SelectField>startingAs("collapsed"));
    return form;
  }

  public static Form globalSettingsApplication(Map<String, Object> extraTokens) {
    Form form = new Form();
    addCommonSettings(form, extraTokens);
    form.field("fetch_backend").setData("html_requests");

    form.add(
        new Fields.BooleanField(
                "api_access_token_enabled", "API access token security check enabled")
            .<Fields.BooleanField>startingAs(true));
    form.add(
        new Fields.StringField("base_url", "Notification base URL override")
            .renderedWith("placeholder", envOr("BASE_URL", "Not set")));
    form.add(
        new Fields.BooleanField("empty_pages_are_a_change", "Treat empty pages as a change?")
            .<Fields.BooleanField>startingAs(false));
    form.add(
        new Fields.StringListField("global_ignore_text", "Ignore Text")
            .checking(Checks.regexList()));
    form.add(
        new Fields.StringListField("global_subtractive_selectors", "Remove elements")
            .checking(Checks.selectors(true, false)));
    form.add(new Fields.BooleanField("ignore_whitespace", "Ignore whitespace"));
    form.add(
        new Fields.FloatField("min_change_percentage", "Screenshot: Minimum Change Percentage")
            .<Fields.FloatField>startingAs(0.1)
            .<Fields.FloatField>renderedWith("placeholder", "0.1")
            .<Fields.FloatField>renderedWith("style", "width: 8em;")
            .checking(Checks.numberRange(0.0, 100.0, "Must be between 0 and 100")));
    form.add(
        new SpecialFields.SaltedPasswordField("password", "Password")
            .renderedWith("autocomplete", "new-password"));
    form.add(
        new Fields.IntegerField("pager_size", "Pager size")
            .<Fields.IntegerField>renderedWith("style", "width: 5em;")
            .checking(Checks.numberRange(0.0, null, "Should be atleast zero (disabled)")));
    form.add(
        new Fields.SelectField("rss_content_format", "RSS Content format", Choices.rssFormats()));
    form.add(
        new Fields.SelectField(
            "rss_template_type", "RSS <description> body built from",
            Choices.rssTemplateTypes()));
    form.add(
        new Fields.TextAreaField(
                "rss_template_override", "RSS \"System default\" template override")
            .<Fields.TextAreaField>renderedWith("rows", "5")
            .<Fields.TextAreaField>renderedWith(
                "placeholder", io.akka.changedetection.model.AppSettings.RSS_TEMPLATE_HTML_DEFAULT)
            .checking(Checks.jinjaTemplate(extraTokens)));
    form.add(
        new Fields.SubmitField("removepassword_button", "Remove password")
            .renderedWith("class", "pure-button pure-button-primary"));
    form.add(
        new Fields.BooleanField("render_anchor_tag_content", "Render anchor tag content")
            .<Fields.BooleanField>startingAs(false));
    form.add(
        new Fields.BooleanField(
                "shared_diff_access",
                "Allow anonymous access to watch history page when password is enabled")
            .<Fields.BooleanField>startingAs(false));
    form.add(new Fields.BooleanField("strip_ignored_lines", "Strip ignored lines"));
    form.add(
        new Fields.BooleanField("rss_hide_muted_watches", "Hide muted watches from RSS feed")
            .<Fields.BooleanField>startingAs(true));
    form.add(
        new Fields.BooleanField("rss_reader_mode", "Enable RSS reader mode ")
            .<Fields.BooleanField>startingAs(false));
    form.add(
        new Fields.IntegerField("rss_diff_length", "Number of changes to show in watch RSS feed")
            .<Fields.IntegerField>renderedWith("style", "width: 5em;")
            .checking(Checks.numberRange(0.0, null, "Should contain zero or more attempts")));
    form.add(
        new Fields.IntegerField(
                "filter_failure_notification_threshold_attempts",
                "Number of times the filter can be missing before sending a notification")
            .<Fields.IntegerField>renderedWith("style", "width: 5em;")
            .checking(Checks.numberRange(0.0, null, "Should contain zero or more attempts")));
    form.add(
        new Fields.IntegerField(
                "history_snapshot_max_length", "Number of history items per watch to keep")
            .<Fields.IntegerField>renderedWith("style", "width: 5em;")
            .checking(Checks.numberRange(2.0, null, "Number must be at least 2.")));
    form.add(new Form.Nested("ui", "", globalSettingsUi()));
    return form;
  }

  public static Form globalSettingsLlm() {
    Form form = new Form();
    form.add(
        new Fields.StringField("model", "Model")
            .<Fields.StringField>renderedWith("placeholder", "gpt-4o-mini")
            .renderedWith("style", "width: 24em;"));
    form.add(
        new Fields.PasswordField("api_key", "API Key")
            .<Fields.PasswordField>renderedWith("autocomplete", "off")
            .renderedWith("style", "width: 24em;"));
    form.add(
        new Fields.StringField("api_base", "API Base URL")
            .<Fields.StringField>renderedWith(
                "placeholder", "http://localhost:11434  (Ollama / custom endpoints only)")
            .<Fields.StringField>renderedWith("style", "width: 24em;")
            .checking(Checks.llmApiBase()));
    form.add(new Fields.HiddenField("provider_kind").startingAs(""));
    form.add(
        new Fields.IntegerField(
                "local_token_multiplier", "Token multiplier for local reasoning models")
            .<Fields.IntegerField>startingAs(LlmSettings.DEFAULT_LOCAL_TOKEN_MULTIPLIER)
            .<Fields.IntegerField>renderedWith("placeholder", "5")
            .<Fields.IntegerField>renderedWith("style", "width: 6em;")
            .checking(Checks.numberRange(1.0, 20.0, "Must be between 1 and 20")));
    form.add(
        new Fields.TextAreaField("change_summary_default", "Default AI Change Summary prompt")
            .<Fields.TextAreaField>startingAs("")
            .<Fields.TextAreaField>renderedWith("rows", "12")
            .<Fields.TextAreaField>renderedWith("style", "width: 100%; ")
            .checking(Checks.maximumLength(2000, "Field cannot be longer than 2000 characters.")));
    form.add(
        new Fields.IntegerField(
                "max_tokens_per_count_period", "Max tokens per watch per period")
            .<Fields.IntegerField>startingAs(0)
            .<Fields.IntegerField>renderedWith("placeholder", "0 = unlimited")
            .<Fields.IntegerField>renderedWith("style", "width: 8em;")
            .checking(Checks.numberRange(0.0, null, "Number must be at least 0.")));
    form.add(
        new Fields.IntegerField("token_budget_month", "Monthly token budget")
            .<Fields.IntegerField>startingAs(0)
            .<Fields.IntegerField>renderedWith("style", "width: 10em;")
            .checking(Checks.numberRange(0.0, null, "Number must be at least 0.")));
    form.add(
        new Fields.IntegerField("max_input_chars", "Max input characters")
            .<Fields.IntegerField>startingAs(LlmSettings.DEFAULT_MAX_INPUT_CHARS)
            .<Fields.IntegerField>renderedWith("placeholder", "100000")
            .<Fields.IntegerField>renderedWith("style", "width: 10em;")
            .checking(Checks.numberRange(1.0, null, "Number must be at least 1.")));
    form.add(
        new Fields.BooleanField("enabled", "Enable AI / LLM features")
            .<Fields.BooleanField>startingAs(true));
    form.add(
        new Fields.BooleanField(
                "override_diff_with_summary",
                "Replace {{diff}} notification token with AI summary")
            .<Fields.BooleanField>startingAs(true));
    form.add(
        new Fields.BooleanField(
                "restock_use_fallback_extract",
                "Use LLM as a fallback for extracting price and restock info")
            .<Fields.BooleanField>startingAs(true));
    form.add(
        new Fields.BooleanField("debug", "Enable LLM debug logging")
            .<Fields.BooleanField>startingAs(false));
    form.add(
        new Fields.SelectField(
                "thinking_budget", "AI thinking budget (tokens)", Choices.thinkingBudgets())
            .<Fields.SelectField>startingAs(
                String.valueOf(LlmSettings.DEFAULT_THINKING_BUDGET)));
    form.add(
        new Fields.SelectField(
                "max_summary_tokens", "Max AI summary length (tokens)",
                Choices.summaryTokenCaps())
            .<Fields.SelectField>startingAs(
                String.valueOf(LlmSettings.DEFAULT_MAX_SUMMARY_TOKENS)));
    form.add(
        new Fields.RadioField(
                "budget_action", "When monthly token budget is reached", Choices.budgetActions())
            .<Fields.RadioField>startingAs("skip_llm"));
    form.add(
        new Fields.RadioField(
                "watchlist_overview_summary",
                "Watchlist \"Summary\" link compares",
                Choices.overviewSummaryBaselines())
            .<Fields.RadioField>startingAs("second_last_version"));
    return form;
  }

  public static Form globalSettings(Map<String, Object> extraTokens) {
    Form form = new Form();
    form.add(new Form.Nested("requests", "", globalSettingsRequests()));
    form.add(new Form.Nested("application", "", globalSettingsApplication(extraTokens)));
    form.add(new Form.Nested("llm", "", globalSettingsLlm()));
    form.add(
        new Fields.SubmitField("save_button", "Save")
            .renderedWith("class", "pure-button pure-button-primary"));
    return form;
  }

  /** The page that edits only the notification settings. */
  public static Form appriseNotifications(Map<String, Object> extraTokens) {
    Form form = new Form();
    form.add(
        new Fields.StringListField("notification_urls", "Notification URL List")
            .<Fields.StringListField>checking(Checks.notificationTargets())
            .checking(Checks.jinjaTemplate(extraTokens)));
    form.add(
        new Fields.StringField("notification_title", "Notification Title")
            .<Fields.StringField>startingAs(DEFAULT_NOTIFICATION_TITLE)
            .checking(Checks.jinjaTemplate(extraTokens)));
    form.add(
        new Fields.TextAreaField("notification_body", "Notification Body")
            .<Fields.TextAreaField>startingAs(DEFAULT_NOTIFICATION_BODY)
            .checking(Checks.jinjaTemplate(extraTokens)));
    form.add(
        new Fields.SelectField(
            "notification_format", "Notification format", Choices.notificationFormats()));
    form.add(
        new Fields.StringField("base_url", "Notification base URL override")
            .renderedWith("placeholder", envOr("BASE_URL", "Not set")));
    form.add(
        new Fields.SubmitField("save_button", "Save")
            .renderedWith("class", "pure-button pure-button-primary"));
    return form;
  }

  public static Form importWatches() {
    Form form = new Form();
    form.add(
        new Fields.RadioField("processor", "Processor", Choices.processors())
            .<Fields.RadioField>startingAs(Choices.defaultProcessor()));
    form.add(new Fields.TextAreaField("urls", "URLs"));
    form.add(new Fields.FileField("xlsx_file", "Upload .xlsx file", List.of("xlsx")));
    form.add(
        new Fields.SelectField("file_mapping", "File mapping", Choices.importMappings())
            .required());
    return form;
  }

  public static Form restoreBackup() {
    Form form = new Form();
    form.add(new Fields.FileField("zip_file", "Backup zip file", List.of("zip")));
    form.add(
        new Fields.BooleanField("include_groups", "Include groups")
            .<Fields.BooleanField>startingAs(true));
    form.add(
        new Fields.BooleanField(
                "include_groups_replace_existing", "Replace existing groups of the same UUID")
            .<Fields.BooleanField>startingAs(true));
    form.add(
        new Fields.BooleanField("include_watches", "Include watches")
            .<Fields.BooleanField>startingAs(true));
    form.add(
        new Fields.BooleanField(
                "include_watches_replace_existing", "Replace existing watches of the same UUID")
            .<Fields.BooleanField>startingAs(true));
    form.add(new Fields.SubmitField("submit", "Restore backup"));
    return form;
  }

  public static Form extractData() {
    Form form = new Form();
    form.add(
        new Fields.StringField("extract_regex", "RegEx to extract")
            .<Fields.StringField>required()
            .checking(Checks.singleRegex()));
    form.add(
        new Fields.SubmitField("extract_submit_button", "Extract as CSV")
            .renderedWith("class", "pure-button pure-button-primary"));
    return form;
  }

  public static Form singleTag() {
    Form form = new Form();
    form.add(
        new Fields.StringField("name", "Tag name")
            .<Fields.StringField>required()
            .renderedWith("placeholder", "Name"));
    form.add(
        new Fields.SubmitField("save_button", "Save")
            .renderedWith("class", "pure-button pure-button-primary"));
    return form;
  }

  /**
   * The form for editing a group, which is a watch's form plus what a group alone can say.
   *
   * <p>A group carries the restock controls whatever its own kind is, because a group's settings
   * are applied to watches of any kind that belong to it.
   */
  public static Form tag(Map<String, Object> tags, Map<String, Object> extraTokens) {
    Form form = watch("restock_diff", tags, extraTokens);
    form.add(
        new Fields.BooleanField(
                "overrides_watch", "Activate for individual watches in this tag/group?")
            .<Fields.BooleanField>startingAs(false));
    form.add(
        new Fields.StringField("url_match_pattern", "Auto-apply to watches with URLs matching")
            .renderedWith(
                "placeholder", "e.g. *://example.com/* or github.com/myorg"));
    form.add(
        new Fields.StringField("tag_colour", "Tag colour")
            .<Fields.StringField>startingAs("")
            .checking(
                Checks.matching(
                    Checks.CSS_HEX_COLOUR, "Must be a hex colour, for example #4f8ef7")));
    form.field("llm_intent").renderedWith("placeholder", LLM_INTENT_TAG_PLACEHOLDER);
    return form;
  }

  static String envOr(String variable, String fallback) {
    String value = System.getenv(variable);
    return value == null || value.isBlank() ? fallback : value;
  }

  /** The values a form produced, with the parts the storage keeps elsewhere separated out. */
  public static Map<String, Object> withoutSubmitButtons(Map<String, Object> data) {
    Map<String, Object> out = new LinkedHashMap<>(data);
    out.keySet().removeIf(key -> key.endsWith("_button") || key.equals("submit"));
    return out;
  }
}
