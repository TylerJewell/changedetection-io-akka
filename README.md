# changedetection-io-akka

Watches a web page on a schedule and decides whether what came back is a change worth
telling someone about.

A port of [dgtlmoon/changedetection.io](https://github.com/dgtlmoon/changedetection.io)
onto **Akka**, built with **Akka Specify**.

---

## Where it came from

changedetection.io watches web pages for changes and tells you when they change. It was
ported to derive a specification format precise enough to regenerate a system on a
different stack — the port is the vehicle, the specification is the deliverable.

The specifications the port was generated from are in
[TylerJewell/akka-specify-harness](https://github.com/TylerJewell/akka-specify-harness)
under `changedetection-io-port/`.

---

## dgtlmoon/changedetection.io → this port

📉 778 Python lines → **878 Java lines**<br>
📁 6 files → **17 files**<br>
⚡ 3,092 → **25.4** microseconds per check<br>
🧠 16,213 → **31.0** microseconds per check with the repeat-content rule on<br>
🎯 30 of 30 → **30 of 30** answers matching, over 13 sequences<br>
📄 5 of 7 → **5 of 7** pages turned into the same text<br>
🚀 not measured → **not measured** seconds, cold start

Full method and the numbers that did *not* make this list:
[`bench/REPORT.md`](https://github.com/TylerJewell/akka-specify-harness/blob/main/changedetection-io-port/bench/REPORT.md).

---

## What it took to build

⏱️ **1.2 hours** from the first command to the published repository, **1.2** of them active<br>
💬 **337** exchanges with the model<br>
✍️ **277,818** tokens written by the model, **66,731,267** counting everything sent and re-sent<br>
🙋 **0** questions to a human<br>
🧪 **69** tests

The record of every question, and where the time went, is in
[`port-log/`](https://github.com/TylerJewell/akka-specify-harness/tree/main/port-log).

---

## What it does

From the specification:

- **A page is fetched again only once its own interval has passed, and never sooner than
  three seconds.** Each watched page keeps its own clock, so a page checked hourly and a
  page checked every minute do not wait on each other.
- **Lines you have marked as noise are removed before anything is compared.** A page whose
  only moving part is a timestamp never reports a change.
- **A page held back by a rule does not use up the comparison.** If you asked to hear only
  when the words "in stock" appear, the page can move a hundred times while they are
  absent and the first time they appear you are still told what changed since the last time
  you heard.
- **Text you have marked as forbidden holds the page back while it is there.** A page that
  says "sold out" reports nothing until it stops saying it.
- **A page can be told to report only text it has never shown before.** A shop that rotates
  between the same three banners stops being interesting after the third.
- **The first time a page is seen is a change, and is not worth telling anyone about.**
  There is nothing to compare it against yet.
- **A day-and-time window can forbid checks outright, and stops at midnight.** A window set
  for Monday evening does not carry on into Tuesday morning.

---

## Design decisions

**Its own clock per page.** One list that every page waits its turn on means a slow page
holds up a fast one, and the list gets longer as pages are added. Each page here sets its
own alarm, so a thousand pages cost the same per page as one does.

**Remembering lines instead of re-reading them.** Deciding whether a page has shown
something new means comparing against everything it has ever shown, and reading all of that
back from disk every time gets slower the longer a page has been watched. This one keeps a
running list in memory, so the answer costs the same on the first day and the thousandth.

**A ceiling on what one page can remember.** A page that changes constantly would otherwise
grow its record without limit until it stopped fitting anywhere. It keeps the twenty most
recent versions or a quarter of a megabyte of them, whichever runs out first, and forgets
the oldest lines past five thousand.

**The rules kept apart from everything else.** Deciding whether something is a change has
nothing to do with fetching pages, storing them, or answering web requests, and mixing them
together means you cannot try one without the others. All the deciding lives in twelve
files that run on their own, which is why most of the tests need nothing started.

**One record per check, holding only what changed.** Writing down the whole of what a page
knows every time it is checked makes each check cost more than the last. Each check writes
down only the difference it made, and the whole is rebuilt by adding those up.

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

**3. Open** http://localhost:9024.

---

## Running it — the developer path

### Requirements

- Java 21 or newer
- Maven 3.9 or newer
- An Akka download token — run `akka code token` once

### Start the service

```bash
mvn compile
akka local run
```

The service starts on **port 9024**.

### Watch a page

```bash
curl -X POST http://localhost:9024/watches/my-watch \
  -H 'Content-Type: application/json' \
  -d '{"url":"https://example.com/","intervalSeconds":3600,"ignoreText":["Last updated"]}'

curl http://localhost:9024/watches/my-watch
curl http://localhost:9024/watches/my-watch/diff
```

To try the rules without waiting for a fetch, hand it a page directly:

```bash
curl -X POST http://localhost:9024/watches/my-watch/submit \
  -H 'Content-Type: application/json' \
  -d '{"body":"Price: 10\nLast updated: 1"}'
```

---

## Configuration

| Variable | Default | Notes |
|---|---|---|
| `akka.javasdk.dev-mode.http-port` | `9024` | set in `application.conf`; the port the service answers on |

Everything else is set per watched page, when it is created.

There is no model provider section: this port calls no language model.

---

## Where it differs from dgtlmoon/changedetection.io

Everything not listed here behaves the same way on purpose, including the parts that look
like mistakes.

- **Turning a page into text.** changedetection.io renders the page the way a text browser
  would, so a bulleted list comes out with `*` in front of each item and a table row comes
  out with spaces between the cells. This port strips the markup and keeps the line breaks,
  so the same list comes out without the `*` and the same row comes out with the cells run
  together. Five of seven test pages come out identically; the two that do not are lists
  and tables. It was chosen because rendering a page properly is a job of its own, and the
  rules this port is about work on lines either way — but a rule written to match `* one`
  will not match here.
- **Word-level highlighting inside a changed line.** changedetection.io can show which
  words inside a line changed. This port reports the whole line as changed, because which
  lines changed is what the rules act on and which words changed is only ever shown to a
  person.
- **The order of the lines left after the noise is removed.** changedetection.io rebuilds
  them from a collection that does not promise an order; run on every size tested it came
  out in the original order anyway. This port promises the original order in so many words,
  because the comparison it feeds is sensitive to order, and a day when that promise broke
  would look exactly like every page suddenly changing.
- **How much one page can remember.** changedetection.io keeps every version it is
  configured to keep and rebuilds what it has seen from all of them each time. This port
  keeps at most twenty versions or a quarter of a megabyte of them, and at most five
  thousand distinct lines, forgetting oldest first. It was chosen because everything a page
  knows is copied between machines as one piece, so a page that grew without limit would
  eventually stop being copyable — and a page that forgets a line it saw a year ago may
  report it as new, which the original would not.
- **Pages larger than half a million characters.** changedetection.io compares them.
  This port refuses them and says so in its log rather than comparing them, for the same
  reason as above.
- **Checks missed while the service was down.** Neither system has a settled answer here;
  in both, however many were missed, one check runs when the service comes back. This port
  additionally counts how many were missed and keeps the number, so the gap is visible
  instead of silent.
- **Choosing part of a page with a selector.** changedetection.io can narrow a page to a
  part of it before any rule runs, and can also remove parts. This port has no setting for
  either, so every rule sees the whole page. Not a difference in behaviour so much as a
  smaller set of choices; it is listed because a rule that worked there may match more here.
- **Who can create a watch.** changedetection.io can be put behind a password. This port's
  interface accepts anyone who can reach it, and creating a watch makes the service fetch a
  web address of the caller's choosing. It was chosen because the port's whole purpose is
  reachable through that interface and closing it would leave nothing to reach; the
  repository is private and the service is not deployed anywhere.
- **What is stored and how.** Both keep the text of past versions. Nothing compared what
  the two would show a person looking at the difference between two versions on screen —
  `not checked`.
- **Behaviour with many pages at once, or across a restart.** Not checked on either side.
  The two schedule differently — one clock per page here, one list for all pages there —
  and what that does under load is `not measured`.
- **Everything outside the slice.** Notifications, browser-driven fetching, the price and
  restock trackers, the web interface, the plugin system, and language-model summaries are
  not here at all.

---

## Licence

changedetection.io is Apache License 2.0, © the changedetection.io contributors and Web
Technologies s.r.o. This port reimplements the behaviour without copied source and is
Apache License 2.0; see `ACKNOWLEDGEMENTS.md`.
