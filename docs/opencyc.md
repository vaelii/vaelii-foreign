# Importing OpenCyc

OpenCyc 4.0 is the open release of the Cyc knowledge base: ~1.9M assertions over
~188k constants in ~620 microtheories. Vaelii can load it, and this is the pipeline
that gets it there.

**The counts on this page are measurements, not guarantees.** Every one of them comes
from a run against one release — OpenCyc 4.0's own `units/5022` unit dump — and nothing
in this repo re-checks any of them: the distribution is not shipped here, and the
fixture the tests do convert (`cyc-tiny`, 717 constants) produces none of these numbers.
Read them as what one conversion did, not as what the next one will.

The reader is a **bridge**, and lives where a bridge lives: in this artifact, reached
through vaelii's plugin seam ([foreign.md](https://github.com/vaelii/vaelii/blob/main/docs/foreign.md)). Converting the corpus once
into vaelii's own dump format is what retires it, and being a separate dependency is what
makes retiring it a matter of dropping one.

The two systems say the same kind of thing — assertions in contexts, a type
hierarchy, argument constraints, defeasible defaults — so most of the work is a
**renaming**, and the rest is a table of sentence rewrites. What makes it more than a
rename is that vaelii encodes a term's *role* in its symbol while Cyc states the role
as an assertion, so the roles have to be recovered from the KB before a single term
can be spelled.

    OpenCyc units (CFASL)                the distribution's own binary dump
      │  vaelii.foreign.cfasl            opcodes -> Clojure data
      │  vaelii.foreign.units            resolve the dump's cross-file ids
      ▼
    {:formula :mt :strength :direction}
      │  vaelii.foreign.cyc              classify roles, translate, write
      ▼
    corpus/                              vaelii sentence files, one per context
      │  vaelii.foreign.cyc/load-dir!
      ▼
    a KB

A CycL text re-dump — `(ke-assert '<formula> #$Mt :strength :dir)` a line at a time —
is the other accepted input, read by `vaelii.foreign.cycl` into the same maps.
`cyc/with-assertions` picks by looking at the path, so `convert!` takes either.

## 1. Reading the dump — `vaelii.foreign.cfasl` and `vaelii.foreign.units`

The distribution ships its KB as binary CFASL unit files under
`server/cyc/run/units/<n>/`, and those are read directly. It needs no Cyc image and no
external tool: **Cycorp published CFASL's reference implementation under Apache-2.0**,
so the format is specified rather than guessed at, and the reader is a port of it
([licenses/THIRD-PARTY.md](../licenses/THIRD-PARTY.md) records which upstream files, and
which other sources may not be used).

The whole KB — 1,889,842 assertions, a 109 MB `assertion.cfasl` against three smaller
tables — read in **5 seconds**, and every record yielded a formula.

### The opcode layer

A CFASL stream is self-describing: one opcode byte, then that opcode's payload,
recursively. Opcodes 0–127 are opcodes and 128–255 carry the fixnums 0–127 inline, which
is why the commonest object in a dump costs one byte.

Three things about the encoding are worth stating because getting any of them wrong
produces plausible data rather than an error:

* **The opcode table is the server's, not the API client's.** They disagree in exactly
  the range a dump uses: the client reads 36/37/38 as source / source-def / axiom where
  a dump writes deduction / kb-hl-support / clause-struc, and 50 is the client's
  special-object against the server's common-symbol.
* **A bignum's chunks are objects, not raw bytes.** Chunks are 8-bit and
  least-significant first, but each is written with `cfasl-output` — so a chunk of 128 or
  more arrives as an opcode *plus* a byte. Reading chunks raw consumes one byte where the
  writer wrote two. The symptom is not a wrong integer: it is an absurd float a few
  thousand records later, because a float's significand is a bignum.
* **Nothing frames an object but its own opcode.** So a mis-sized payload does not fail
  where the mistake is — it desynchronizes the file. An opcode this reader does not
  decode is therefore an error and never a skip.

### The dump layer

Each file holds a flat sequence of records — a copyright string, then `dump-id` followed
by that record's fields — and every reference between files is an integer id:

| file | what it holds |
|---|---|
| `constant-shell.text` | `"Name"` → dump-id, in plain text: the whole name table |
| `nart-hl-formula.cfasl` | nart id → its HL formula, whose head is a function |
| `clause-struc.cfasl` | clause-struc id → a CNF, which is how a rule is stored |
| `assertion.cfasl` | id, formula-data, mt, flags, arguments, plist |

The three small tables are read whole and the assertion file streams against them, so
peak memory is the tables and not the KB. The name table being text is not a shortcut:
the dump writes its constant names twice, once as CFASL and once as text, and the text
one is complete.

**One integer carries three fields.** An assertion's flags hold whether it is a ground
atomic formula (bit 0), its direction (bits 1–2) and its truth value (bits 3–5) — and the
truth value carries Cyc's monotonic-vs-default marking, which is vaelii's assumption
strength under another name.

**A gaf may be stored as a clause too.** The gaf bit says how to read `formula-data`, not
whether it is a reference: 83,464 of OpenCyc 4.0's assertions are a gaf holding a
clause-struc, and their formula is the clause's single positive literal rather than the
implication a rule's CNF becomes. Reading the bit without that leaves a bare reference
where a fact should be. (Only 14 assertions go the other way — a *rule* holding a
clause-struc reference — because a rule's CNF is normally stored inline.)

**A NART may name a NART defined later in the file.** Resolving during the read would
render that forward reference as an unresolved `(:nart 23227)` marker and then propagate
it into every assertion mentioning the term — 6% of the KB, and 122k formulas. So the
formulas are read with NART references left as markers and expanded when an assertion is
*emitted*, by which point every NART is in the table. Expansion is depth-bounded, which
is what a cyclic chain would otherwise do.

**A rule keeps the variable names it was written with.** The stored literals carry
`?var0`, `?var1`, … and the plist carries `("?RELN" "?COL")`, so
`(implies (argIsa ?RELN 1 ?COL) (arg1Isa ?RELN ?COL))` comes across reading the way its
author wrote it. That list is found by shape and not by its key — the key is an index
into a symbol table the server installs at runtime, and a dump on disk carries no such
table, so the index is all a reader has and it is not a name.

### What the counts are for

Each dump states its own record counts in `<name>-count.text`, and a read compares
against them: reading exactly that many records and then reaching end-of-stream cleanly
is the check that the traversal never lost step. A short read is an error, not a smaller
KB. On `units/5022` every table agreed to the record — 188,111 constants, 48,238 NARTs,
37,201 clause-strucs, 1,889,842 assertions.

The count is the only check that catches a desynchronized traversal, and it is a strong
one, because a stream out of step does not stop yielding records — it stops yielding the
right number of them. What it does not pin is the *predicate* each formula ends up under,
so those are worth stating too. Reading the whole dump and counting formula heads
(through a `not`, since a false gaf is wrapped):

| predicate | head count |
|---|---:|
| `isa` | 525,614 |
| `genls` | 202,822 |
| `comment` | 106,568 |
| `argIsa` | 54,660 |
| `genlMt` | 25,359 |
| `implies` | 812 |

**These are the formulas as the dump stores them, which is one step below EL.** Cyc's
own `assertion-formula` is what the reader reproduces — a gaf's literal, or a rule's CNF
rebuilt into an implication — and Cyc's EL layer sits above that and *uncanonicalizes*
further, which is where the last fraction of a percent moves between predicates. Add up
the per-predicate conversion figures below and `comment`, `argIsa` and `implies` come to
exactly these head counts, while `isa`, `genls` and `genlMt` miss them by under a tenth
of a percent in one direction or the other. Those figures were measured per predicate on
the translation's own output and carry that difference in their denominators.

## 2. Reading CycL — `vaelii.foreign.cycl`

A CycL text re-dump of a KB is the second accepted input, for a corpus that arrives
already extracted. Its syntax is Common Lisp, not EDN: `#$Constant` is not a reader tag,
a `comment` string spans lines, a float prints `1.0d0`. So it has its own reader, which
is a lexer and nothing more — it interprets no CycL.

Every token comes back as ordinary Clojure data, and a symbol's **namespace** is what
keeps the kinds apart:

| CycL | Clojure | |
|------|---------|-|
| `#$Dog` | `cyc/Dog` | a KB constant — the only kind translated |
| `?X` | `?X` | a variable, already vaelii's spelling |
| `foo-bar` | `subl/foo-bar` | a bare SubL symbol: executable code, never knowledge |
| `(:nart 7)` | `(:nart 7)` | a reference the dumper could not resolve |

`with-assertions` streams the file as a lazy seq of
`{:formula :mt :strength :direction}` maps, in constant memory.

## 3. Translation — `vaelii.foreign.cyc`

### Roles first

vaelii reads a term's role off its symbol — a type is `snake_case`, a predicate
`camelCase`, an individual `CapitalCamelCase`, a context ends in `Context` — while
Cyc writes nearly everything `CapitalCamelCase` and says the role in an assertion.
So **pass 1 classifies every constant** and pass 2 translates with that in hand.

The evidence is mostly **positional**, because collection-hood in Cyc is usually
*inherited*: a term is an instance of some metatype, not of `Collection` itself, so
the explicit declarations alone would classify a fraction of the KB. Argument 2 of
`isa` is a collection; both sides of `genls` are; argument 1 of `arity` is a
predicate; the microtheory slot is a context; the head of a *nested* application is a
function, while the head of a formula is a predicate.

That formula/term distinction is load-bearing. `(FruitFn AppleTree)` inside a
sentence is a term whose head is a function, and reading it as a predicate
application would spell it `fruitFn` and lose vaelii's NAT reification.

Conflicts resolve by `role-precedence`, in decreasing authority: a **microtheory** is
never anything else; a term used as a **formula head** is a predicate whatever else is
claimed of it, because vaelii refuses a functor that is not spelled as one — a wrong
role there costs every fact about the term, while a wrong role anywhere else costs
only the term's own appearance; then Cyc's own `(isa X <metatype>)` **declarations**;
then **position**; and finally an **individual by residue**, which is Cyc's own
default for a term that is neither a collection nor a relation.

### Spelling

`spell` renders a Cyc name under its role, splitting on case boundaries and on the
punctuation Cyc allows that vaelii's symbols do not:

| Cyc | role | vaelii |
|-----|------|--------|
| `DomesticatedAnimal` | type | `domesticated_animal` |
| `Agent-PartiallyTangible` | type | `agent_partially_tangible` |
| `USState` | type | `us_state` (an acronym is one word) |
| `prettyString-Canonical` | predicate | `prettyStringCanonical` |
| `Ohio-State` | individual | `OhioState` |
| `EnglishMt` | context | `EnglishContext` |
| `BaseKB` | context | `BaseKBContext` |

Names are assigned in sorted order and collisions take a numeric suffix, so the table
is a function of the dump and not of the order it was read. The whole mapping is
written out as `names.edn`.

A microtheory that is itself a non-atomic term — Cyc *computes* microtheories as well
as naming them, as in `(MtSpace (MtTimeDimFn Now))` — is minted a context symbol from
the constants it names, since vaelii names a context with a symbol. Long ones are
abbreviated with a hash of the whole, because a context becomes a file name.

**And so is a computed collection.** `(CityInCountryFn Canada)` denotes the collection
of Canadian cities; Cyc says both that Erickson belongs to it and where it sits in the
taxonomy. vaelii writes a membership as `(type Individual)` and a functor has to be a
name, so the NART is minted a **type** symbol the same way — `city_in_country_fn_canada`
— and the corpus writes its definition:

    (termOfUnit city_in_country_fn_canada (CityInCountryFn Canada))

That sentence is why the naming is worth doing rather than dropping the assertion. Left
to itself the engine mints its own constant for the expression the first time it meets
one structurally (`nat/g19374`): correct, unreadable, and different on every run — which
is exactly what a diff between two converted corpora cannot see through. With the
definition written first, `(genl (CityInCountryFn Canada) city)` and
`(city_in_country_fn_canada CityOfEricksonCanada)` are **one term**, so the membership
joins the taxonomy instead of hanging off an anonymous twin. It is written into the
corpus's `:terms` layer, which loads before everything else for that reason: a
`termOfUnit` arriving after a sentence that mentions its expression mints a second
constant for it, and the two have to be reconciled afterwards.

Naming is **depth one** — `(F a b)` over constants and literals. A nested
`(CollectionIntersection2Fn (GroupFn A) B)` is left structural, because `termOfUnit`
quotes its argument while every other position reifies, so the two spellings of one
expression would not meet. That is the whole of the remaining `:unnameable-type` count:
124 assertions of the 4,218 with a computed collection, the other 4,094 read.

`units/5022` names **13,966** of them, and the memberships are the smaller half of what
that buys: 916 of the names head a membership (4,080 sentences), while **13,118** appear
in a `genl` edge — 45,457 occurrences. Those edges were always converted; what they
named before was an anonymous constant the engine minted on first sight, with nothing
tying it to the term the same collection is called elsewhere.

### Sentences

| CycL | vaelii | |
|------|--------|-|
| `(isa Rover Dog)` | `(dog Rover)` | a type **is** the unary predicate |
| `(genls Dog Mammal)` | `(genl dog mammal)` | |
| `(genlPreds P Q)` | `(genl p q)` | vaelii's predicate hierarchy is the same closure |
| `(genlMt A B)` | `(genlContext AContext BContext)` | written to `Topology.txt` |
| `(disjointWith A B)` | `(disjoint a b)` | |
| `(arg1Isa P C)`, `(argIsa P 2 C)` | `(argIsa p 1 c)`, `(argIsa p 2 c)` | the `argN*` family folds into one positional form |
| `(comment X "…")` | `(comment x "…")` | |
| `(isa P TransitiveBinaryPredicate)` | `(transitive p)` **and** `(transitive_binary_predicate p)` | metadata as well as membership |
| `(isa F ReifiableFunction)` | `(reifiableFunction F)` | vaelii's NAT declaration |
| `(implies A C)` + `:forward` + `:default` | `(set/forwardRule (set/defaultRule (implies A' C')))` | direction and defeasibility are wrappers |
| `:monotonic` / `:default` | `{:strength :monotonic}` / the default | Cyc's own defeasibility marking is vaelii's assumption strength |
| anything else | the same fact under its renamed predicate | stored, indexed, queryable — uninterpreted |

A rule's antecedents go through the **same** literal rewrite as a standalone fact.
A rule that kept Cyc's `(isa ?x Dog)` in its body could never match the `(dog Rover)`
the same translation stores, so `rewrite-literal` is applied under the connectives
rather than only at the top.

### An assertion is a clause

Cyc holds an assertion as a CNF clause — positive literals and negative ones — and that
split is vaelii's own polarity, so `translate` reads a formula by its **clause shape**
rather than by a list of connectives that happen to be handled:

| clause | CycL | vaelii |
|--------|------|--------|
| one positive literal | `(P a b)` | a fact |
| one negative literal | `(not (P a b))` | a fact at `:false` — the record's own `:truth` |
| one positive, n negative | `(implies (and A B) C)`, `(or (not A) (not B) C)` | a rule |
| all negative | `(not (and A B))` | — an integrity constraint |
| several positive | `(or C1 C2)` | — a real disjunction |

The negated literal goes through the same predicate mapping a positive one does, so a
negated `isa` is still vaelii's unary form. A conjunction is n assertions — refused
only when a conjunct routes elsewhere (`genlMt`, to the topology), since that would
split one assertion across two contexts.

The last two rows have no vaelii reading, and they are dropped under their **own**
reasons because only one of them is a candidate for ever being supported.

**Why the EL formula and not the clause.** Cyc canonicalizes an assertion to CNF and
keeps the authored formula beside it, and we read the formula. `¬A ∨ ¬B` says nothing
about which literal the author wrote as the conclusion; `(implies A (not B))` does. A
rule direction that survives the trip is worth more than a normal form — and vaelii
stores a rule concluding a negative literal, so nothing is gained by flattening.

`(functional P)` is deliberately **not** imported by default. Cyc's
`FunctionalPredicate` constrains an argument position; vaelii's `functional` *merges*
a second value into the first through the equality closure. Importing all 4,332 of them
(3,321 `StrictlyFunctionalSlot`, 571 `FunctionalSlot`, 440 `FunctionalPredicate`) would
rewrite terms wholesale on the strength of a mapping that is not quite the same claim. `convert!` takes `:functional? true` for anyone who wants it anyway.

### What is dropped, and why it is counted

Every drop carries a reason and a **kind**, and the counts are written to `report.edn`
— [ontologies.md](ontologies.md#reading-the-report) argues why the kind is the half that
decides whether anything was lost. On `units/5022` the dump's 1,889,842 assertions
produced 58,989 drops and **643** losses:

| reason | kind | count | |
|--------|------|-------|-|
| `:nart-definition` | restated | 48,244 | Cyc's own `termOfUnit`, which this reader writes itself from the name table |
| `:trigger-code` | filtered | 4,657 | `afterAdding` / `afterRemoving` hold SubL the engine runs, not a claim it holds |
| `:subl-code` | filtered | 3,986 | a bare Lisp symbol anywhere in the formula |
| `:context-declaration` | restated | 1,341 | `(isa X Microtheory)` — carried by the topology instead |
| `:code-rule` | filtered | 80 | Cyc states the implication in full and implements it in SubL — **`--code-rules`** |
| `:editorial` | filtered | 38 | `sharedNotes`, `myCreationPurpose` — the KB editors writing to each other — **`--editorial`** |
| `:unresolved` | **unread** | 407 | a `(:nart n)` the dumper could not resolve — the source's, not ours |
| `:unnameable-type` | **unread** | 124 | `(isa X (F (G a) b))`: a computed collection too nested to name |
| `:all-negative-clause` | **unread** | 65 | no positive literal — an integrity constraint, which vaelii has no form for |
| `:untranslatable-rule` | **unread** | 25 | a rule with a literal that has no reading |
| `:non-ground` | **unread** | 20 | vaelii refuses a non-ground fact — it would match every goal of its shape |
| `:disjunction` | **unread** | 2 | several positive literals — a claim vaelii cannot state |

`:unresolved-mt` (a microtheory with no name in the table),
`:mixed-context-conjunction` (a conjunct belonging in another context, so the assertion
cannot land whole) and `:unsupported-connective` (a quantifier) are reasons this reader
can report and `units/5022` does not trip.

Two figures moved when the computed collections were named: `:non-constant-type`
(4,204) became `:unnameable-type` (124), and the three shared `:excluded` counts became
the three rows above, which is what made it visible that 48,244 of them were a
restatement and not a refusal.

**Two of the four filtered drops have a flag and two do not**, and the split is the
point. A `:code` rule and an editorial note are policies — Cyc wrote the implication out
in full before saying it runs SubL instead, and `sharedNotes` is prose somebody may want
for provenance work — so `--code-rules` and `--editorial` hand the decision back.
`:trigger-code` and `:subl-code` have none, because SubL is executable code and vaelii
has no form for code: a flag would produce a fact nobody could read. That is written
into `cyc/drop-flags` as a sentence rather than left to the absence of an option, and
`plugin-test` refuses a filtered reason carrying neither.

Running with both flags moved 118 assertions into the translation and 115 sentences out
of it; the other three are rules that turn out to be untranslatable for unrelated
reasons, so they land in `:unread` (643 → 646) where they belong.

## 4. The corpus

The layout is every reader's ([ontologies.md](ontologies.md#the-corpus)): `meta.edn`,
`names.edn` — here a Cyc constant to a vaelii term, with its role — `report.edn`, the
`genlContext` wiring of every microtheory in `kb/Topology.txt`, and one file per context
beside it, `.monotonic` or not.

The files hold plain vaelii sentences, one s-expression per line — the same format
`vaelii.impl.seed` reads — but they are read off the **filesystem**, not the
classpath: a converted corpus is not shipped schema, and at this size it belongs
beside a KB directory rather than inside the jar. The two strengths are separate
files so that each stays a plain sentence list, with the loader supplying the
`{:strength :monotonic}` the file name announces.

Partitioning by context is what makes a subset selectable: the natural-language layer
is nearly all of the volume and none of the inference, and it is already a context of
its own.

Converting from a shell, and loading from Clojure:

```sh
lein convert convert cyc <opencyc>/server/cyc/run/units/5022/ corpora/cyc
```
```clojure
(cyc/convert! "opencyc-4.0.sexpr" "/kb-data/opencyc-4.0")

(def kb (v/open-kb {:backend :disk :dir "/kb/opencyc"}))
(core-context/load-into kb)                                   ; the vocabulary head
(cyc/load-dir! kb "/kb-data/opencyc-4.0" {:profile :ontology})
```

`Topology.txt` loads first — a context's supercontexts must exist before its own
sentences are checked against them — and Cyc's root microtheory is wired under
vaelii's `CoreContext`, so the whole imported spindle sees the engine vocabulary.
Then each context file in the meta's topological order.

`:profile` names a subset: `:full` loads everything, `:ontology` drops the
natural-language and bookkeeping layers, `:core` keeps only Cyc's upper vocabulary.
`:keep-contexts` / `:drop-contexts` / `:drop-predicates` do the same thing directly.
`:chain?` is passed to `assert` and defaults to **false**: a bulk load derives nothing
useful fact-by-fact, so chaining once at the end with `forward-chain` reaches the same
fixpoint for a fraction of the work — in the **same process** as the load, since what is
owed a derivation is held in memory rather than in the store. Reopen the KB first and
`forward-chain` returns `{:derived 0}` without doing anything.

### One batch, one settle

The whole load runs inside a single `with-deferred-settle`. Under it an assert stores
and chains but does not reconcile belief, and the taxonomy repairs only the new edge's
own end of its **depth potential** instead of pushing the repair down to that edge's
descendants. That potential is what prunes the `genl` reachability walk (`edge x→y ⇒
depth[x] > depth[y]`); repairing it in full for one new edge costs that edge's whole
descendant set — proportional to the graph, not to the change, and worst when the
edges arrive child-first.

The local repair is what makes the deferral a win rather than a trade. Abandoning the
potential outright for the length of the batch would be simpler, but an unpruned
`reachable?` walk is dramatically more expensive than a pruned one and `wff` runs one
per taxonomy edge asserted — so that version is quadratic whenever the edges arrive
*parent*-first, which is the order a hierarchy is normally written in. Repairing
locally keeps the potential sound for exactly those orders, and falls back to one
reverse-topological pass at the settle only when an edge genuinely breaks it. The
closing settle pays both that and belief once, and belief is computed from current
state, so the KB is identical to one loaded assert by assert.

Cancelling a load throws out of that batch, so its closing settle never runs. The
depth potential is repaired on the way out regardless
([taxonomy.md](https://github.com/vaelii/vaelii/blob/main/docs/taxonomy.md)): a
cancelled load leaves an entry that stays queryable, and a KB whose potential is
unrepaired answers every `genl?` and `sees?` the slow way from then on.

The load is layered — **term definitions, hierarchy, the rest of the schema, type
memberships, then the remaining facts** — the same layering the shipped ontology uses,
and each step is read by the one after it.

Term definitions are an **identity** argument. `(reifiableFunction F)` and `(termOfUnit
K E)` decide which constant a non-atomic term reifies to, and Cyc's KB is full of them:
a sentence mentioning `(F a)` structurally is reified against those declarations as they
stand at that moment. Arriving late, a `termOfUnit` mints a second constant for an
expression that already has one, and a `reifiableFunction` misses the reification
altogether — the NAT was stored as a compound and no later declaration goes back for it.
13,966 of the corpus's type names are computed collections defined this way, and the
phase took 11s.

The next two are a **cost** argument. A `genl` edge retires the memo of the cached
transitive closure, and everything else reads that closure back: an `argIsa` constraint
checks its type argument, a fact checks its arguments against the constraints.
Interleaved, each of those pays for a closure the next edge is about to retire, over a
100k-type taxonomy.

Memberships are a **correctness** argument, and a stronger one. `argIsa` is open-world
about an argument with no type at all and closed about one that has any: an argument the
KB knows *a* type for, but not the required one, is a violation. So a relational fact
checked before its argument's other memberships have arrived is refused on a partial
answer, nothing ever revisits it, and the *same corpus in a different order keeps a
different set of facts*. Loading every membership first is what makes "what types does
this term have" complete before anything asks. (Within the layer the order still shows
in which side of a genuine disjointness clash is refused — but that is two claims that
cannot both hold, not an artefact of when a file was read.) `argGenl` moved into the
schema layer for the same reason `argIsa` is there: it is a constraint the fact checks
read, not a fact.

Each layer is a filter over the same file list — no gathering, no sorting.

What remains is the per-fact `(argIsa pred ?n ?type)` constraint query, which the
engine names as the dominant per-fact cost and which `:bulk? true` skips (along with
the other definitional checks and the dedup probe). That mode stores what a checked
load would have refused, so it belongs after a checked load has reported, not
before one.

A corpus this broad is what tells you whether an assert costs the *answer* or the
*KB*, because here the two differ by five orders of magnitude. Four places on the
assert path are sized by the answer, and each is invisible until a KB is this wide:

* `kb/isa?` asks `(t x)` once. Matching fans a functor out over its spec closure
  already, so one probe covers every subtype — and `specs(thing)` is every type
  OpenCyc has.
* `res/matches-hierarchical` decides whether a mirrored probe is needed by
  intersecting the declared symmetric predicates — a handful — with the closure,
  never by walking the closure.
* `nat/merge-colliding-nats!` looks only at the class the equality merged. The
  collisions a merge can create all name its representative, which the inverted term
  index answers in the size of the class.
* `res/candidate-handles` routes a **compound argument sitting after a variable**
  — `(termOfUnit ?k (FruitFn AppleTree))`, the NAT dedup probe — to the argument
  roots, which key the compound whole. The trie reaches it only by fanning out over
  the intervening variable, i.e. over every NAT minted so far. A ground argument the
  trie cannot prefix is the roots' case, compound or not.

The `:ontology` profile loaded in **650s** — 1,163,120 sentences into the contexts that
profile keeps, over a 125k-type taxonomy — of which the topology was instant, the term
definitions 11s, the type hierarchy 82s, the rest of the schema 53s, the memberships
159s, and the facts 345s.

Chaining afterwards derived **146,241** sentences and came back `:truncated? true`, so
that is a budget and not a fixpoint. 16,728 completed firings were dropped rather than
stored, 11,713 of them for `:no-placement` — no context sees the rule, all of the
antecedent facts, *and* the genl edges the match subsumed through, so there is nowhere
the conclusion could be asserted that its own premises are all visible from. That is a
property of a KB whose rules and facts were written in different microtheories, not a
failure of the load. (Three senses of "context" are in play on this page and they are
different things: the context terms the vocabulary **names**, the far smaller set that
actually **holds** sentences, and whatever a profile keeps of either. The first two
totals are below.)

## What comes across

OpenCyc 4.0 (`units/5022`, 1,889,842 assertions over 188,111 constants) converted to
**1,848,561 sentences in 5,423 contexts** (contexts that hold at least one sentence —
the vocabulary names 13,302, counted below). Sentences do not divide by assertions: one
`isa` assertion becomes two sentences where the predicate is metadata as well as a
membership. Counted the way the drops are, in assertions, **96.9%** convert.

The 58,989 drops are dominated by the two the mapping makes on purpose: 48,244
`termOfUnit` restatements and 4,657 SubL hooks, neither of which is a loss. **643
assertions are lost** — 0.034% — and 407 of those are references the dump's own writer
could not resolve. The rest is 124 computed collections too nested to name, 65
integrity constraints, 25 rules with an unreadable literal, 20 non-ground formulas and
2 disjunctions: shapes vaelii has no form for, one reason at a time. The table in §3
is the whole list.

The 214,219 named terms come out as 113,712 types (99,746 of Cyc's own plus 13,966
computed collections this reader names), 63,741 individuals, 19,600 predicates, 13,302
context **terms** (622 of them Cyc's own, the rest computed microtheories — most name a
context nothing is stated in, which is why only 5,423 hold sentences) and 3,864
functions.

Per predicate, almost everything survives — `report.edn` counts the sentences written
and the assertions dropped for each: `isa` 524,074 written / 1,938 dropped (of which
1,341 are microtheory declarations the topology carries instead), `genls` 202,546 / 174,
`comment` 106,521 / 47, `argIsa` 54,641 / 19, `genlMt` 25,342 / 15. The exceptions are
`implies` — 526 written and 286 dropped, since a Cyc rule may be SubL-implemented, use
`or`, or quantify in a way vaelii's rules do not — and `genlPreds` at 10,136 / 395.

## What the engine refuses, which is not what the translation drops

Two different losses sit on this pipeline and they are worth keeping apart. The
translation **drops** a formula it has no vaelii reading for, and says so in the
corpus's `report.edn`. Then the load **refuses** a translated sentence that the engine's
own definitional checks decline, counted by the `ex-info` `:type` in `load-dir!`'s
return. `:on-refusal` hands each one over as `{:sentence :context :reason :phase :ex}`,
which is what turns the tally into a diagnosis
([ontologies.md](ontologies.md#reading-the-report)).

On the `:ontology` profile's 1,163,120 sentences, the tally read:

| reason | refused | |
|---|---:|---|
| `:arg-type` | 6,578 | a fact against a membership the schema layer already loaded |
| `:not-well-formed` | 1,191 | an argument constraint about a function |
| `:arg-genl` | 156 | |
| `:disjoint` | 181 | the one structural mismatch — below |
| `:naf-not-closed` | 28 | |
| `:not-range-restricted` | 8 | |
| `:not-stratified` | 7 | |
| `:not-ground` · `:functional` | 1 each | |
| `:arg-constraint-clash` · `:arg-position` | 0 | |
| **refused** | **8,151** | of 1,163,120 — 0.7% |

`:disjoint` is 181, where the same load against the old global closure recorded a
five-figure count. The scoped `disjoint?` below is what replaced that closure, and the
section explaining it is the reason this line moved. `:arg-type` moved the other way and
this run does not say why — `:on-refusal` is what would attribute it, and nothing here
ran with one. The plausible reading is that naming the computed collections put 13,118
more terms into the taxonomy, and `argIsa` is open-world about an argument with no type
and closed about one with any, so there is more for the check to convict on. Plausible
is not measured.

Load **order** is what most of those numbers are about, and `:arg-genl` is the line that
shows why: a definitional check can only fire against content already loaded, so an
`argGenl` arriving after the facts it governs never fires at all, and a check that never
fires is worse than no check — it reads as a clean load. The schema layer (`argIsa`,
`argGenl`, `disjointWith`, the memberships) therefore loads ahead of the facts checked
against it, which is what makes `:arg-genl`'s 156 and `:arg-type`'s 6,578 real readings
rather than artifacts of what had not arrived yet.

A refusal is not automatically a disagreement. Three kinds turn up, and only the first
is Cyc and vaelii genuinely differing:

**A real difference**, and the one that turned out to be ours. `rewriteOf` cycles are
still refused — a cycle there leaves the equality class with no head. `genlMt` cycles
are **not**: the corpus states 49 non-trivial components over 211 contexts and 427
edges, and one of them is the root of the whole lattice —

    BaseKB ↔ CycAgencyTheory ↔ CycHistoricalPossibilityTheory
           ↔ UniversalVocabulary ↔ UniversalVocabularyImplementation

Refusing an edge there narrowed what every microtheory could see, and *which* edge was
lost depended on the order the files were read, which is the one thing the engine is not
allowed to let arrival order decide. Mutual visibility is now admitted and answered
directly ([contexts.md](https://github.com/vaelii/vaelii/blob/main/docs/contexts.md)); the contexts are not merged, since a cycle claims
that each sees the other and not that they are one place.

**A refusal that costs nothing.** The corpus carries 864 reflexive edges — 517
`(genlContext X X)`, 328 `(genl X X)` (Cyc's `genls` and `genlPreds` both land there), 17
`(rewriteOf X X)`, 2 `(disjoint X X)`. Most of them are a NART on both sides, which is
why the figure is larger than the constant-to-constant count. The first three are refused
and lose nothing: those closures are reflexive by construction, so the edge was already
true before it was refused. The fourth is refused because it is **false** — a type
overlaps itself — which is the check working, not a disagreement.

**A bug of ours**, which is what the tally is for. Two were found by reading the refused
sentences rather than the counts, and both were the *engine* being wrong about Cyc rather
than the other way round:

* **An argument constraint about a function.** 13,057 of them — `(argIsa Milli 1
  unit_of_measure_no_prefix)`, `(argGenl ConveyViaFn 1 solid_tangible_thing)`. Cyc's
  `argIsa` constrains any relation, and a function is one; vaelii's `wff` refused a first
  argument spelled CapitalCamelCase as "an individual". But a function is spelled that
  way too, so the test could not tell the term it meant to admit from the one it meant to
  refuse — and it was throwing away the whole vocabulary of function argument types to
  catch a mistake nothing depends on. The constrained relation is no longer held to a
  spelling.
* **`argIsa` and `argGenl` on one position.** 12,468, every one of them `argGenl`. The
  check refused the pair because "no term satisfies both readings of one slot", which is
  false: `(argIsa P 2 collection)` with `(argGenl P 2 physical_device)` says the slot
  holds a kind of physical device, and any such kind satisfies both — an instance of
  `collection`, a subtype of `physical_device`. Cyc declares slots this way routinely.

And one that was neither, but a property of the **load order** — see *One batch, one
settle* above. `argIsa` is closed-world about an argument that has any type at all, so a
relational fact checked before the rest of its argument's memberships have arrived is
refused on a partial answer. That is why memberships are their own layer.

### The disjointness refusals were one microtheory speaking for all of them

The largest category was `:disjoint`, and it was neither a bug in a check nor Cyc
being inconsistent. It was the one structural mismatch between the two systems, and
OpenCyc is big enough to make it visible — and it is what the context-scoped
closures closed.

**The difference was where the visibility filter sits relative to the transitive
step.** Not that Cyc's taxonomy is per-microtheory data and ours is one blob — both
systems store each `(genl a b)` in a context, and the corpus does place each one
where Cyc stated it (`:support` is `{[a b] {handle ctx}}`, several supporters per
edge, each with its stating context). The difference is when the context is
consulted. **Cyc applies the microtheory cone at lookup, so every edge of a
transitive search is filtered as it is walked: a path exists in Mt M only if every
edge on it is visible from M.** The engine now does the same
([taxonomy.md](https://github.com/vaelii/vaelii/blob/main/docs/taxonomy.md), "Reads are
scoped by the asking context"): `genls` / `specs` / `genl?` /
`disjoint?` take the asking context, and `disjoint-problem` asks the scoped
question from the asserting context — the same cone `types-of` was already
filtered by, so the check's two halves finally agree about where they stand.

The clearest case is also the biggest. Cyc states

    (disjointWith #$Place #$Agent-Generic)          in #$PhysicalGeographyMt

and separately, elsewhere, that a city is a `#$GeopoliticalEntity`, that a geopolitical
entity is an `#$Organization` and so an `#$Agent-Generic`, and that it is a
`#$GeographicalRegion` and so a `#$Place`. The corpus's own topology is what shows why
Cyc stays consistent, and it is *not* that the microtheories are unrelated:
PhysicalGeographyMt **does** see UniversalVocabularyMt, where most of that chain lives.
It is that the cone cuts the chain somewhere in both directions —

* PhysicalGeographyMt's cone is 109 of the 13,302 context terms, and the AURA biology
  mapping that holds `(genls #$City #$GeopoliticalEntity)` is **not** in it; and
* only **3** contexts see PhysicalGeographyMt at all, so from wherever a city is
  actually asserted, the disjointness is invisible.

So the disjointness and the full subsumption path never coexist in any one Mt's cone,
and Cyc never has the clash to resolve. The global closure had both, everywhere:
**that one assertion produced 7,400 clashes, about half of the total**, and 78 disjoint
pairs accounted for all of them. Dropping the imported external ontologies
(`GeneOntologyContentContext`, the AURA biology mapping) changed the number by
**zero** — the subsumption paths run through `#$UniversalVocabularyMt`, which is
Cyc's own. So it was not an artefact of alignment layers, and no profile removed it.

The scoped `disjoint?` dissolves the category rather than patching it: a membership
is refused only when the separation and the paths to it are visible from where the
membership is written, which is Cyc's own consistency argument applied at our
lookup. A clash that becomes visible *jointly* — from a descendant that sees parts
no single writer could — is reported by `settle`'s exposure pass in
`(violations kb)` rather than blamed on a writer (the engine's own
`disjoint_test/a-disjointness-counts-only-where-its-declaration-is-visible` and
`exposure_test` pin both halves).

**There are 638 of them, and this corpus is what sized the pass that finds them.** A
separating declaration reaches back over the instances already stored, and the
candidate rule decides what that costs: sweeping everything below *either* side of a
separation asks for 26,518,841 instance enumerations across OpenCyc's 27,195 declared
pairs, where the terms that can actually convict — those holding a spec of each side —
need 1,694,193. At the 4,096-instance budget the first rule is spent after **27**
declarations of 27,195, so the pass paid a bounded cost for coverage it did not
achieve. The intersection reaches 8,372, and per trigger over all 37,701 `disjoint`
sentexes the pass cost 52 s against 321 s for the first 3,000 alone.

The corpus is also what makes the completeness claim checkable rather than arguable.
The engine's own `core/exposed-clashes` uses no candidate rule and no budget at all — a
term is a candidate iff it holds two believed memberships — so it is the independent
oracle, and the narrowed pass over every declaration reports the same 638 with both set
differences empty. A candidate rule that **over**-collects can reach that same 638 from
a fraction of the triggers and still be wrong: what it finds while sweeping a
declaration that does not implicate a clash gets filed against the wrong trigger, so the
total agrees while every attribution is off. Thousands of the declarations are against a
NART (`(disjoint (AbnormalFn chromosome) eukaryotic_organism)`) — see
[taxonomy.md](https://github.com/vaelii/vaelii/blob/main/docs/taxonomy.md), "What a
declaration reaches back over". Naming the computed collections narrowed that but did
not close it: 793 of the 13,966 named NART types are named in a disjointness, and only
**18** of those also head a membership, so the rest still implicate nobody.

It also **cascades**, which is why it dominates twice over. A refused membership is a
type the KB no longer knows the term has, and the next `argIsa` check reads exactly that:
`#$genls` is declared both `#$AsymmetricBinaryPredicate` and
`#$ReflexiveBinaryPredicate`, which are disjoint, so one of them is refused — and then
`(transitiveViaArg promotesRisk genls 3)` is refused too, because the KB can no longer
show that `genls` is the transitive predicate the constraint asks for. Over 1,200 of the
remaining `:arg-type` refusals are downstream of a disjointness refusal in this way.

### Where a name means slightly different things on the two sides

The other named share of what is left is neither engine nor corpus being wrong, but the
two using one word for concepts of different width. Cyc's `#$arity` applies to any
`#$FixedArityRelation`, functions included; vaelii's `arity` is about a predicate — it is
read by `checks/declared-arity` off a *sentence functor*, and a function never heads a
sentence — and the engine's own `resources/kb/CoreContext.txt` says so with
`(argIsa arity 1 predicate)`.
So 2,693 `(arity SomeFn n)` assertions are refused by **vaelii's own** constraint rather
than by anything Cyc said. `quotedIsa` is a smaller instance of the same shape.

This is the one category where the translation, not the engine, is the place to decide:
a Cyc predicate whose reading is wider than the vaelii predicate of the same name is
either narrowed on the way in or given a name of its own. Left as it is, and counted.

## What vaelii does not take

`genlInverse` is not vaelii's `inverse` (Cyc's relates a predicate to a
*generalization* of another's inverse), so it lands as an ordinary uninterpreted fact
rather than as metadata. The same goes for `arity`, `argFormat`, `quotedIsa`,
`negationInverse` and the rest of the vocabulary vaelii has no theory of: stored and
queryable, but nothing in the engine reads them.

What is above is the whole of the comparison this document owes: what the reader takes
from Cyc, what it narrows, and what it stores without interpreting. The wider
engine-to-engine comparison is in vaelii's internal design notes, which are not
published — so nothing here points at it.
