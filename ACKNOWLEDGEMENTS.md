# Acknowledgements

This project is a port of
**[dgtlmoon/changedetection.io](https://github.com/dgtlmoon/changedetection.io)**.

## What licence is it under, and who holds the copyright?

**Apache License 2.0**, read from the repository's own `LICENSE` file at commit `fce24780`
rather than assumed from a badge. The repository also carries `COMMERCIAL_LICENCE.md`, a
separate agreement from Web Technologies s.r.o. that applies to *hosting* — offering the
program's functionality to third parties as a service, or offering a service whose value
derives primarily from it. That agreement is not triggered by reading the code or by a
private rebuild; it would be triggered by running this port, or the original, as a service
for other people. Anyone who takes this further should read it.

## Was anything copied verbatim?

**No.** No file, prompt, fixture, schema or test corpus from the original appears in
`changedetection-io-akka`. Everything in that project was written for it. Specifically:

- The test bodies (`"Price: 10"`, `"In stock"`, `"Sold out"`) were invented for the probes
  and tests here; they are not the original's fixtures.
- `Diff.java` implements the longest-common-run decomposition that Python's `difflib`
  performs. That is a published algorithm, not the original's code, and no line of
  `difflib` or of `changedetectionio/diff/__init__.py` was transcribed.
- `TextPreparation.java` does not derive from `inscriptis` — the two disagree, and
  `bench/REPORT.md` §1 measures where.

The probes in `changedetection-io-port/probes/` **import** the original and run it. They do
not copy it, and they are not part of the published project.

## Is behaviour derived even where no text was copied?

**Yes, and that is the point of a port.** The rules in `specs/SPEC-001-changedetection-io.md`
§3 were read out of the original's source and confirmed by running it; the port implements
those rules deliberately. `docs/question-log.md` records which lines of the original each
rule came from and how it was checked. Where the original had no settled behaviour, the
port was given one, and §4 of the spec says so each time.

## What licence does this force on this project?

Because nothing was copied verbatim, Apache-2.0 is not inherited through copied text.
Because the behaviour is derived, `changedetection-io-akka` carries **Apache License 2.0**
anyway — the same licence as the work it derives from, with attribution to the original in
its `README.md` and this file. That is a decision to stay compatible rather than a
requirement someone imposed.

## Also used

- Akka Java SDK 3.6.3 (BSL 1.1 / Akka licence) — the runtime the port is built on.
