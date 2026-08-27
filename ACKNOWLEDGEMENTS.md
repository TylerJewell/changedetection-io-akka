# What in this repository is somebody else's

This is a rebuild of [dgtlmoon/changedetection.io](https://github.com/dgtlmoon/changedetection.io)
at commit `fce24780`, version 0.55.8, on Akka. It is a complete port: the whole system, not
one capability of it. So the honest answer to "was anything copied" is longer than usual,
and this file gives it by class rather than by string, with the count for each class taken
from `python toolkit/copied_strings.py changedetection-io`.

The original is licensed under the Apache License 2.0, and a copy of that licence is in
`LICENSE`. Nothing here is offered as original work.

---

## Files copied wholesale, unchanged

These are the original's own files, shipped as they are. RENDERING.md R3 requires it: the
interface a port ships is the one the source already has, changed only in where it gets its
data.

Verbatim-copy: `src/main/resources/changedetection/templates`
Verbatim-copy: `src/main/resources/changedetection/static`
Verbatim-copy: `src/main/resources/changedetection/translations`
Verbatim-copy: `src/main/resources/changedetection/browser`
Verbatim-copy: `src/main/resources/changedetection/api`

- **`templates/`** — every page the interface serves, 32 documents. Unchanged.
- **`static/`** — the stylesheets, scripts, icons and images the pages load, 653 files.
  Two changes, both in the data layer and both required by RENDERING.md R1 and R4:
  `js/realtime.js` has its transport swapped from a socket to a stream (three lines: the
  construction, one log line, and nothing else — the event names, the payloads and every
  handler are the original's), and `js/stream.js` is new, holding the subscription that
  replaces the socket library. `js/socket.io.min.js` is still present and no longer loaded.
- **`translations/`** — the interface's own wording in fifteen languages, contributed by
  the original's translators. Unchanged.
- **`browser/`** — the scripts the original runs inside a driven browser to find elements
  on a page and to read whether something is in stock. Unchanged, because what they measure
  is a fact about a page and a reimplementation would measure something else.
- **`api/`** — the original's published description of its programmatic interface, and the
  price processor's addition to it. Unchanged, and served at `/api/v1/full-spec` as the
  original serves it. Which fields a caller may set is read out of this file rather than
  restated in code.

## Wording reproduced inside code this port wrote

1,598 string literals of ten characters or more occur in both systems, spread across 123
Java files this port wrote. They are not code that was copied; they are the original's own
words, reproduced because a system that said something different would not be the same
system. Six classes, and every one of the 1,598 falls into one of them:

- **What the pages say.** The templates above are the original's, and they ask the server
  for values by name and print them. Every label, option, placeholder, help sentence and
  button word the server supplies has to be the original's word or the original's own page
  says something else. `forms/Forms.java` (275), `forms/Choices.java` (86) and
  `model/WatchDefaults.java` (64) are almost entirely this.
- **What a caller is told.** The refusals and confirmations the programmatic interface
  answers with — "No watch exists with the UUID of …", "Invalid or unsupported URL",
  "Unknown field(s): …". A script written against the original reads these, so changing
  them would break callers rather than improve anything. `web/ApiEndpoint.java` (90).
- **The names of things.** Every field of a watch, every setting, every route name, every
  event name, every query parameter. An archive taken from either system restores into the
  other, and a page written against one renders against the other, only because these are
  identical. `web/Routes.java` (79) is the whole of that file.
- **The default templates.** The notification title and body, the two feed layouts, the
  wording of the alert sent when a filter goes missing or a browser step fails. An operator
  who has not changed them gets the original's words.
  `model/AppSettings.java` (65), `application/Notifier.java` (34).
- **The command line and the environment.** Every flag's name and its help text, and every
  environment variable's name. `cli/Options.java` (34).
- **Markup and selectors.** The fragments the difference view marks changes with, the
  selectors the price reader looks for structured price information in, and the layout a
  feed's entries are shown in. `web/DiffEndpoint.java` (107),
  `processors/RssTools.java`, `llm/RestockFallback.java`.

The Java that produces them was written for this port. What was copied is the words.

## What was written for this port

Everything else. In particular the parts a reader might assume were taken:

- **The template engine** (`jinja/`) — a reimplementation of the subset of Jinja the
  original's templates use, written from the templates' own behaviour and compared against
  the original over 94 renderings.
- **The text layer** (`text/`, `text/inscriptis/`) — the markup-to-text renderer, both
  markup parsers, the tree writers and the sequence matcher, all reimplemented in Java and
  compared against the original over 8,681 answers.
- **The difference layer** (`diff/`) — compared over 1,574 answers.
- **The form layer** (`forms/`) — the field types, validation and rendering the templates
  expect, reimplemented so the original's templates render against it.
- **Everything under `application/`, `web/`, `processors/`, `conditions/`, `fetchers/`,
  `notification/` and `llm/`.**

## Dependencies

The libraries in `pom.xml` are each other people's work under their own licences: jsoup,
Saxon-HE, json-path, jackson-jq, diff-match-patch, commons-text, Jakarta Mail, and Jackson's
YAML reader. None is vendored; they are declared and fetched.

## How this was checked

By running `python toolkit/copied_strings.py changedetection-io`, which pulls every literal
of ten characters or more out of this rebuild, finds the ones that also occur in the clone,
and names the ones this file does not mention. Not from memory: the counts above are its
output, and the class each string falls into was read off its per-file grouping.
