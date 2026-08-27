package io.akka.changedetection.web;

import akka.http.javadsl.model.ContentTypes;
import akka.http.javadsl.model.HttpEntity;
import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.model.MediaTypes;
import akka.http.javadsl.model.StatusCodes;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.AbstractHttpEndpoint;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.akka.changedetection.application.SettingsEntity;
import io.akka.changedetection.application.Store;
import io.akka.changedetection.application.WatchState;
import io.akka.changedetection.model.Watch;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Taking a copy of everything, and putting one back.
 *
 * <p>The archive has the same shape the original's does -- the settings as one document at the
 * top, then one directory per watch or tag named by its identifier, holding that record and its
 * stored snapshots. Keeping the shape is the whole point: an archive taken from either system
 * restores into the other, which is the only test of a backup anybody actually runs.
 *
 * <p>What differs is where the archive lives. The original writes it beside its data and lists
 * the directory afterwards; there is no such directory here, so the archive is built on demand
 * and handed straight to the caller, and the list of previous ones is what has been kept in the
 * store rather than what is on a disk.
 */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint
public class BackupsEndpoint extends AbstractHttpEndpoint {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final DateTimeFormatter STAMP =
      DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneId.systemDefault());

  private static final Pattern BACKUP_NAME =
      Pattern.compile("^changedetection-backup-\\d+\\.zip$");

  private static final Pattern UUID_DIRECTORY =
      Pattern.compile(
          "^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
          Pattern.CASE_INSENSITIVE);

  /** How large an uploaded archive may be. */
  private static final long MAX_UPLOAD_BYTES = megabytes("MAX_RESTORE_UPLOAD_MB", 256);

  /** How large it may be once unpacked, which is the guard against an archive built to explode. */
  private static final long MAX_DECOMPRESSED_BYTES =
      megabytes("MAX_RESTORE_DECOMPRESSED_MB", 1024);

  private final ComponentClient componentClient;

  public BackupsEndpoint(ComponentClient componentClient) {
    this.componentClient = componentClient;
  }

  @Get("/backups/")
  public HttpResponse index() {
    return listPage("/backups/");
  }

  @Get("/backups/create")
  public HttpResponse createPage() {
    return listPage("/backups/create");
  }

  @Get("/backups/request-backup")
  public HttpResponse requestBackup() {
    Store store = new Store(componentClient);
    Render.Page page = Render.page(requestContext(), store, "/backups/request-backup", "backups");
    HttpResponse refusal = Guard.requireSignIn(page, null);
    if (refusal != null) {
      return refusal;
    }
    if (Backups.list(store).size() > maxNumberOfBackups()) {
      page.session().flash("Maximum number of backups reached, please remove some", "error");
      return page.session().attachTo(Requests.redirect("/backups/"));
    }
    byte[] archive = build(store);
    String name = "changedetection-backup-" + STAMP.format(Instant.now()) + ".zip";
    Backups.keep(store, componentClient, name, archive);
    page.session().flash("Backup building in background, check back in a few minutes.");
    return page.session().attachTo(Requests.redirect("/backups/"));
  }

  @Get("/backups/download/{filename}")
  public HttpResponse download(String filename) {
    Store store = new Store(componentClient);
    Render.Page page =
        Render.page(requestContext(), store, "/backups/download/" + filename, "backups");
    HttpResponse refusal = Guard.requireSignIn(page, null);
    if (refusal != null) {
      return refusal;
    }
    String wanted = filename.strip();
    List<Backups.Entry> available = Backups.list(store);
    if ("latest".equals(wanted)) {
      if (available.isEmpty()) {
        return Requests.notFound();
      }
      wanted = available.get(0).filename();
    }
    if (!BACKUP_NAME.matcher(wanted).matches()) {
      return Requests.text(StatusCodes.BAD_REQUEST, "Bad Request");
    }
    byte[] archive = Backups.read(store, wanted);
    if (archive == null) {
      return Requests.notFound();
    }
    return Requests.download(
        wanted, ContentTypes.create(MediaTypes.APPLICATION_ZIP), archive);
  }

  @Post("/backups/remove-backups")
  public HttpResponse removeBackups() {
    Store store = new Store(componentClient);
    Render.Page page = Render.page(requestContext(), store, "/backups/remove-backups", "backups");
    HttpResponse refusal = Guard.requireSignIn(page, null);
    if (refusal != null) {
      return refusal;
    }
    Backups.removeAll(store, componentClient);
    page.session().flash("Backups were deleted.");
    return page.session().attachTo(Requests.redirect("/backups/"));
  }

  @Get("/backups/restore")
  public HttpResponse restorePage() {
    Store store = new Store(componentClient);
    Render.Page page = Render.page(requestContext(), store, "/backups/restore", "backups");
    HttpResponse refusal = Guard.requireSignIn(page, "/backups/restore");
    if (refusal != null) {
      return refusal;
    }
    Map<String, Object> variables = new LinkedHashMap<>();
    variables.put("form", io.akka.changedetection.forms.Forms.restoreBackup());
    variables.put("restore_running", false);
    variables.put("max_upload_mb", MAX_UPLOAD_BYTES / (1024 * 1024));
    variables.put("max_decompressed_mb", MAX_DECOMPRESSED_BYTES / (1024 * 1024));
    return page.session()
        .attachTo(Requests.html(Render.render(page, "backup_restore.html", variables)));
  }

  @Post("/backups/restore/start")
  public HttpResponse restoreStart(HttpEntity.Strict body) {
    Operations operations = new Operations(componentClient);
    Store store = operations.store();
    Render.Page page = Render.page(requestContext(), store, "/backups/restore/start", "backups");
    HttpResponse refusal = Guard.requireSignIn(page, null);
    if (refusal != null) {
      return refusal;
    }
    Requests.Submission submitted = Requests.submission(requestContext(), body);
    Requests.Upload upload = submitted.upload("zip_file");
    if (upload == null || upload.filename().isEmpty()) {
      page.session().flash("No file uploaded", "error");
      return page.session().attachTo(Requests.redirect("/backups/restore"));
    }
    if (!upload.filename().toLowerCase(java.util.Locale.ROOT).endsWith(".zip")) {
      page.session().flash("File must be a .zip backup file", "error");
      return page.session().attachTo(Requests.redirect("/backups/restore"));
    }
    if (upload.content().length > MAX_UPLOAD_BYTES) {
      page.session()
          .flash(
              "Backup file is too large (max " + (MAX_UPLOAD_BYTES / (1024 * 1024)) + " MB)",
              "error");
      return page.session().attachTo(Requests.redirect("/backups/restore"));
    }

    boolean includeGroups = submitted.values().containsKey("include_groups");
    boolean replaceGroups = submitted.values().containsKey("include_groups_replace_existing");
    boolean includeWatches = submitted.values().containsKey("include_watches");
    boolean replaceWatches = submitted.values().containsKey("include_watches_replace_existing");

    try {
      restore(
          operations, upload.content(), includeGroups, replaceGroups, includeWatches,
          replaceWatches);
    } catch (BadArchive e) {
      page.session().flash(e.getMessage(), "error");
      return page.session().attachTo(Requests.redirect("/backups/restore"));
    }
    page.session().flash("Restore started in background, check back in a few minutes.");
    return page.session().attachTo(Requests.redirect("/backups/restore"));
  }

  // ------------------------------------------------------------------ making

  /** An archive that cannot be trusted to unpack. */
  static final class BadArchive extends RuntimeException {
    BadArchive(String message) {
      super(message);
    }
  }

  private HttpResponse listPage(String path) {
    Store store = new Store(componentClient);
    Render.Page page = Render.page(requestContext(), store, path, "backups");
    HttpResponse refusal = Guard.requireSignIn(page, path);
    if (refusal != null) {
      return refusal;
    }
    List<Map<String, Object>> rows = new ArrayList<>();
    for (Backups.Entry entry : Backups.list(store)) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("filename", entry.filename());
      row.put("filesize", String.format("%.2f", entry.sizeBytes() / (1024.0 * 1024.0)));
      row.put("creation_time", entry.createdAt());
      rows.add(row);
    }
    Map<String, Object> variables = new LinkedHashMap<>();
    variables.put("available_backups", rows);
    variables.put("backup_running", false);
    return page.session()
        .attachTo(Requests.html(Render.render(page, "backup_create.html", variables)));
  }

  static byte[] build(Store store) {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
      Map<String, Object> settings = new LinkedHashMap<>(store.settings().settings());
      settings.put("watching", watchDocuments(store));
      write(zip, "changedetection.json", MAPPER.writeValueAsBytes(settings));

      for (Map.Entry<String, Map<String, Object>> tag : store.tags().entrySet()) {
        write(
            zip,
            tag.getKey() + "/tag.json",
            MAPPER.writeValueAsBytes(tag.getValue()));
      }

      StringBuilder urls = new StringBuilder();
      StringBuilder urlsWithTags = new StringBuilder();
      for (String uuid : store.watchUuids()) {
        WatchState state = store.watch(uuid);
        if (!state.exists()) {
          continue;
        }
        Watch watch = state.asWatch();
        write(zip, uuid + "/watch.json", MAPPER.writeValueAsBytes(watch.asMap()));

        StringBuilder index = new StringBuilder();
        for (Long timestamp : state.history()) {
          String snapshot = store.snapshot(uuid, timestamp);
          write(
              zip,
              uuid + "/" + timestamp + ".txt",
              (snapshot == null ? "" : snapshot).getBytes(StandardCharsets.UTF_8));
          index.append(timestamp).append(',').append(timestamp).append(".txt").append('\n');
        }
        write(zip, uuid + "/" + historyIndexName(watch), index.toString()
            .getBytes(StandardCharsets.UTF_8));

        String url = watch.fields().string("url", "");
        urls.append(url).append("\r\n");
        urlsWithTags.append(url).append(' ')
            .append(watch.fields().strings("tags")).append("\r\n");
      }
      write(zip, "url-list.txt", urls.toString().getBytes(StandardCharsets.UTF_8));
      write(
          zip, "url-list-with-tags.txt",
          urlsWithTags.toString().getBytes(StandardCharsets.UTF_8));
    } catch (Exception e) {
      throw new RuntimeException("Could not build the backup: " + e.getMessage(), e);
    }
    return buffer.toByteArray();
  }

  /**
   * The index a watch's snapshots are listed in.
   *
   * <p>Named after the processor for every kind but the first, so that switching a watch's kind
   * does not make it show a history of a shape it can no longer read.
   */
  static String historyIndexName(Watch watch) {
    String processor = watch.fields().string("processor", "");
    if (processor.isEmpty() || processor.equals("text_json_diff")) {
      return "history.txt";
    }
    return "history-" + processor + ".txt";
  }

  private static Map<String, Object> watchDocuments(Store store) {
    Map<String, Object> out = new LinkedHashMap<>();
    for (Map.Entry<String, Watch> entry : store.allWatches().entrySet()) {
      out.put(entry.getKey(), entry.getValue().asMap());
    }
    return out;
  }

  private static void write(ZipOutputStream zip, String name, byte[] content) throws Exception {
    ZipEntry entry = new ZipEntry(name);
    zip.putNextEntry(entry);
    zip.write(content);
    zip.closeEntry();
  }

  // --------------------------------------------------------------- restoring

  static Map<String, Integer> restore(
      Operations operations,
      byte[] archive,
      boolean includeGroups,
      boolean replaceGroups,
      boolean includeWatches,
      boolean replaceWatches) {
    Store store = operations.store();
    Map<String, Map<String, byte[]>> byDirectory = new LinkedHashMap<>();
    long totalUncompressed = 0;

    try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
      ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        if (entry.isDirectory()) {
          continue;
        }
        String name = entry.getName().replace('\\', '/');
        // A member naming its way out of the directory it is unpacked into is refused rather
        // than written: the archive decides the path, and nothing else checks it.
        if (name.startsWith("/") || name.contains("../")) {
          throw new BadArchive("Zip Slip path traversal detected in backup archive: " + name);
        }
        byte[] content = zip.readAllBytes();
        totalUncompressed += content.length;
        if (totalUncompressed > MAX_DECOMPRESSED_BYTES) {
          throw new BadArchive(
              "Backup archive decompressed size exceeds the "
                  + (MAX_DECOMPRESSED_BYTES / (1024 * 1024))
                  + " MB limit");
        }
        int slash = name.indexOf('/');
        if (slash <= 0) {
          continue;
        }
        byDirectory
            .computeIfAbsent(name.substring(0, slash), key -> new LinkedHashMap<>())
            .put(name.substring(slash + 1), content);
      }
    } catch (BadArchive e) {
      throw e;
    } catch (Exception e) {
      throw new BadArchive("Invalid or corrupted zip file");
    }

    int restoredGroups = 0;
    int skippedGroups = 0;
    int restoredWatches = 0;
    int skippedWatches = 0;

    for (Map.Entry<String, Map<String, byte[]>> directory : byDirectory.entrySet()) {
      String uuid = directory.getKey();
      if (!UUID_DIRECTORY.matcher(uuid).matches()) {
        continue;
      }
      Map<String, byte[]> files = directory.getValue();

      if (includeGroups && files.containsKey("tag.json")) {
        if (store.tags().containsKey(uuid) && !replaceGroups) {
          skippedGroups++;
          continue;
        }
        Map<String, Object> tag = parse(files.get("tag.json"));
        if (tag == null) {
          continue;
        }
        tag.put("uuid", uuid);
        // Every tag carries the price shape, because a tag may override its watches' price
        // settings and there is nowhere else for those to live.
        tag.put("processor", "restock_diff");
        operations
            .store()
            .client()
            .forKeyValueEntity(SettingsEntity.ID)
            .method(SettingsEntity::updateTag)
            .invoke(new SettingsEntity.UpdateTag(uuid, tag));
        restoredGroups++;
      } else if (includeWatches && files.containsKey("watch.json")) {
        if (store.watch(uuid).exists() && !replaceWatches) {
          skippedWatches++;
          continue;
        }
        Map<String, Object> watch = parse(files.get("watch.json"));
        if (watch == null) {
          continue;
        }
        watch.put("uuid", uuid);
        operations.restoreWatch(uuid, watch, snapshotsOf(files));
        restoredWatches++;
      }
    }

    Map<String, Integer> counts = new LinkedHashMap<>();
    counts.put("restored_groups", restoredGroups);
    counts.put("skipped_groups", skippedGroups);
    counts.put("restored_watches", restoredWatches);
    counts.put("skipped_watches", skippedWatches);
    return counts;
  }

  /** The stored snapshots in one watch's directory, oldest first. */
  private static Map<Long, String> snapshotsOf(Map<String, byte[]> files) {
    Map<Long, String> out = new java.util.TreeMap<>();
    for (Map.Entry<String, byte[]> file : files.entrySet()) {
      String name = file.getKey();
      if (!name.endsWith(".txt") || name.startsWith("history")) {
        continue;
      }
      String stem = name.substring(0, name.length() - ".txt".length());
      try {
        out.put(Long.parseLong(stem), new String(file.getValue(), StandardCharsets.UTF_8));
      } catch (NumberFormatException e) {
        // A file that is not named after a moment is not a snapshot.
      }
    }
    return out;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> parse(byte[] content) {
    try {
      return MAPPER.readValue(content, Map.class);
    } catch (Exception e) {
      return null;
    }
  }

  private static int maxNumberOfBackups() {
    String configured = System.getenv("MAX_NUMBER_BACKUPS");
    if (configured != null && !configured.isBlank()) {
      try {
        return Integer.parseInt(configured.strip());
      } catch (NumberFormatException e) {
        // Falls through to the built-in ceiling.
      }
    }
    return 100;
  }

  private static long megabytes(String variable, long fallback) {
    String configured = System.getenv(variable);
    long value = fallback;
    if (configured != null && !configured.isBlank()) {
      try {
        value = Long.parseLong(configured.strip());
      } catch (NumberFormatException e) {
        value = fallback;
      }
    }
    return value * 1024 * 1024;
  }

  /** Where the archives that have been taken are kept, and what is known about each. */
  static final class Backups {

    record Entry(String filename, long sizeBytes, double createdAt) {}

    private Backups() {}

    static List<Entry> list(Store store) {
      List<Entry> out = new ArrayList<>();
      String index = store.sideStore("system", "backup-index");
      if (index == null || index.isEmpty()) {
        return out;
      }
      for (String line : index.split("\n")) {
        String[] parts = line.split(",");
        if (parts.length == 3) {
          try {
            out.add(new Entry(parts[0], Long.parseLong(parts[1]), Double.parseDouble(parts[2])));
          } catch (NumberFormatException e) {
            // A line that cannot be read is one archive missing from the list, not a failure.
          }
        }
      }
      out.sort(Comparator.comparingDouble(Entry::createdAt).reversed());
      return out;
    }

    static void keep(
        Store store, ComponentClient componentClient, String filename, byte[] archive) {
      store.saveSideStore(
          "system", "backup-" + filename, java.util.Base64.getEncoder().encodeToString(archive));
      List<Entry> existing = list(store);
      existing.add(0, new Entry(filename, archive.length, System.currentTimeMillis() / 1000.0));
      writeIndex(store, existing);
    }

    static byte[] read(Store store, String filename) {
      String stored = store.sideStore("system", "backup-" + filename);
      if (stored == null || stored.isEmpty()) {
        return null;
      }
      try {
        return java.util.Base64.getDecoder().decode(stored);
      } catch (IllegalArgumentException e) {
        return null;
      }
    }

    static void removeAll(Store store, ComponentClient componentClient) {
      for (Entry entry : list(store)) {
        store.deleteSideStore("system", "backup-" + entry.filename());
      }
      store.saveSideStore("system", "backup-index", "");
    }

    private static void writeIndex(Store store, List<Entry> entries) {
      StringBuilder index = new StringBuilder();
      for (Entry entry : entries) {
        index
            .append(entry.filename())
            .append(',')
            .append(entry.sizeBytes())
            .append(',')
            .append(entry.createdAt())
            .append('\n');
      }
      store.saveSideStore("system", "backup-index", index.toString());
    }
  }
}
