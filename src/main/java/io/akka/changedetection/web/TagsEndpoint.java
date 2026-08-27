package io.akka.changedetection.web;

import akka.http.javadsl.model.HttpEntity;
import akka.http.javadsl.model.HttpResponse;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.AbstractHttpEndpoint;
import io.akka.changedetection.application.SettingsEntity;
import io.akka.changedetection.application.Store;
import io.akka.changedetection.forms.Form;
import io.akka.changedetection.forms.Forms;
import io.akka.changedetection.jinja.Environment;
import io.akka.changedetection.jinja.PyValue;
import io.akka.changedetection.llm.Evaluator;
import io.akka.changedetection.model.Fields;
import io.akka.changedetection.model.Watch;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Groups: the things a watch can belong to, and the settings a group hands down.
 *
 * <p>A group is a watch in all but name -- it carries the same filters, the same notification
 * settings and the same price rules -- which is why editing one uses the watch's own form. What
 * a group adds is who it applies to: named members, and anything whose address matches its
 * pattern.
 */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint
public class TagsEndpoint extends AbstractHttpEndpoint {

  private final ComponentClient componentClient;

  public TagsEndpoint(ComponentClient componentClient) {
    this.componentClient = componentClient;
  }

  @Get("/tags/list")
  public HttpResponse overview() {
    Store store = new Store(componentClient);
    Render.Page page = Render.page(requestContext(), store, "/tags/list", "tags");
    HttpResponse refusal = Guard.requireSignIn(page, "/tags/list");
    if (refusal != null) {
      return refusal;
    }
    Map<String, Object> variables = new LinkedHashMap<>();
    variables.put("app_rss_token", store.application().get("rss_access_token"));
    variables.put("available_tags", sortedTags(store));
    variables.put("form", Forms.singleTag());
    variables.put("tag_count", tagCounts(store));
    variables.put("datastore", new DatastoreView(store));
    return page.session()
        .attachTo(Requests.html(Render.render(page, "groups-overview.html", variables)));
  }

  @Post("/tags/add")
  public HttpResponse add(HttpEntity.Strict body) {
    Operations operations = new Operations(componentClient);
    Store store = operations.store();
    Render.Page page = Render.page(requestContext(), store, "/tags/add", "tags");
    HttpResponse refusal = Guard.requireSignIn(page, null);
    if (refusal != null) {
      return refusal;
    }
    Requests.Submission submitted = Requests.submission(requestContext(), body);
    Form form = Forms.singleTag();
    form.populate(submitted.values());
    if (!form.validate()) {
      for (Object messages : form.errors().values()) {
        if (messages instanceof List<?> list) {
          List<String> texts = new ArrayList<>();
          for (Object message : list) {
            texts.add(String.valueOf(message));
          }
          page.session().flash(String.join(",", texts), "error");
        }
      }
      return page.session().attachTo(Requests.redirect("/tags/list"));
    }
    String title = submitted.first("name").strip();
    if (exists(store, title)) {
      page.session().flash("The tag \"" + title + "\" already exists", "error");
      return page.session().attachTo(Requests.redirect("/tags/list"));
    }
    operations.addTag(title);
    page.session().flash("Tag added");
    return page.session().attachTo(Requests.redirect("/tags/list"));
  }

  @Get("/tags/mute/{uuid}")
  public HttpResponse mute(String uuid) {
    Store store = new Store(componentClient);
    Render.Page page = Render.page(requestContext(), store, "/tags/mute/" + uuid, "tags");
    HttpResponse refusal = Guard.requireSignIn(page, null);
    if (refusal != null) {
      return refusal;
    }
    Map<String, Object> tag = store.tags().get(uuid);
    if (tag != null) {
      Map<String, Object> change = new LinkedHashMap<>();
      change.put("notification_muted", !Fields.truthy(tag.get("notification_muted")));
      componentClient
          .forKeyValueEntity(SettingsEntity.ID)
          .method(SettingsEntity::updateTag)
          .invoke(new SettingsEntity.UpdateTag(uuid, change));
    }
    return page.session().attachTo(Requests.redirect("/tags/list"));
  }

  @Post("/tags/delete/{uuid}")
  public HttpResponse delete(String uuid) {
    Operations operations = new Operations(componentClient);
    Store store = operations.store();
    Render.Page page = Render.page(requestContext(), store, "/tags/delete/" + uuid, "tags");
    HttpResponse refusal = Guard.requireSignIn(page, null);
    if (refusal != null) {
      return refusal;
    }
    componentClient
        .forKeyValueEntity(SettingsEntity.ID)
        .method(SettingsEntity::deleteTag)
        .invoke(new SettingsEntity.DeleteTag(uuid));
    detach(operations, uuid);
    page.session().flash("Tag deleted, removing from watches in background");
    return page.session().attachTo(Requests.redirect("/tags/list"));
  }

  @Post("/tags/unlink/{uuid}")
  public HttpResponse unlink(String uuid) {
    Operations operations = new Operations(componentClient);
    Render.Page page =
        Render.page(requestContext(), operations.store(), "/tags/unlink/" + uuid, "tags");
    HttpResponse refusal = Guard.requireSignIn(page, null);
    if (refusal != null) {
      return refusal;
    }
    detach(operations, uuid);
    page.session().flash("Unlinking tag from watches in background");
    return page.session().attachTo(Requests.redirect("/tags/list"));
  }

  @Post("/tags/delete_all")
  public HttpResponse deleteAll() {
    Operations operations = new Operations(componentClient);
    Store store = operations.store();
    Render.Page page = Render.page(requestContext(), store, "/tags/delete_all", "tags");
    HttpResponse refusal = Guard.requireSignIn(page, null);
    if (refusal != null) {
      return refusal;
    }
    for (String uuid : new ArrayList<>(store.tags().keySet())) {
      componentClient
          .forKeyValueEntity(SettingsEntity.ID)
          .method(SettingsEntity::deleteTag)
          .invoke(new SettingsEntity.DeleteTag(uuid));
    }
    for (Map.Entry<String, Watch> entry : store.allWatches().entrySet()) {
      if (!entry.getValue().fields().strings("tags").isEmpty()) {
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("tags", new ArrayList<>());
        operations.update(entry.getKey(), change);
      }
    }
    page.session().flash("All tags deleted, clearing from watches in background");
    return page.session().attachTo(Requests.redirect("/tags/list"));
  }

  @Get("/tags/edit/{uuid}")
  public HttpResponse editPage(String uuid) {
    Store store = new Store(componentClient);
    Render.Page page = Render.page(requestContext(), store, "/tags/edit/" + uuid, "tags");
    HttpResponse refusal = Guard.requireSignIn(page, "/tags/edit/" + uuid);
    if (refusal != null) {
      return refusal;
    }
    String resolved = resolve(store, uuid);
    Map<String, Object> tag = store.tags().get(resolved);
    if (tag == null) {
      page.session().flash("Tag not found", "error");
      return page.session().attachTo(Requests.redirect("/"));
    }
    Form form = Forms.tag(new LinkedHashMap<>(store.tags()), new LinkedHashMap<>());
    form.fill(tag);
    return page.session()
        .attachTo(Requests.html(renderEdit(page, store, resolved, tag, form)));
  }

  @Post("/tags/edit/{uuid}")
  public HttpResponse editSubmit(String uuid, HttpEntity.Strict body) {
    Store store = new Store(componentClient);
    Render.Page page = Render.page(requestContext(), store, "/tags/edit/" + uuid, "tags");
    HttpResponse refusal = Guard.requireSignIn(page, null);
    if (refusal != null) {
      return refusal;
    }
    String resolved = resolve(store, uuid);
    Map<String, Object> tag = store.tags().get(resolved);
    if (tag == null) {
      page.session().flash("Tag not found", "error");
      return page.session().attachTo(Requests.redirect("/"));
    }
    Requests.Submission submitted = Requests.submission(requestContext(), body);
    Form form = Forms.tag(new LinkedHashMap<>(store.tags()), new LinkedHashMap<>());
    form.populate(submitted.values());

    // Only one field is checked before storing, and it is the one that goes into a stylesheet:
    // anything but a plain colour there would be a rule of the submitter's choosing on every
    // page that shows the group.
    io.akka.changedetection.forms.Field colour = form.field("tag_colour");
    if (colour != null && !colour.validate()) {
      for (String message : colour.errors()) {
        page.session().flash(message, "error");
      }
      return page.session()
          .attachTo(
              Requests.redirect(Routes.build("tags.form_tag_edit", Map.of("uuid", resolved))));
    }

    Map<String, Object> change = new LinkedHashMap<>(form.data());
    change.put("processor", "restock_diff");
    componentClient
        .forKeyValueEntity(SettingsEntity.ID)
        .method(SettingsEntity::updateTag)
        .invoke(new SettingsEntity.UpdateTag(resolved, change));

    // A group's settings feed into every watch that belongs to it, so the stored checksum of
    // each is dropped: without that, a watch would skip its next check as unchanged and the
    // new settings would not take effect until the page itself moved.
    Operations operations = new Operations(componentClient);
    for (Map.Entry<String, Watch> entry : store.allWatches().entrySet()) {
      if (store.tagsForWatch(entry.getKey()).containsKey(resolved)) {
        store.saveSideStore(entry.getKey(), "last-raw-checksum", "");
      }
    }
    page.session().flash("Updated");
    return page.session().attachTo(Requests.redirect("/tags/list"));
  }

  // ------------------------------------------------------------------ bits

  String renderEdit(
      Render.Page page, Store store, String uuid, Map<String, Object> tag, Form form) {
    Environment environment = Render.environmentFor(page, store.application());
    Map<String, Object> matching = new LinkedHashMap<>();
    for (Map.Entry<String, Watch> entry : store.allWatches().entrySet()) {
      if (store.tagsForWatch(entry.getKey()).containsKey(uuid)) {
        matching.put(entry.getKey(), entry.getValue());
      }
    }
    Map<String, Object> variables = new LinkedHashMap<>();
    variables.put("data", tag);
    variables.put("form", form);
    variables.put("watch", tag);
    variables.put("uuid", uuid);
    variables.put("datastore", new DatastoreView(store));
    variables.put("extra_notification_token_placeholder_info", new ArrayList<>());
    variables.put("llm_configured", Evaluator.config(store.llmSurroundings()) != null);
    variables.put("extra_form_content", null);
    variables.put("extra_tab_content", Forms.processorTabLabel("restock_diff"));
    variables.put("matching_watches", matching);
    variables.put("settings_application", store.application());
    variables.put("available_timezones", io.akka.changedetection.forms.Choices.timezones());
    variables.put(
        "timezone_default_config", store.application().get("scheduler_timezone_default"));
    return Render.renderWith(page, environment, "edit-tag.html", variables);
  }

  static String resolve(Store store, String uuid) {
    if (!uuid.equals("first")) {
      return uuid;
    }
    List<String> uuids = new ArrayList<>(store.tags().keySet());
    return uuids.isEmpty() ? uuid : uuids.get(uuids.size() - 1);
  }

  static void detach(Operations operations, String tagUuid) {
    for (Map.Entry<String, Watch> entry : operations.store().allWatches().entrySet()) {
      List<String> tags = new ArrayList<>(entry.getValue().fields().strings("tags"));
      if (tags.remove(tagUuid)) {
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("tags", tags);
        operations.update(entry.getKey(), change);
      }
    }
  }

  static boolean exists(Store store, String title) {
    String wanted = title.strip().toLowerCase(Locale.ROOT);
    for (Map<String, Object> tag : store.tags().values()) {
      if (String.valueOf(tag.getOrDefault("title", "")).strip().toLowerCase(Locale.ROOT)
          .equals(wanted)) {
        return true;
      }
    }
    return false;
  }

  static List<Object> sortedTags(Store store) {
    List<Map.Entry<String, Map<String, Object>>> entries =
        new ArrayList<>(store.tags().entrySet());
    entries.sort(
        Comparator.comparing(entry -> String.valueOf(entry.getValue().getOrDefault("title", ""))));
    List<Object> out = new ArrayList<>();
    for (var entry : entries) {
      out.add(new PyValue.Tuple(entry.getKey(), entry.getValue()));
    }
    return out;
  }

  /** How many watches name each group, which the overview shows beside it. */
  static Map<String, Object> tagCounts(Store store) {
    Map<String, Object> counts = new LinkedHashMap<>();
    for (Watch watch : store.allWatches().values()) {
      for (String tagUuid : watch.fields().strings("tags")) {
        Object current = counts.get(tagUuid);
        counts.put(tagUuid, (current instanceof Number number ? number.intValue() : 0) + 1);
      }
    }
    return counts;
  }
}
