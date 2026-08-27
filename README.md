# changedetection-io-akka

Watches web pages on a schedule and tells you when one of them changes.

A port of [dgtlmoon/changedetection.io](https://github.com/dgtlmoon/changedetection.io)
onto **Akka**, built with **Akka Specify**.

---

## Where it came from

changedetection.io watches web pages for changes and tells you when they change. It was
ported to derive a specification format precise enough to regenerate a system on a
different stack — the port is the vehicle, the specification is the deliverable.

This is the whole system, not one part of it: every page, every route a program can call,
the feeds, the archives, the importers, the command line, and the rules that decide what
counts as a change.

The specifications the port was generated from are in
[TylerJewell/akka-specify-harness](https://github.com/TylerJewell/akka-specify-harness)
under `changedetection-io-port/`.

---

## dgtlmoon/changedetection.io → this port

📉 53,667 Python lines → **36,577 Java lines**<br>
📁 340 files → **189 files**<br>
⚡ 1,745.75 → **166.27** microseconds per check<br>
🔁 688.09 → **166.27** microseconds per check, both sides reading their stored marker from memory<br>
📝 197.19 → **68.80** microseconds turning a page into comparable text<br>
🎯 10,630 → **10,624** answers matching<br>
🌐 36 of 36 → **36 of 36** replies with the same status, driven as servers<br>
🖼️ 7 of 7 → **7 of 7** screens matching, zero differing regions<br>
🧪 69 → **135** tests<br>
🚀 1.67 → **8.56** seconds to answer its first page

Full method and the numbers that did *not* make this list:
[`bench/REPORT.md`](https://github.com/TylerJewell/akka-specify-harness/blob/main/changedetection-io-port/bench/REPORT.md).

---

## What it took to build

⏱️ **151.8 hours** from the first command to the published repository, **7.4** of them active<br>
💬 **2,484** exchanges with the model<br>
✍️ **2,307,634** tokens written by the model, **1,099,136,290** counting everything sent and re-sent<br>
🙋 **0** questions to a human<br>
🧪 **135** tests

The record of every question, and where the time went, is in
[`port-log/`](https://github.com/TylerJewell/akka-specify-harness/tree/main/port-log).

---

## What it does

From the specification:

- **A page is fetched again only once its own interval has passed, and never sooner than
  three seconds.** Each watched page keeps its own clock, so a page checked hourly and a
  page checked every minute do not wait on each other.
- **Lines you have marked as noise are removed before anything is compared, and kept in
  what is stored.** A page whose only moving part is a timestamp never reports a change,
  and you can still read the timestamp afterwards.
- **A page held back by a rule does not use up the comparison.** If you asked to hear only
  when the words "in stock" appear, the page can move a hundred times while they are
  absent, and the first time they appear you are told what changed since you last heard.
- **Text you have marked as forbidden holds the page back while it is there.** A page that
  says "sold out" reports nothing until it stops saying it.
- **A page can be told to report only text it has never shown before.** A shop that rotates
  between the same three banners stops being interesting after the third.
- **A day-and-time window can forbid checks outright, and stops at midnight.** A window set
  for Monday evening does not carry on into Tuesday morning.
- **A rule you wrote that names something the page cannot supply stops the check and says
  so.** A rule that quietly did nothing would look exactly like a rule that was satisfied.
- **The page you are looking at is told when something changes, and never asks.** Leave it
  open for an hour and it sends nothing; change something and it shows it in about a
  hundredth of a second.

---

## Design decisions

**Its own clock per page.** One list that every page waits its turn on means a slow page
holds up a fast one, and the list gets longer as pages are added. Each page here sets its
own alarm, so a thousand pages cost the same per page as one does.

**The original's own screens, rewired.** Rebuilding the screens would have meant nobody
could tell whether a difference was a mistake or a taste. These are the same files the
original ships, with only the part that fetches data changed — which is why seven screens
photographed side by side come out identical to the pixel.

**Told, not asked.** A page that asks the server every few seconds is doing nothing useful
most of the time and is still slow to notice. This one is told, so it is quiet while nothing
happens and shows a change about a hundredth of a second after it happens.

**A ceiling on how big one stored version may be.** Everything a page remembers is copied
between machines as one piece, and a piece too big stops being copied without saying so.
A version too large to copy is refused with the reason instead.

**Words kept, code rewritten.** Every label, message and setting name is the original's,
because a program written against one has to keep working against the other. Everything
underneath was written fresh, and checked by asking both systems ten thousand of the same
questions.

---

## Running it — the short path

You do not need Java, Maven, or the Akka CLI installed. Akka Specify installs them for you.

**1. Install Akka Specify** in Claude Code:

```
/plugin marketplace add akka/ai-marketplace
/plugin install akka@akka-ai-marketplace
```

Restart Claude Code when it asks.

**2. Give it this prompt:**

> Clone https://github.com/TylerJewell/changedetection-io-akka into a new directory and
> open it. Then run /akka:setup to install everything this project needs, and /akka:build
> to compile it, run the tests, and start it locally.

**3. Open** http://localhost:9114.

---

## Running it — the developer path

### Requirements

- Java 21 or newer
- Maven 3.9 or newer
- An Akka download token — run `akka code token` once

### Start the service

```bash
mvn compile exec:java
```

The service starts on **port 9114**. Open http://localhost:9114 and it is the interface
changedetection.io ships.

### Watch a page from the command line

The key a program needs is shown on the settings page, under the API tab.

```bash
KEY=... # from http://localhost:9114/settings

curl -X POST http://localhost:9114/api/v1/watch \
  -H "x-api-key: $KEY" -H 'Content-Type: application/json' \
  -d '{"url":"https://example.com/","ignore_text":["Last updated"]}'

curl -H "x-api-key: $KEY" http://localhost:9114/api/v1/watch
```

The description of everything a program can ask for is at
http://localhost:9114/api/v1/full-spec.

### Subscribe to the changes

The feeds need the token shown in the link on the watch list:

```bash
curl "http://localhost:9114/rss?token=..."
```

---

## Configuration

Every setting the original reads is read here, under the same name. The ones most often
changed:

| Variable | Default | Notes |
|---|---|---|
| `akka.javasdk.dev-mode.http-port` | `9114` | set in `application.conf`; the port the service answers on |
| `DATASTORE_PATH` | `./datastore` | where the file holding this installation's secret is kept |
| `MINIMUM_SECONDS_RECHECK_TIME` | `3` | no page is fetched again sooner than this, whatever its interval says |
| `FETCH_WORKERS` | from the settings page | how many pages may be fetched at once |
| `DISABLED_PROCESSORS` | `image_ssim_diff` | which kinds of watch are not offered |
| `PLAYWRIGHT_DRIVER_URL` | `ws://playwright-chrome:3000` | where to reach a browser, for pages that need one |
| `PAGE_WATCH_LIMIT` | none | refuse to add more than this many pages |
| `LLM_MODEL`, `LLM_API_KEY`, `LLM_API_BASE` | none | a model to summarise changes, if you want one |
| `ALLOW_FILE_URI`, `ALLOW_IANA_RESTRICTED_ADDRESSES` | off | let a watch point at a file, or inside your own network |

---

## Where it differs from dgtlmoon/changedetection.io

Everything not listed here behaves the same way on purpose, including the parts that look
like mistakes.

- **How the page is told about changes.** changedetection.io holds a two-way connection
  open and pushes over it. This port sends a one-way stream instead, which is what the
  rebuilding rules require. The names and shapes of what is sent are unchanged and the
  original's own script reads them. What differs is what a page misses while it is
  disconnected: this port sends the current counts as the first thing on every connection,
  so a page that was away catches up rather than waiting for the next change. Measured:
  disconnected for three seconds, stale while disconnected, correct three seconds after
  the connection came back, without reloading.
- **The list of pages is a moment behind the pages themselves.** changedetection.io reads
  its list out of memory and is never behind. Here the list is built from the records as
  they change, so a page created a moment ago appears in its own record at once and in the
  list shortly after. A program that creates a page and immediately lists them may not see
  it on the first try.
- **Short refusals are labelled as plain text.** changedetection.io labels them as a web
  page, because that is what its framework does with a bare sentence. Five of thirty-six
  replies differ in that label alone; the status and the words are the same.
- **A refusal about a colour is worded differently.** changedetection.io refuses a colour
  that is not one through its published description and answers a small object; this port
  refuses it by hand and answers the sentence. Same status, same reason.
- **Two spellings of one address.** changedetection.io treats `/rss` and `/rss/` as two
  addresses, one sending you to the other. Here they are one address and both work.
- **Where an archive is kept.** changedetection.io writes it next to its data and lists the
  directory. This port builds it when you ask and holds the five most recent in memory, so
  they are gone when the service restarts. The archive itself has the same shape, so one
  taken from either system restores into the other.
- **How big one stored version may be.** changedetection.io writes a file of any size. This
  port refuses a version larger than 900 kilobytes once compressed and says so, because
  everything it stores is copied between machines as one piece and a piece past that size
  stops being copied silently.
- **How the queue, the open pages and the live browsers are kept.** All four are held in the
  one running copy of the service rather than shared between copies. Running two copies at
  once would give each its own queue display and its own live-browser sessions; the decision
  to check a page is taken from that page's own record, which is shared, so both copies
  would reach the same decisions.
- **It never checks whether a newer version exists.** changedetection.io asks and shows a
  notice when there is one. This port asks nobody, so it never shows the notice.
- **Delivering a notification.** changedetection.io reaches about a hundred destinations
  through a library. This port delivers by email and by web request, and refuses an address
  it cannot deliver to, using the same rule the original uses to refuse an invalid one.
- **Two pages of markup out of 8,681 come out differently.** One is markup with a stray
  closing tag, which the two markup readers recover from differently. The other is a table
  inside a table, which the two address readers return in a different order. Neither system
  decides these; the libraries under them do.
- **Two word-level differences out of 1,574.** Which words inside a changed line are
  highlighted differs in two cases; which lines changed does not.
- **It starts more slowly.** 8.56 seconds to answer its first page, against 1.67.
- **Behaviour with many pages at once, or across a restart.** `not measured` on either
  side. Every figure here is one operation at a time in one copy of the service.
- **What a person would say about the difference view on screen.** `not checked`. The seven
  screens that were compared are listed in `bench/REPORT.md`; the difference view is not one
  of them, because it needs a page that has changed and neither system was allowed to fetch
  one.
- **Registering a key you carry in your hand.** Neither system has it, so there is nothing
  to compare.

---

## Licence

changedetection.io is Apache License 2.0, © the changedetection.io contributors and Web
Technologies s.r.o. This port ships that project's own screens, wording and published
interface description, and reimplements everything underneath; it is Apache License 2.0.
What was copied and what was not is set out in `ACKNOWLEDGEMENTS.md`.
