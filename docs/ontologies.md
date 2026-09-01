# Importing an ontology

Four of this repo's five readers take a public ontology and write a vaelii corpus:
RDF/OWL, WordNet, OBO and ATOMIC. The fifth, OpenCyc, has its own account in
[opencyc.md](opencyc.md) — it is a larger job and a stranger format, and nothing
here supersedes it.

This document is about what the other four *are*, what survives the trip, and what
does not. The commands are in the [README](../README.md#the-converter); if you are
deciding whether a translation is honest enough to build on, this is the argument.

Nothing here ships an ontology. `scripts/fetch-suites.sh` caches the third-party test
material — the W3C RDF syntax suites, the OBO Foundry registry, three small real
ontologies — under a gitignored `.cache/`, once per checkout; the tests that use it skip
when it is absent, so an ordinary `lein test` needs no network. CI fetches one of those
items, the W3C suites, because they can be pinned to a revision and carry a contract; see
[Four syntaxes](#four-syntaxes-and-how-the-reader-picks) below.

`load` takes no format. Every converter here writes the same corpus, so reading one
back is format-independent — see [The corpus](#the-corpus) below.

## The four formats at a glance

| format | source | what it is | scale |
|---|---|---|---|
| `rdf` | `.nt` / `.nq` / `.ttl` / `.owl`, `.gz` fine | RDF, RDFS and OWL — Wikidata, YAGO, DBpedia, schema.org, BFO, OpenCyc's OWL export, DOLCE, and the OWL serialization of nearly everything else | unbounded; a Wikidata truthy dump is ~7 × 10⁹ triples |
| `wordnet` | a `dict/` directory | Princeton WordNet's own WNDB files; Open English WordNet ships the same layout | 120k synsets |
| `obo` | an `.obo` file | the OBO Foundry's stanza format — the Gene Ontology, ChEBI, Uberon, the Disease Ontology, ~200 more | GO is 50k terms |
| `atomic` | a directory of `.tsv` | ATOMIC-2020, if-then commonsense collected from people | 1.3M tuples |

Licensing is not incidental here and is covered in
[licenses/THIRD-PARTY.md](../licenses/THIRD-PARTY.md): a converted corpus carries its
source's terms with it, and for three of these four that means an attribution clause
travels with the output wherever it goes.

## The corpus

Every reader writes the same thing — a directory of plain vaelii sentence files, one
s-expression per line, partitioned by context:

```
meta.edn                 format, source, counts, context load order
names.edn                foreign term -> vaelii term, with its role
report.edn               what converted, what dropped, and why
NOTICE                   whose knowledge this is, and on what terms
kb/Topology.txt          the genlCx wiring of every context
kb/Cx<C>.txt             that context's :default-strength sentences
kb/Cx<C>.monotonic.txt   its :monotonic ones
```

**One format, five converters** is a deliberate constraint rather than tidiness.
Because a corpus says nothing about where it came from beyond `meta.edn`'s `:format`
line, and because `vaelii.foreign.corpus/load-dir!` never asks, vaelii's own catalog
recognizes any of these directories and opens it. It also means the layered load — term
definitions, hierarchy, the rest of the schema, type memberships, then everything else
— is written once and is the same for all of them. That order is a correctness claim,
not a schedule: `vaelii.foreign.corpus`'s docstring says why,
`cyc-test/the-membership-layer-is-what-keeps-it` shows a corpus losing a fact without
it, and [opencyc.md](opencyc.md#one-batch-one-settle) argues each layer in turn against
a KB big enough to price them.

### Two strengths, and which one a sentence gets

vaelii distinguishes a `:monotonic` premise from a `:default` one: the second can be
retracted by later evidence, the first cannot, and a contradiction with a monotonic
premise is a refusal rather than a revision. None of these four formats draws that
line itself, so each reader draws it, and the choice is part of the translation:

| reader | monotonic | default |
|---|---|---|
| `rdf` | RDFS/OWL axioms — `genl`, `argIsa`, `disjoint`, the rules | ordinary triples, labels, comments |
| `obo` | the hierarchy, the `Typedef` declarations, the chain rules | annotations, instance data |
| `wordnet` | `genl` from `@` and `&` | everything else, entailment rules included |
| `atomic` | *nothing* | all of it |

ATOMIC having no monotonic half is the point of importing it. Every tuple is what
people said usually follows, and there is no other strength that is honest about
that.

## `rdf` — the Horn fragment of OWL

### Four syntaxes, and how the reader picks

RDF has four serializations in circulation and this reads all of them, through two
lexers that hand the projection identical triples:

| syntax | lexer | who writes it |
|---|---|---|
| N-Triples, N-Quads | `turtle` | bulk dumps — Wikidata, DBpedia, YAGO |
| Turtle | `turtle` | hand-written vocabularies — schema.org, BFO, most Foundry `.owl` |
| RDF/XML | `rdfxml` | OWL tooling — OpenCyc's export, DOLCE, OWL 1's only serialization |

The syntax is decided by **looking at the file**, not by its extension. `.owl` names
both — the OBO Foundry publishes Turtle as `.owl`, OpenCyc and DOLCE publish RDF/XML as
`.owl` — so an extension table would be wrong about real files from real sources. The
first few hundred bytes are unambiguous: `<rdf:RDF ` is a start tag and `<http://a/s>`
is not, because a tag's prefix is followed by a name where an IRI's scheme is followed
by `//`.

Both lexers are checked against the **W3C RDF 1.1 syntax suites**, which are cached by
`scripts/fetch-suites.sh` rather than vendored. Every valid document in all four suites
reads correctly — that part is a contract, asserted at 100% — and refusing invalid input
is a ratchet, because the reader deliberately recovers from a bad statement rather than
losing a billion-triple dump to one:

| suite | reads what is valid | refuses what is invalid, at least |
|---|---|---|
| Turtle | 145/145 eval, 74/74 syntax | 73% |
| N-Triples | 41/41 | 48% |
| N-Quads | 53/53 | 44% |
| RDF/XML | 126/126 eval | 97% |

The right-hand column is the floor `conformance_test` holds the reader to, not the rate
a run reports: a run prints what it actually reached beside each floor, and the floor is
raised when a fix lands and never lowered to let a change pass.

Both columns are checked by the `conformance` job in `.github/workflows/test.yml`, which
fetches the suites at the revision pinned in `scripts/fetch-suites.sh` and fails when the
corpus did not land rather than skipping quietly. The pin is what lets a ratchet mean
anything: measured against a suite free to move, these numbers would be a claim about the
week the run happened.

`turtle/triples` takes `:strict? true` to turn recovery off, which is what the suites
run with and what a hand-written ontology probably wants.

### The projection

This reader has the widest reach and gives up the most, for one reason: **OWL is a
description logic and vaelii is a Horn rule engine.** The translation is a
*projection*, and the fragment it keeps is roughly OWL 2 RL:

| RDF / OWL | vaelii |
|---|---|
| `C rdfs:subClassOf D` | `(genl c d)` |
| `X rdf:type C` | `(c X)` |
| `P rdfs:subPropertyOf Q` | `(genl p q)` |
| `P rdfs:domain` / `rdfs:range C` | `(argIsa p 1 c)` / `(argIsa p 2 c)` |
| `C owl:disjointWith D`, `owl:AllDisjointClasses` | `(disjoint c d)`, pairwise |
| `P owl:inverseOf Q` | `(inverse p q)` |
| `P a owl:TransitiveProperty` (and siblings) | `(transitive p)`, `(symmetric p)`, `(reflexive p)`, `(asymmetric p)` |
| `C owl:equivalentClass D` | `(genl c d)` **and** `(genl d c)` |
| `P owl:propertyChainAxiom (Q R)` | `(implies (and (q ?x ?y) (r ?y ?z)) (p ?x ?z))` |
| `C ⊑ ∀P.D` | `(implies (and (c ?x) (p ?x ?y)) (d ?y))` |
| `C ⊑ ∃P.{v}` (`owl:hasValue`) | `(implies (c ?x) (p ?x V))` |
| `C ≡ ∃P.D` | `(implies (and (p ?x ?y) (d ?y)) (c ?x))` |
| `C ≡ D1 ⊓ D2` | both `genl` edges **and** the sufficient-condition rule |
| `O owl:imports O2` | `(genlCx CxO CxO2)` |
| anything else | `(p S O)`, a plain fact |

### What is dropped, and why that is the interesting half

- **`C ⊑ ∃P.D`** (`:existential-superclass`) — an existential in the *conclusion*.
  "Every hand has a finger" obliges the reasoner to invent a finger, which is what a
  description logic does and a rule engine does not. On a real ontology this is the
  single largest drop.
- **`owl:unionOf` in the ⊑ direction, `owl:oneOf`, `owl:complementOf`** — a real
  disjunction, a real closed class, a real negation.
- **Cardinality** (`owl:minCardinality`, `maxCardinality`, `qualifiedCardinality`) —
  counting constraints have no Horn form at all. `owl:FunctionalProperty` is the one
  exception and only behind `--functional`, because vaelii's `functional` *merges* two
  values through the equality closure rather than refusing the second, which is not
  the same claim.

Reading the two directions of `owl:equivalentClass` separately is what buys most of
what is kept. `C ≡ ∃P.D` is worthless as a definition and perfectly good as the
sufficient-condition half of one, and a reader that turned every equivalence into two
subsumptions would lose that.

### Blank nodes become n-ary facts, or names

RDF triples are binary, so a relation with more than two arguments has to be written
as a **node with one edge per argument** — the W3C n-ary pattern, Wikidata's
statements, schema.org's Role, and every `[ :city "Austin" ; :zip "78701" ]` anybody
ever wrote. vaelii predicates have no arity limit, so such a node becomes one fact of
that arity:

```turtle
ex:Bob ex:hasJob [ ex:employer ex:Acme ; ex:role ex:Engineer ; ex:since 2020 ] .
```
```clojure
(hasJob Bob Acme Engineer 2020)
(arity hasJob 4)
```

Which means a query asks the question directly — `(hasJob ?who ?emp Engineer ?yr)` —
instead of joining through a node that stands for nothing.

**Flattening is only sound when the shape is uniform**, and that is a whole pass of
its own. A predicate flattens only if every node it reaches carries the same
qualifiers, exactly once each, with nameable fillers, is reached from exactly one
place, and the predicate takes no plain object anywhere else in the graph. Miss any
of those and the arguments silently misalign:

```turtle
ex:Bob ex:award [ ex:awardName "Prize" ; ex:year 2001 ] .
ex:Ann ex:award [ ex:awardName "Medal" ] .        # no year
```

Flattened positionally, Ann's award would be `(award Ann "Medal")` — arity 3 against
Bob's 4, so no one rule matches both, and worse, a two-slot node missing its *first*
qualifier would put a year where a rule reads a name. vaelii is open-world about
arity until something declares it, so nothing would refuse the misaligned fact
either; it would simply never match. That is the failure mode worth being careful
about, because it is invisible.

So `ex:award` is **skolemized** instead — the node gets a name built from the subject
and predicate that reach it, and its triples are kept as facts about it:

```clojure
(award Bob BobAward) (awardName BobAward "Prize") (year BobAward 2001)
(award Ann AnnAward) (awardName AnnAward "Medal")
```

That loses nothing — it is what the n-ary pattern already means — and it is what a
node with an identity of its own wants regardless. Always skolemized:

| the node is | why it keeps a name |
|---|---|
| **typed** (`[ a :Employment ; … ]`) | it claims to be an instance of something, and flattening takes away the term the membership hangs on. Wikidata's statements are typed, so they land here |
| **shared** (reached from two places) | flattening would copy it rather than move it |
| **non-uniform** | the misalignment above |
| **nested** (a slot holding another node) | the slot has no name to hold |

The `(arity p n)` written beside the tuples is what turns a later wrong-arity fact
into a refusal rather than a sentence that quietly matches nothing. It is only safe
because of the uniformity rule — which is why a predicate that takes a plain object
anywhere backs off from flattening entirely rather than flattening what it can.

Only the interior of an OWL construct is left alone: the axiom that owns it has
already said what it means. `--no-n-ary` skolemizes everything, which is the reading
to take if a rule set was written against the joined form.

The argument order is the qualifiers' IRIs, sorted. It is arbitrary, which is exactly
why it has to be fixed, and `report.edn` records it per predicate:

```clojure
:n-ary {hasJob [employer role since] address [city zip]}
```

### Naming, contexts, languages

A term is named from its IRI's local part and its **role** decides the spelling, so
`ex:Mammal` used as a class becomes the type `mammal` and `ex:hasPart` used as a
property becomes `hasPart`. Two IRIs sharing a local part collide; collisions take a
numeric suffix in a sorted pass, so the same graph produces the same names every run
and two conversions can be diffed.

A **named graph** is a context — that is what N-Quads' fourth term is for — and so is
an `owl:Ontology`. `owl:imports` becomes the `genlCx` edge it always was, which
makes an ontology's import closure its context hierarchy.

A triple in no named graph lands in the file's own context: the `owl:Ontology` the file
declares, when it declares exactly one, and otherwise a context named from the file
name. `--context <Name>` names that fallback instead, which is what a dump that is one
fragment of something larger wants — its triples land in the context they belong to
rather than in one named after whoever split the file. The name is spelled the way
every context name in a corpus is, so `Zoo`, `zoo` and `CxZoo` all name
`CxZoo`.

`--languages en,fr` filters language-tagged literals; the default is `en` plus every
untagged literal. On a multilingual dump this is not a nicety: fifty translations of
one label are fifty facts saying one thing.

```sh
lein convert convert rdf go.owl.ttl corpora/go --languages en
lein convert convert rdf wikidata-truthy.nt.gz corpora/wd --limit 1000000
```

## `obo` — read the OBO, not the OWL

The OBO Foundry publishes both, and the OWL is *generated from* the OBO by a mapping
that turns every `relationship:` into an existential restriction — precisely the
construct with no Horn form. Reading the `.obo` directly keeps the relations vaelii
can hold.

### What comes across

| OBO | vaelii |
|---|---|
| `[Term]` | a **type**, named from its `name:` |
| `[Typedef]` | a **predicate**, named from its `id:` |
| `[Instance]` | an **individual** |
| `namespace:` | the **context** that stanza's sentences land in |
| `is_a: X` | `(genl this x)` |
| `relationship: R X` | `(r this x)` |
| `disjoint_from: X` | `(disjoint this x)` |
| `union_of: X` | `(genl x this)`, one per member |
| `intersection_of:` (all plain) | both `genl` edges **and** the sufficient-condition rule |
| `domain:` / `range:` | `(argIsa p 1 c)` / `(argIsa p 2 c)` |
| `inverse_of: Q` | `(inverse p q)` |
| `is_transitive:` and its siblings | `(transitive p)`, `(symmetric p)`, `(reflexive p)`, `(asymmetric p)` |
| `holds_over_chain: A B` | `(implies (and (a ?x ?y) (b ?y ?z)) (p ?x ?z))` |
| `transitive_over: R` | `(implies (and (p ?x ?y) (r ?y ?z)) (p ?x ?z))` |
| `instance_of: C` / `property_value: R v` | `(c I)` / `(r I v)`, on an `[Instance]` |
| `def:` / `comment:` | `(comment t "…")` |
| `name:` / `synonym:` / `xref:` | `(label t "…")`, `(synonym t "…")`, `(xref t "…")` |

A `[Term]` is named from its `name:` and a `[Typedef]` from its `id:`, and the
asymmetry is the format's: a term's id is an opaque accession (`GO:0000278`) where its
name is what every tool shows, while a typedef's id *is* its name (`part_of`).

**A relation a file uses but never declares is still named**, from its id. OBO names
relations across ontology boundaries — `part_of` is the Relations Ontology's, not the
Gene Ontology's — and the OWL product resolves them through `owl:imports` rather than by
restating them, so most Foundry files declare few of the relations they use and some
declare none. `uo.obo` has no `[Typedef]` at all and uses `has:prefix` eighty times
against a target sitting in the same file. Naming only declared relations would drop
every one of those facts; naming an undeclared one from its id keeps the fact and costs
a readable name, which is why PATO's corpus has a `rO0015012` in it.

**What `relationship:` is taken to mean.** `relationship: part_of GO:0051301` is read
as the class-level fact `(partOf mitotic_cell_cycle cell_division)`. That is what OBO
tooling means by it in practice and what every browser shows, but it is *not* what the
OWL mapping says — there it is `MitoticCellCycle ⊑ ∃part_of.CellDivision`, a claim
about every instance. The two agree about the ontology's shape and disagree about what
follows for an individual, and only the first has a form vaelii can store. Taking it
is a deliberate weakening.

**Obsolete terms are dropped** and counted, unless `--obsolete`. OBO never deletes: a
retired term keeps its id, gains `is_obsolete: true`, and loses its `is_a` edges, so
importing one gives a term with no place in the hierarchy and a name that collides
with its replacement's.

`is_functional` is imported as vaelii's `functional` only under `--functional`, for the
reason the `rdf` section gives: vaelii's `functional` merges a second value into the
first through the equality closure rather than refusing it.

### Contexts, and what a profile keeps

`namespace:` is the context a stanza's sentences land in — the Gene Ontology's three
namespaces are three contexts — and the header names the root they hang under: its
`ontology:` id, else its `default-namespace:`, else the file's own name. An ontology
whose live stanzas carry no `namespace:` writes one context, which is what `pato.obo`,
`ro.obo` and `uo.obo` each do.

`--profile ontology` drops the curation trail and the lexical layer — `label`,
`synonym`, `xref`, `altId`, `subset`, `consider`, `replacedBy`, `createdBy`,
`creationDate` — which is 6,302 of PATO's 11,052 sentences and none of its inference.
`--profile taxonomy` drops `comment` as well and loads only the term, hierarchy and
schema layers.

### What is dropped

| reason | kind | what it is |
|---|---|---|
| `:obsolete` | filtered | a retired term — **`--obsolete`** imports it after all |
| `:existential-intersect` | weakened | `intersection_of: R X` — the fact is written, the biconditional it came from is not |
| `:unknown-parent`, `:unknown-disjoint`, `:unknown-union-member`, `:unknown-intersect`, `:unknown-relationship`, `:unknown-chain`, `:unknown-instance-type`, `:unknown-property` | unread | the tag names an id nothing declares and no import resolved |
| `:malformed-relationship`, `:malformed-chain`, `:malformed-property-value` | unread | a tag value that is not the two words its tag takes |
| `:no-id` | unread | a stanza with no `id:` |

All three ontologies `scripts/fetch-suites.sh` caches convert with nothing unread:

| ontology | stanzas | sentences | dropped | `:unread` |
|---|---:|---:|---|---:|
| `pato.obo` | 2,820 | 11,052 | 919 obsolete | 0 |
| `ro.obo` | 758 | 3,939 | 17 obsolete | 0 |
| `uo.obo` | 574 | 2,241 | 80 existential-intersect, 1 obsolete | 0 |

`uo.obo`'s 80 are the `intersection_of: has:prefix UO:…` lines: each writes its
`(has_prefix …)` fact and loses only the "and nothing else" half of the definition.
Those rows are what `lein convert convert obo .cache/obo/<name>.obo <dir>` writes into
`report.edn`, so re-running it is the check. Any `:unknown-` reason above zero is what
to read before the corpus is loaded: each one is a fact the source stated and the corpus
does not have.

```sh
lein convert convert obo go-basic.obo corpora/go
lein convert load corpora/go /var/kb/go --profile taxonomy
```

## `wordnet` — a lexical database that contains a taxonomy

WordNet is organized around which words mean the same thing and is only incidentally
an ontology. What makes it worth importing is that its central relation is
subsumption: 82,000 noun synsets wired by `@` are a usable upper taxonomy nobody has
to build.

A synset becomes a type, `@` becomes `genl`, and `@i` — WordNet's own mark for an
*instance* rather than a kind — becomes a membership, which is exactly vaelii's
distinction between `(genl a b)` and `(b A)`. The `~` pointer and the holonyms are not
imported: they are the exact inverses of `@` and the meronyms, stored twice because
WordNet is a lookup structure, and importing both would double the corpus to say each
thing once.

**Entailment becomes a rule.** `snore *> sleep` says snoring entails sleeping, and a
verb synset is a type of event, so this reads as `(implies (snore_v ?e) (sleep_v ?e))`
over events — the one relation in WordNet with a genuine inferential reading, and the
reason importing it into a rule engine is worth doing. It lands **defeasible**,
because it generalizes about usage rather than defining anything, and it fires on
nothing WordNet itself contains: it is vocabulary for somebody else's events.
`--no-entailment-rules` writes plain `(entails a b)` facts instead.

**Every word of a synset is written**, as `(wordForm dog_n "dog")`, because a synset is
a set of words and those words are how anything reaches one from text. They carry no
inference of their own, so `--no-word-forms` leaves them out at conversion time and
`--profile ontology` drops them at load time, for a KB reached through the taxonomy
rather than through English.

**Sense numbers are not preserved.** A synset is named from its first word plus its
part of speech — `dog_n` — which collides constantly, because that is what a word
having several senses *is*. Collisions take a numeric suffix in offset order, so
`dog_n_2` is stable across runs but is **not** WordNet's sense 2; the index files that
would say are not read. `(wnOffset dog_n "n02084071")` is written for every synset and
is the identifier to join on.

Each part of speech is its own context under `CxWordNet`, so `--profile nouns`
loads the noun taxonomy alone.

```sh
lein convert convert wordnet WordNet-3.0/dict corpora/wn
lein convert load corpora/wn /var/kb/wn --profile taxonomy
```

## `atomic` — the corpus vaelii's strengths were built for

Cyc marks a handful of assertions `:default`; an OBO ontology and an OWL vocabulary
are definitional throughout. Every one of ATOMIC's tuples is a defeasible
generalization — *if PersonX abandons the cat, PersonX probably wants to find a new
home for it* — collected by asking people what usually follows. Nothing in it is a
definition, so nothing in it is written `:monotonic`.

The 23 relations fall into three families, and the families are the contexts:
`CxAtomicSocial` (what a person intends, needs, feels, wants), `CxAtomicEvent`
(how events sit next to each other), `CxAtomicPhysical` (what a thing is for,
where it is, what it is made of). A relation outside the table is still imported, into
the event context, so a release that adds one arrives rather than vanishing.

**Nodes are phrases and stay phrases.** An ATOMIC node is a fragment of English with
no definition anywhere, so each becomes an individual named from its own text, with
the text kept as `(nodeText PersonXAbandonsAltogether "PersonX abandons ___
altogether")`. Making `baseball bat` a type is tempting and is not done: ATOMIC never
marks which nodes are kinds, the same string appears on both sides of relations from
different families, and a type with no members and no supertype is a name with nothing
attached. Inventing a type system the corpus does not have would make the output look
more structured than the source and be wrong in ways nothing could check.

The `nodeText` facts are the only way back from a name to the phrase it was built from,
which is why they are written by default. They are also about a third of the sentences,
so `--no-node-text` drops them at conversion time and `--profile ontology` at load time,
for a KB that will only ever be reached through the graph.

Two rows with the same text are the **same** node, which is what makes the result a
graph rather than a list. A `none` tail is ATOMIC's own marker for "annotators said
nothing follows" and is dropped: it is a real annotation and it is not a fact.

```sh
lein convert convert atomic atomic2020_data/ corpora/atomic
lein convert load corpora/atomic /var/kb/atomic --profile social
```

## Reading the report

`report.edn` is part of the output, not a log. Every reader counts what it dropped and
why, because a conversion that silently lost a tenth of its source looks exactly like
one that lost nothing:

```clojure
{:triples 61 :sentences 22 :dropped 41
 :drops {:restated {:structural 21 :vocabulary-declaration 17}
         :filtered {:other-language 1}
         :unread   {:existential-superclass 1 :unsupported-restriction 1}}
 :unread 2
 :drop-reasons {:existential-superclass 1 :other-language 1 :structural 21
                :unsupported-restriction 1 :vocabulary-declaration 17}
 :blank-nodes 9
 :skolemized 0
 :n-ary {}
 :iris {:type 11 :predicate 7 :individual 2 :context 2}}
```

That is `test/resources/rdf/tiny.ttl` as it converts today, abridged: a run also writes
`:source`, `:contexts 3`, and a `:by-predicate` map of what each IRI wrote and dropped.
What is shown reproduces key for key, so the reason counts sum to `:dropped` and can be
checked. A graph that actually flattens fills the last two in —
`nary.ttl` reports `:skolemized 5` and `:n-ary {address [city zip] hasJob [employer role
since]}`, with the qualifier triples it folded away counted `:n-ary-member`, `:restated`,
because the tuple says what they said.

**`:dropped` is not the number to read; `:unread` is.** A reason name says what was
refused and not whether refusing it lost anything, and the four kinds are not degrees
of one thing:

| kind | what it means | is it a problem |
|---|---|---|
| `:restated` | said elsewhere in the corpus | no — there is nothing to fix |
| `:filtered` | deliberately not imported | no — a policy, and **a flag reverses it** |
| `:weakened` | read in part; the sentence *was* written | the claim is weaker than the source's |
| `:unread` | a claim with no reading here | **yes — this is the reader's own number** |

`:restated` is `X a owl:Class` when the term, its role and its spelling are already in
`names.edn`; `rdf:first` / `rdf:rest` and the interior of a restriction, read through
the axiom that owns them; WordNet's `~`, which is `@` stored backwards. A reason no
reader classifies counts as `:unread`, so a new drop is guilty until its author says
otherwise.

### A filtered drop is one you can overrule

`:filtered` means a reader decided against importing something it could read perfectly
well — which is a decision made on the converting party's behalf, so it owes them a
flag:

| flag | reader | what comes back |
|---|---|---|
| `--obsolete` | `obo` | terms the ontology has retired but not deleted |
| `--editorial` | `cyc` | `sharedNotes`, `myCreationPurpose` — the KB editors' notes to each other |
| `--code-rules` | `cyc` | rules Cyc states in full and then implements in SubL rather than chaining |
| `--empty-tails` | `atomic` | rows whose tail is the `none` annotation |
| `--languages en,fr` | `rdf` | language-tagged literals outside the default `en` |

Each reader's `drop-flags` carries either the option or the sentence saying why there
can be none — `cyc`'s two SubL drops are the case where none can, argued in
[opencyc.md](opencyc.md#what-is-dropped-and-why-it-is-counted) — and
`plugin-test/every-filtered-drop-is-either-reversible-or-explained` refuses a filtered
reason that has neither. The reverse direction is checked too: a flag no reader reads
would silently do nothing, which is worse than no flag.

Turning one on admits the content to the *translation*, which then judges it on its own
terms, and can still refuse it: some of what `--editorial --code-rules` admits to
OpenCyc turns out to be untranslatable for unrelated reasons and lands in `:unread`,
where it belongs.

The gap between the two figures is the whole reason for the split:

| corpus | source | `:dropped` | `:unread` |
|---|---|---|---|
| OpenCyc, CFASL dump | 1,889,842 assertions | 58,989 | **643** (0.03%) |
| OpenCyc, OWL export | 2,281,726 triples | 115,744 | **1,784** (0.08%) |
| `pato.obo` | 2,820 stanzas | 919 | **0** |
| `uo.obo` | 574 stanzas | 81 | **0** |

PATO retires 919 of its 2,820 stanzas, which read as a third-broken conversion until
the count said `:filtered`. `uo.obo`'s 81 were 1 obsolete term and 80 weakenings of
`intersection_of: has:prefix UO:…` — every one of them written, none of them lost.

The two `.obo` rows reconvert from the ontologies `scripts/fetch-suites.sh` caches. The
two OpenCyc rows are one run's, against a distribution this repo does not ship and
nothing here re-checks — [opencyc.md](opencyc.md) says what they measure.

What is left in `:unread` is worth reading one reason at a time. On OpenCyc's OWL it is
`:datatype-range` (1,266 — `rdfs:range P xsd:string`, a constraint on a value where
vaelii's `argIsa` names a type of term) and a few hundred anonymous class expressions.
On the CFASL dump it is `:unresolved` (407 — a reference the dump's own writer could
not resolve, which is the source's and not ours), and then disjunctions, integrity
constraints and non-ground facts: shapes vaelii has no form for rather than shapes this
reader failed to read. If `:existential-superclass` ever dominates, the ontology is
mostly existential and this is the wrong tool for it.

`load-dir!` reports the other half. A **refusal** is the engine declining a translated
sentence — the `:type` of the `ex-info` `assert` throws — and it is counted, not
swallowed, because which of vaelii's checks a foreign ontology trips is the most
useful thing an import has to say about it. `:on-refusal` is handed each one, which is
what turns the tally into a diagnosis: a count says a check fired, only the sentence
says whether the ontology disagrees with us or we are wrong.

## Scale

Both passes stream, so the memory a conversion needs is its **term table**, not its
source — plus, for `rdf`, the blank nodes, which are held because an OWL construct is
a small closed cluster of them and a dump large enough to matter has none at all.

For a load past a few million sentences, use a disk KB and consider `:bulk? true`,
which skips the per-fact definitional checks. It stores what a checked load would have
**refused**, so it is for a corpus a checked load has already reported on, not for a
first look.
