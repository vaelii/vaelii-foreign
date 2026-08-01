# Third-party notices

`vaelii-foreign` (Apache-2.0) reads formats it does not own. Some of them carry
obligations that outlive the read — one on this repo's source, several on any
corpus it produces — and all of them are below. Vaelii core's own inventory lives
in the core repo's `licenses/THIRD-PARTY.md`; this file covers only what this
sibling adds.

Two kinds of obligation are mixed here and are worth keeping apart:

- **On this repo's source.** Only one: the CFASL reader is a derivative work of
  Cycorp's Apache-2.0 implementation.
- **On a corpus this repo produces.** All five converters. A translation is a
  *reformulation* of somebody else's knowledge, and every source below attaches
  terms that survive one — so the corpus, not the converter, is what carries
  them. `vaelii.foreign.corpus/write!` writes a `NOTICE` into every corpus
  directory saying so.

Informational inventory, not legal advice.

## Direct dependencies

| Artifact | Version | License |
|---|---|---|
| `org.clojure/clojure` | 1.12.5 | EPL-1.0 |
| `com.vaelii/vaelii` (core engine) | 0.1.0 | SSPL-1.0 |
| `com.taoensso/trove` | 1.2.0 | EPL-1.0 |

## The CFASL reader is a derivative work of Cycorp's own

`src/vaelii/foreign/cfasl.clj` and `src/vaelii/foreign/units.clj` implement
CFASL — Cyc's binary serialization format — and the KB dump layout of a
`units/<n>/` directory. Neither was reverse-engineered: Cycorp published the
reference implementation under the Apache License 2.0, and these files are a
Clojure port of it.

| Upstream | What it supplied |
|---|---|
| `com.cyc.cycjava.cycl.cfasl`, `.cfasl_kernel`, `.cfasl_kb_methods` | the opcode table, the integer/list/string encodings, and the handle indirection for constants, NARTs, assertions and clause-strucs |
| `com.cyc.cycjava.cycl.dumper`, `.assertions_low`, `.enumeration_types` | the dump-file record layout, the assertion flag bit-fields, and the direction / truth-value code tables |
| `org.opencyc.api.CfaslInputStream` | the float, bignum, vector, character and byte-vector encodings, which the SubL sources declare and leave undefined |

Copyright © 1995–2009 Cycorp Inc., licensed under the Apache License, Version
2.0. Substantial portions were developed by the Cyc project and by Cycorp Inc.,
whose contribution is gratefully acknowledged. The first two rows reach us
through the [LarKC](https://github.com/kraeutli/larkc) consortium's release
(© 2009 LarKC project consortium, Apache-2.0), which carries Cycorp's notice
per-file; the third through the OpenCyc Java API, which
`opencyc-4.0/LEGAL.txt` places under the same license as the Knowledge Base.

This repo is Apache-2.0, so that derivation needs no carve-out — the notice
above is the whole of Apache-2.0 §4(a)–(d) as it applies here.

**What is not a source.** OpenCyc also ships a *Knowledge Server* — a binary
build of the inference engine, KB index and browser — under Cycorp's
Free-of-Charge Software terms, which forbid reverse assembly, reverse
compilation and translation. Nothing here is derived from it, and it is not a
permissible reference for work on these files. The Apache-2.0 sources above are
complete for the formats this repo reads; use them.

## The `cyc-tiny` test fixture

| | |
|---|---|
| Files | `test/resources/cyc-tiny/` — `constant-shell.text`, `assertion.cfasl`, `clause-struc.cfasl`, `nart-hl-formula.cfasl`, and the `*-count.text` files |
| Licence | Apache-2.0 |
| Copyright | Cyc® Knowledge Base © 1995–2008 Cycorp, Inc., Austin, TX, USA |
| Upstream | LarKC, `src/main/resources/cyc-tiny/` |

A 717-constant, 8,899-assertion KB dump — the smallest real one that exercises
every reader path. Vendored so the suite needs no external checkout. It is KB
content, and the OpenCyc Knowledge Base is Apache-2.0.

## Test material, cached and not vendored

`scripts/fetch-suites.sh` downloads third-party corpora into a gitignored
`.cache/`. None of it is in this repo, none is redistributed, and the tests
that use it skip when it is absent — so a checkout that never runs the script
carries nothing below.

| Cached | Licence | What it is |
|---|---|---|
| `w3c/rdf-tests` (`rdf/rdf11`) | W3C Test Suite Licence + BSD-3-Clause | the RDF 1.1 syntax suites for Turtle, N-Triples, N-Quads and RDF/XML |
| OBO Foundry registry | CC-BY 4.0 | `ontologies.yml`: every Foundry ontology, its licence, its PURL. No test reads it — it is here so a person picking an ontology to convert can see what there is and on what terms |
| `uo.obo`, `ro.obo`, `pato.obo` | CC-BY 4.0 | three small real ontologies, for a conversion that is not a fixture |

## What each reader's output carries

A reader is code; a corpus is content. This table is about the content, and it is
the part most easily missed, because none of it attaches to anything in this tree.

| reader | source | terms on a converted corpus |
|---|---|---|
| `cyc` | OpenCyc Knowledge Base (the CFASL dump) | Apache-2.0 + Cycorp attribution — see below, and note the clause makes this explicit rather than arguable |
| `rdf` | OpenCyc **OWL export** | **CC-BY 3.0**, not Apache-2.0 — see below. The same knowledge, published twice under different terms |
| `rdf` | whatever graph you point it at | the source graph's own. Wikidata is CC0, DBpedia and YAGO CC-BY-SA, schema.org CC-BY-SA, BFO CC-BY. RDF is a syntax and not a publisher, so the converter cannot know and does not guess |
| `wordnet` | Princeton WordNet 3.0 | the WordNet License — permissive, BSD-style, and it requires the copyright notice to appear "in supporting documentation", which is a live obligation on anything shipping a corpus. Open English WordNet is CC-BY 4.0 |
| `obo` | an OBO Foundry ontology | in practice CC-BY 4.0 — Foundry principle 1 requires an open licence, and GO, ChEBI, Uberon and DOID all use it. Attribution to the source ontology travels with the corpus |
| `atomic` | ATOMIC-2020 (Allen Institute for AI) | CC-BY 4.0, attribution to AI2 |

**No reader's output is this repo's to license**, OpenCyc included. The converter
is Apache-2.0; what it converts is not.

**Formats, not content.** Nothing in this repo is derived from any of these
ontologies' *content*. The RDF, OBO and WNDB readers implement published format
specifications — W3C Recommendations for RDF/Turtle, the OBO Flat File Format
specification, and the `wndb(5)` manual page — which are specifications, not
copyrightable databases. ATOMIC's TSV layout is three tab-separated columns.

## A converted OpenCyc corpus stays Apache-2.0

This is the obligation most easily missed, because it attaches to output rather
than to source. `opencyc-4.0/LEGAL.txt` licenses the OpenCyc Knowledge Base
under Apache-2.0 and then extends it:

> The terms of this license equally apply to renamings and other logically
> equivalent reformulations of the Knowledge Base (or portions thereof) in any
> natural or formal language.

A vaelii corpus converted from OpenCyc is exactly such a reformulation. So the
clause cuts both ways, and both matter:

- **It permits the conversion outright.** Translating CycL into vaelii's
  sentences, renaming every term, and redistributing the result is licensed
  behaviour, not a grey area.
- **The result is Apache-2.0 content, not this repo's.** A converted corpus
  carries Cycorp's copyright and the Apache-2.0 terms with it, whatever license
  the converter is under. It cannot be relicensed, and shipping one means
  shipping that notice beside it.

`corpus/write!` therefore writes a `NOTICE` into every corpus directory any of
the five converters creates, naming the source and its terms. A corpus is data on
a filesystem rather than an artifact in this tree, and the notice is what keeps
it attributable once it has been copied somewhere else.

**This applies to OpenCyc only.** ResearchCyc and the full Cyc KB are licensed
separately and are not redistributable on these terms; no conversion of either
belongs in a corpus anyone ships.

### …but the OWL export does not

Cycorp publishes the same knowledge base a second way, as an OWL file, and puts
it under **different terms**. `opencyc-latest.owl`'s own header says so:

> This file contains an OWL representation of information contained in the
> OpenCyc Knowledge Base. The content of this OWL file is licensed under the
> Creative Commons Attribution 3.0 license.

So which OpenCyc artifact a corpus was converted *from* decides what governs
it: the CFASL dump under `server/cyc/run/units/` is Apache-2.0 with the
reformulation clause above, and the OWL export is CC-BY 3.0. Both are
permissive and both require attribution, so the practical difference is small
— but a corpus that names the wrong one names the wrong licence, and this repo
now reads both, so the two are easy to confuse. `corpus/write!` writes a
`NOTICE` naming the source path, which is what distinguishes them after the
fact.

`lein convert diff` compares two corpora, and comparing these two is what
surfaced this.

## Trademark

Cyc, OpenCyc and ResearchCyc are trademarks of Cycorp, Inc. WordNet is a
registered trademark of Princeton University. This project is not affiliated with
or endorsed by either of them, nor by the OBO Foundry, the Allen Institute for AI,
or the W3C. They are named here and in the source only to say which format is read
and where its specification came from — nominative use, and no part of this repo's
own naming.

## Copyleft assessment for Apache-2.0 distribution

No GPL/AGPL/LGPL dependency, and none is admissible: an AGPL reader could not
ship under this repo's license. That rules out
[clyc](https://github.com/white-flame/clyc) (AGPL-3.0) as a source for anything
here, its Cyc-derived files included — those carry White Flame's copyright over
the translation on top of Cycorp's Apache-2.0 notice, and it is the outer layer
that governs. Go to the Apache-2.0 upstream instead; it is listed above.

EPL (Clojure, trove) is weak file-level copyleft: the jars are
redistributed unmodified under their own EPL terms, which does not restrict
licensing this repo's own source under Apache-2.0 — the standard posture for the
Clojure ecosystem.

Vaelii core is **SSPL-1.0** and is a dependency, not a component: it is resolved
from a repository, never vendored or redistributed here, and this sibling's own
source is its own work. That is the same posture every permissive vaelii
sibling takes.
