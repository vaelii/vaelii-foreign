# Changelog

Notable changes to `vaelii-foreign`, newest first. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow
[semantic versioning](https://semver.org/spec/v2.0.0.html).

**What counts as a breaking change here** is worth stating, because this artifact has
two surfaces and only one of them is code. A reader map's keys and a `load-dir!`
signature are the ordinary kind. The other is the **corpus format** — a directory some
earlier version wrote and a later one has to keep opening. A change that makes an
existing corpus unreadable is breaking whatever it does to the API, and the
`:format` line in `meta.edn` is what a reader checks to find out.

Translation changes are called out separately from both. A conversion that now keeps
something it used to drop does not break anything, but it does mean a corpus converted
before and after are not the same corpus, and anybody comparing two runs across such a
change wants to know which one moved.

## [0.4.0] — 2026-08-05

No functional change. The version moves in lockstep with the engine for the same
reason 0.3.0 did, and the engine's 0.4.0 carries thirteen breaking changes and eight
refusals, none of which reaches this artifact. Three of its calls touch a contract
that moved, and each is on the accepting side of it: the `{:backend :disk :dir …
:recover? false}` it opens a corpus with passes only keys the roster reads, `false`
being inside the domain `:recover?` is now checked against; the `(forward-chain kb
{})` that closes a load carries no key there is anything to refuse; and the sentences
the readers hand `assert` are lists, which is the shape its new check asks for. The
suite says as much against the engine being cut, rather than the pattern of the last
two releases saying it.

## [0.3.0] — 2026-08-04

No functional change. The version moves in lockstep with the engine for the same
reason 0.2.0 did, and the engine's 0.3.0 carries eight breaking changes none of
which reaches this artifact: its only contact with a changed contract is the
`{:recover? false}` it opens a corpus with, and `false` is still what that option
takes.

## [0.2.0] — 2026-08-03

No functional change. The version moves in lockstep with the engine because the
two coordinates cross-reference — `project.clj` here depends on
`com.vaelii/vaelii` and the engine's `:with-foreign` profile depends back — so a
half-bumped pair leaves one of them resolving a version that does not exist. The
engine's 0.2.0 is not a drop-in upgrade from its 0.1.0; this artifact is.

## [0.1.0] — 2026-08-01

First public release, on [Clojars](https://clojars.org/com.vaelii/vaelii-foreign).
Everything below is the initial contents rather than a change from anything.

### Added

**The readers.**

- **`cyc`** — an OpenCyc KB, from the distribution's own binary CFASL unit dump or from
  a CycL text re-dump. Reads the format from Cycorp's Apache-2.0 reference
  implementation rather than by reverse engineering
  ([licenses/THIRD-PARTY.md](licenses/THIRD-PARTY.md)).
- **`rdf`** — RDF, RDFS and OWL as N-Triples, N-Quads, Turtle or RDF/XML, through two
  lexers checked against the W3C RDF 1.1 syntax suites. Every valid document in all four
  suites reads.
- **`wordnet`** — a WordNet `dict/` directory, Princeton's WNDB files and Open English
  WordNet's.
- **`obo`** — an OBO-format ontology, read as OBO rather than through the generated OWL,
  which is what keeps the relations a Horn engine can hold.
- **`atomic`** — ATOMIC-2020, entirely at defeasible strength.

**The corpus.**

- One format, five converters, so a directory any of them wrote loads through any of
  their `load-dir!`s ([docs/ontologies.md](docs/ontologies.md), "The corpus").
- A **layered load**, which is a correctness claim rather than a schedule: what a
  sentence is checked against has to be in the KB before the sentence arrives.
- Every drop carries a **kind** as well as a reason (`:restated`, `:filtered`,
  `:weakened`, `:unread`), because summing four unlike things into one `:dropped` figure
  reports a conversion as broken when it is not.
- A **`:filtered` drop is reversible**: `--obsolete`, `--editorial`, `--code-rules`,
  `--empty-tails`, `--languages`, with a test walking the plugin manifest to refuse a
  filtered drop that has neither a flag nor a written justification.
- A `NOTICE` in every corpus directory, since a translation carries its source's terms
  and not this repo's.

### Known limits

- `forward-chain` has to run in the **same process** as the load; what is owed a
  derivation is held in memory, so chaining a KB reopened from disk derives nothing.
- The `rdf` reader is a projection onto roughly OWL 2 RL. Existentials in the
  conclusion, real disjunction, closed classes, negation and cardinality have no Horn
  form and are counted, not carried.
- WordNet sense numbers are not preserved; `wnOffset` is the identifier to join on.

[0.4.0]: https://github.com/vaelii/vaelii-foreign/compare/v0.3.0...v0.4.0
[0.3.0]: https://github.com/vaelii/vaelii-foreign/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/vaelii/vaelii-foreign/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/vaelii/vaelii-foreign/releases/tag/v0.1.0
