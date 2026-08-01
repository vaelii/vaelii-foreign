# Security Policy

## Reporting a vulnerability

Please don't open public issues for security vulnerabilities.

Report privately through either channel:

- **GitHub Security Advisories**: use "Report a vulnerability" on this
  repository (preferred), or
- **Email**: support@vaelii.com with "SECURITY" in the subject line.

Include what you can: affected version or commit, reproduction steps, and
impact. Please practice coordinated disclosure — report privately first and
allow time for a fix to land before publishing details.

## Supported versions

Only the latest release (and `main`) receive security fixes.

## Scope

`vaelii-foreign` is a set of readers loaded on demand by the
[vaelii](https://github.com/vaelii/vaelii) engine; it has no network
surface of its own. A running deployment's exposed surface (the browser's
unauthenticated write routes, the headless daemon) belongs to core — see core's full
[security policy](https://github.com/vaelii/vaelii/blob/main/.github/SECURITY.md),
which also tracks third-party advisories for the shared dependency stack.

**This repo parses untrusted input, which is its whole security surface.** Every
reader here is pointed at a file somebody else produced — a CFASL unit dump, an
RDF graph, a WordNet database, an OBO ontology, an ATOMIC release — and a KB dump
names its own lengths, so a hostile or truncated file can ask for a 2 GB string,
declare a list longer than the file, or point a handle at an object that was never
read. Reports worth sending:

- A crafted source file that exhausts the heap, spins forever, or escapes a
  reader as something other than a clean parse failure. The unbounded shapes to
  aim at are CFASL's self-declared lengths, an `rdf:rest` cycle, a deeply nested
  Turtle blank-node list, and a WNDB record whose counts overrun its fields.
- A path in a dump directory or corpus that reads or writes outside it.
- Anything that lets source content reach `eval`, a reader macro, or the
  classpath. Every reader here is a lexer by construction and interns no code,
  and a corpus is read back with `clojure.edn`; a way around either is a bug.

A source file is only as trustworthy as wherever it came from, and this repo does
no authentication of one — a reader's job is to fail cleanly on bytes it cannot
account for, not to decide whether they were meant kindly.
