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

## [0.9.0] — 2026-08-17

**Nothing here reaches a reader, and the number moves anyway.** The engine and this
plugin release in lockstep — one version string across the pair, checked at the cut —
so an engine minor carries the plugin's number with it whether or not this repo earned
one. No reader map, no `load-dir!` signature and no `:format` line moves, and a corpus
converted by 0.8.0 opens unchanged. There is nothing to migrate.

**The engine pin follows the tree it is built against**: this artifact now depends on
engine 0.9.0, so a project naming only this one gets it. That engine release defaults
the truth-maintenance network to the dense representation — belief-identical to the old
one, ~3.8× less JTMS memory at scale — and carries five Breaking entries: the strength a
subsumption is reported at can rise from `:default` to `:monotonic`, the
algebraic-property `…Predicate` twin types collapse to the bare marks, `person` splits
from `human`, and `argPreserving` is renamed `transitiveInArg`. Read its changelog
before upgrading; none of it is this repo's to migrate, and all of it is underneath you.

## [0.8.0] — 2026-08-14

**Nothing here reaches a reader, and the number moves anyway.** The engine and this
plugin release in lockstep — one version string across the pair, checked at the cut —
so an engine minor carries the plugin's number with it whether or not this repo earned
one. No reader map, no `load-dir!` signature and no `:format` line moves, and a corpus
converted by 0.7.0 opens unchanged. There is nothing to migrate.

**The engine pin follows the tree it is built against**, which is where the release
actually lands for a caller: this artifact depends on the engine at the version it was
cut with, so a project naming only this one now gets engine 0.8.0. That engine release
is twenty Breaking entries wide — the definitional marks and the argument constraints
descend a `genl` edge, a KB whose derived state was never built refuses writes, and a
firing names the `genlCx` edges its placement was read over. Read its changelog before
upgrading; none of it is this repo's to migrate, and all of it is underneath you.

One thing to know if you drive the converter directly: `lein convert load` opens its
destination `{:recover? false}` and then asserts into it. That is still correct, and
narrowly so — an *empty* store carries no hazard, and the first write into one builds
the network as it goes. Pointed at a store that already holds records, the same command
now meets the engine's `:unrecovered-kb` refusal.

**The authorship gate is the repo's own** rather than a copy of the engine's. It cites
the sections this repo has, reads the roster it asked for, blocks what it says it
blocks, and stays quiet on every trigger but `pull_request`, where there is something
to read.

## [0.7.0] — 2026-08-12

**Breaking, and reader-facing for once: a context is spelled `Cx`-prefixed.** The engine's
naming invariant moved the role marker from the end of a context name to the front, so the
speller prepends where it appended — Cyc's `EnglishMt` reads back as `CxEnglish`, `BaseKB`
as `CxBaseKB`, and an RDF corpus roots at `CxRdf<Graph>`. *Migration:* a corpus converted
by an earlier version names its contexts the other way, and those are names the engine now
refuses on assert; re-convert the source rather than re-reading the corpus.

The context-transitivity predicate moved with it: every reader that laid down a
`genlContext` edge — Cyc's `genlMt`, an RDF graph's rooting, WordNet's hypernym layering —
now writes `genlCx`. A corpus carrying the old spelling holds edges under a predicate the
taxonomy no longer reads, which is the second reason to re-convert rather than re-read.

The corpus **format** is untouched — `:format` stays `:vaelii-rdf-corpus/v1` and an old
directory still opens — so this is the translation kind of change rather than the format
kind, and it is the whole of the difference between two corpora converted across it. Cyc's
own vocabulary is not involved: `EnglishMt`, `BaseKB` and `genlMt` are identifiers in
someone else's system and keep their spellings, which is what the reader translates *from*.

Two smaller consequences of a marker that leads rather than trails. The guard keeping a
foreign individual from reading as a context prepends to break the collision, appending
being unable to disturb what the front of a name says; and the digit-splicing a suffixed
name needed to stay well-formed is gone for the same reason.

## [0.6.0] — 2026-08-12

No reader-facing change, no corpus-format change, and nothing here a caller can observe.
The version moves in lockstep with the engine, whose 0.6.0 is a **minor** carrying three
Breaking entries — a status code, the ops a model may reach, and where the CLI writes its
refusals — none of which reaches a reader here: this artifact translates formats and calls
`assert`, and the three touch the daemon's wire, the LLM tool surface and the CLI. The
lockstep is the release's whole reason rather than a convention, a released engine tree
naming `com.vaelii/vaelii-foreign` at its own version in the `+with-foreign` profile.

What the range holds is the version bump and the CI tiering — the conformance job auto-runs
on the public repository and nowhere else, and every `uses:` is a SHA that Dependabot
watches.

## [0.5.1] — 2026-08-11

No reader-facing change, no corpus-format change, and nothing here a caller can observe.
The version moves in lockstep with the engine, whose 0.5.1 is a patch — and the lockstep
is this release's whole reason rather than a convention: a released engine tree names
`com.vaelii/vaelii-foreign` at its **own** version in the `+with-foreign` profile, so that
version has to exist for the profile to resolve. Publishing it beside the engine rather
than after it is what keeps `lein with-profile +with-foreign` working on the day the
engine ships.

What the range holds is the version bump itself and a test fixture renaming the canonical
dog, which reaches no reader, no corpus and no shipped term.

## [0.5.0] — 2026-08-07

No reader-facing change, and no corpus-format change. The version moves in lockstep
with the engine, whose 0.5.0 carries nine breaking changes; one of them reaches this
repo, and it lands on the scaffolding rather than on a reader. `open-kb` takes one
`:space` number where it took `:record-space` and `:index-space`, and refuses either
retired key **by name**, so `test-util`'s scratch KB was named in a spelling that no
longer opens — it names the single number 11 now, still clear of the 14 and 15 the
engine's own suite owns, so the two suites still run at once.

The other seven do not reach a reader, and each for a reason rather than by luck. A
reader opens a corpus with `{:backend :disk :dir … :recover? false}` and never named a
space, so the migration above is the scaffolding's alone. Nothing here makes a request
of a daemon, so the bearer token it now demands on a public bind is not this artifact's
concern; nothing here starts the browser, so `VAELII_WEB_PORT` is not either. The engine
no longer puts `org.slf4j/slf4j-nop` on a consumer's classpath, which reaches this repo
as a dependency and would matter if the conversion ran a server — it does not, and the
progress log goes through Trove, which is named here directly. `VAELII_HIER` renames a
switch only the engine's own suite sets. The last two touch names no file here holds:
`context-size` is `count-in-context`, and nothing in this repo calls either spelling —
a conversion reports the count it kept as it went; and `different` descends into
compound arguments now, where no reader asserts a comparison of any kind.

The ninth is the one worth checking rather than asserting, since this repo's whole job
is minting names: the engine now reads two more roles off a spelling, a **sense**
(`abrasive-grit`) and a **lexeme** (`lex/fool's_gold`), and refuses a lexeme applied to
arguments. `term/spell` mints neither and cannot. `words` splits a foreign name on the
punctuation vaelii's symbols may not carry — a dash and a colon match no branch of its
pattern — so every minted name comes back out of the four shapes `spell` names, and no
converted corpus changes by a character. A reader that wanted to mint a sense would have
to say so.

One thing worth knowing for a converted corpus even though it changes nothing in this
repo: the engine's `/kbs` now lists at most 200 discovered KBs per search-path
directory. A directory holding more converted corpora than that hides the rest from the
page, and naming them in the catalog file lists them regardless.

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

[0.7.0]: https://github.com/vaelii/vaelii-foreign/compare/v0.6.0...v0.7.0
[0.6.0]: https://github.com/vaelii/vaelii-foreign/compare/v0.5.1...v0.6.0
[0.5.1]: https://github.com/vaelii/vaelii-foreign/compare/v0.5.0...v0.5.1
[0.5.0]: https://github.com/vaelii/vaelii-foreign/compare/v0.4.0...v0.5.0
[0.4.0]: https://github.com/vaelii/vaelii-foreign/compare/v0.3.0...v0.4.0
[0.3.0]: https://github.com/vaelii/vaelii-foreign/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/vaelii/vaelii-foreign/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/vaelii/vaelii-foreign/releases/tag/v0.1.0
