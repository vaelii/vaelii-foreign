(ns vaelii.foreign.cyc
  "Translate an OpenCyc KB — the CycL assertion dump read by `vaelii.foreign.cycl`
  — into vaelii KB files, and load them.

  Cyc and vaelii represent the same idea (a contextualized common-sense KB) with
  different spellings, so the translation is mostly a **renaming** plus a small
  table of sentence rewrites.  Three things make it more than a rename:

  * **A role decides a term's spelling.**  vaelii's naming invariants encode a
    term's role in its symbol — a type is `snake_case`, a predicate `camelCase`, an
    individual `CapitalCamelCase`, a context starts with `Cx` — while Cyc writes
    every constant `CapitalCamelCase` (predicates aside) and states the role as an
    assertion.  So the role must be **recovered first**: pass 1 reads the whole dump
    and classifies every constant, pass 2 translates with that classification in
    hand.  A constant's position is the evidence — arg 2 of `isa` is a collection,
    the head of a formula is a predicate, the microtheory slot is a context — backed
    by Cyc's own `(isa X Collection)` / `(isa X Microtheory)` declarations where they
    exist.  Position is decisive because collection-hood is usually *inherited* in
    Cyc (a term is an instance of some metatype, not of `Collection` itself), so the
    explicit declarations alone would classify a fraction of the KB.

  * **Cyc says with a predicate what vaelii says with a record field.**  `(isa P
    TransitiveBinaryPredicate)` is vaelii's `(transitive P)`; `(genls A B)` is
    `(genl a b)`; `(genlMt A B)` is `(genlCx CxA CxB)`; `(arg1Isa P
    C)` is `(arg p 1 c)`; a `:monotonic` assertion is a `{:strength :monotonic}`
    premise.  Everything without such a mapping stays an ordinary fact under its own
    (renamed) predicate — uninterpreted by the engine but stored, indexed and
    queryable, which is the honest outcome for vocabulary vaelii has no theory of.

  * **Some of the dump is not knowledge.**  SubL code (`afterAdding` hooks), forms
    the dumper could not resolve (`(:nart 23227)`), and assertions whose
    microtheory is itself unresolved are dropped, each with a counted reason.  The
    report is part of the output: a conversion that silently dropped a tenth of the
    KB would look exactly like one that dropped nothing.

  **Output.**  A `vaelii.foreign.corpus` directory — plain vaelii sentence files, one
  s-expression per line, partitioned by context — with `report.edn` carrying the
  per-predicate converted/dropped counts and `names.edn` the Cyc constant -> vaelii term
  map.  Cyc's `:monotonic` / `:default` marking is the corpus's strength split.

  Entry points: `convert!` (dump -> directory) and `load-dir!` (directory -> kb), both
  also reachable from `vaelii.foreign.convert`'s command line."
  (:require [clojure.string :as str]
            [taoensso.trove :as trove]
            [vaelii.foreign.corpus :as corpus]
            [vaelii.foreign.cycl :as cycl]
            [vaelii.foreign.term :as term]
            [vaelii.foreign.units :as units]))

;;; ── where the assertions come from ────────────────────────────────────

(defn with-assertions
  "Call `(f assertions)` on the assertions at `path`, whichever form they are in.

  Two things hold an OpenCyc KB and the translation reads either: the distribution's own
  binary dump directory (`vaelii.foreign.units`) and a CycL text re-dump of one
  (`vaelii.foreign.cycl`).  Both yield the same `{:formula :mt :strength :direction}`
  maps, so nothing downstream of here knows which it got — and the binary one needs no
  external tool, which is why it is what `convert!` is normally pointed at.

  The seq is only valid inside `f`; it reads from an open stream."
  [path f]
  (if (units/dump-directory? path)
    (units/with-assertions path f)
    (cycl/with-assertions path f)))

;;; ── what a role is read off ───────────────────────────────────────────

(def logical-operators
  "CycL's connectives and quantifiers.  They head formulas without being
  predicates, so the head-of-formula evidence must skip them."
  '#{cyc/and cyc/or cyc/not cyc/implies cyc/equiv cyc/xor cyc/forAll
     cyc/thereExists cyc/thereExistExactly cyc/thereExistAtLeast
     cyc/thereExistAtMost cyc/ist cyc/ke-assert})

(def collection-args
  "Argument positions (1-based) whose filler must be a Cyc collection."
  '{cyc/isa [2] cyc/quotedIsa [2] cyc/genls [1 2] cyc/disjointWith [1 2]
    cyc/typeGenls [1 2] cyc/argIsa [3] cyc/argGenl [3] cyc/argQuotedIsa [3]
    cyc/arg1Isa [2] cyc/arg2Isa [2] cyc/arg3Isa [2] cyc/arg4Isa [2] cyc/arg5Isa [2]
    cyc/arg6Isa [2] cyc/arg1Genl [2] cyc/arg2Genl [2] cyc/arg3Genl [2]
    cyc/arg4Genl [2] cyc/arg5Genl [2] cyc/arg1QuotedIsa [2] cyc/arg2QuotedIsa [2]
    cyc/arg3QuotedIsa [2] cyc/resultIsa [2] cyc/resultGenl [2]
    cyc/resultIsaArgIsa [2] cyc/collectionExpansion [1] cyc/superTaxons [1 2]
    cyc/disjointWithViaTypeIntersection [1 2] cyc/genlCanonicalizerDirectives [1]})

(def predicate-args
  "Argument positions whose filler must be a Cyc predicate."
  '{cyc/arity [1] cyc/arityMin [1] cyc/arityMax [1] cyc/argIsa [1] cyc/argGenl [1]
    cyc/argFormat [1] cyc/argQuotedIsa [1] cyc/genlPreds [1 2] cyc/genlInverse [1 2]
    cyc/negationInverse [1 2] cyc/typedGenlPreds [1 2] cyc/typedGenlInverse [1 2]
    cyc/arg1Isa [1] cyc/arg2Isa [1] cyc/arg3Isa [1] cyc/arg4Isa [1] cyc/arg5Isa [1]
    cyc/arg6Isa [1] cyc/arg1Genl [1] cyc/arg2Genl [1] cyc/arg3Genl [1]
    cyc/arg4Genl [1] cyc/arg5Genl [1] cyc/arg1Format [1] cyc/arg2Format [1]
    cyc/arg3Format [1] cyc/arg1QuotedIsa [1] cyc/arg2QuotedIsa [1]
    cyc/arg3QuotedIsa [1] cyc/functionalInArgs [1] cyc/strictlyFunctionalInArgs [1]
    cyc/singleEntryFormatInArgs [1] cyc/intervalEntryFormatInArgs [1]
    cyc/transitiveViaArg [1] cyc/transitiveViaArgInverse [1]
    cyc/functionCorrespondingPredicate-Canonical [2] cyc/afterAdding [1]
    cyc/afterRemoving [1] cyc/interArgIsa1-2 [1] cyc/interArgIsa2-1 [1]})

(def context-args
  "Argument positions whose filler must be a Cyc microtheory."
  '{cyc/genlMt [1 2] cyc/ist [1] cyc/mtVisible [1 2] cyc/genlMt-Vocabulary [1 2]})

(def function-args
  "Argument positions whose filler must be a Cyc function."
  '{cyc/resultIsa [1] cyc/resultGenl [1] cyc/arityMin [1] cyc/functionCorrespondingPredicate-Canonical [1]})

(def declared-role
  "The role Cyc's own `(isa X <metatype>)` declares outright.  Weaker evidence than
  position (a metatype is inherited far more often than it is stated), but it names
  roles position cannot — a function that never appears applied, a microtheory that
  never holds an assertion."
  '{cyc/Microtheory :context
    cyc/Collection :type cyc/FirstOrderCollection :type cyc/SecondOrderCollection :type
    cyc/Individual :individual
    cyc/Predicate :predicate cyc/BinaryPredicate :predicate cyc/TernaryPredicate :predicate
    cyc/QuaternaryPredicate :predicate cyc/QuintaryPredicate :predicate
    cyc/UnaryPredicate :predicate cyc/VariableArityPredicate :predicate
    cyc/Function-Denotational :function cyc/ReifiableFunction :function
    cyc/UnreifiableFunction :function cyc/UnaryFunction :function
    cyc/BinaryFunction :function cyc/CollectionDenotingFunction :function})

(def metadata-metatypes
  "Cyc metatypes that are vaelii **predicate metadata** — a declaration the engine
  reads, not an ordinary type membership.  `functional` is deliberately absent: Cyc's
  `FunctionalPredicate` constrains one argument position, while vaelii's `functional`
  *merges* a second value into the first through the equality closure, so importing
  the 4,332 functional declarations would rewrite terms wholesale on the strength of a
  mapping that is not quite the same claim.  `convert!` takes `:functional? true` to
  import them anyway."
  '{cyc/TransitiveBinaryPredicate transitive
    cyc/SymmetricBinaryPredicate symmetric
    cyc/ReflexiveBinaryPredicate reflexive
    ;; the two halves of vaelii's NAT declaration (vaelii.impl.nat), which Cyc
    ;; states as metatype membership and vaelii reads off a predicate: without it a
    ;; non-atomic term is never reified, and Cyc's KB is full of them
    cyc/ReifiableFunction reifiableFunction
    cyc/UnreifiableFunction unreifiableFunction})

(def functional-metatypes
  "Cyc metatypes that map to vaelii's `functional`, behind `:functional?`."
  '{cyc/FunctionalPredicate functional cyc/StrictlyFunctionalSlot functional
    cyc/FunctionalSlot functional})

(def cycl-collection-names
  "Cyc's `CycL*` syntactic collections to vaelii snake_case types.  `term`'s tokenizer
  spells `CycLConstant` as `cyc_l_constant` (the `L` a maximal uppercase run), so the
  `quotedIsa` translation renames the mentioned term's collection through this table
  instead — its targets are the `cycl_*` hierarchy `quote-vocabulary` declares.  A
  collection not here renames the ordinary way, an isolated `cyc_l_*` type."
  '{cyc/CycLExpression                cycl_expression
    cyc/CycLDenotationalTerm          cycl_denotational_term
    cyc/CycLReifiableDenotationalTerm cycl_reifiable_denotational_term
    cyc/CycLClosedDenotationalTerm    cycl_closed_denotational_term
    cyc/CycLConstant                  cycl_constant
    cyc/CycLFormula                   cycl_formula
    cyc/CycLSentence                  cycl_sentence
    cyc/CycLClosedAtomicSentence      cycl_closed_atomic_sentence
    cyc/CycLVariable                  cycl_variable})

(def cyc-syntactic-types
  "Cyc's SubL / string quoted-type collections to vaelii's syntactic types (the roots
  `vaelii.impl.checks/syntactic-roots` names), so `(argQuotedIsa p n CharacterString)`
  becomes the `(quotedArg p n string)` the engine actually checks against a literal's kind.
  A collection with no syntactic reading renames the ordinary way — an inert `quotedArg` on
  a domain type the check reads open-world, so an unmapped Cyc type is never mistranslated
  into a false refusal."
  '{cyc/CharacterString string  cyc/SubLString             string
    cyc/SubLSymbol      symbol  cyc/CycLConstant           symbol   cyc/SubLAtomicTerm symbol
    cyc/SubLInteger     integer cyc/PositiveInteger        integer  cyc/SubLNonNegativeInteger integer
    cyc/SubLRealNumber  number  cyc/SubLNumber             number})

(def quote-vocabulary
  "The fixed preamble a `Quote`-bearing corpus needs, emitted into `CxBaseKB` before the
  facts (like the computed-collection definitions) and **only** when a `Quote` term will
  appear — whether from a `quotedIsa` or a raw `#$Quote` in the dump (`convert!`'s
  `quotes?`): `Quote` as a **reifiable quoting function** — a reified `(Quote X)` mentions
  `X` as syntax and is held opaque to an identity merge of its referent (vaelii
  `quotingFunction`) — landing every quoted term in the `cycl_expression` tree, plus that
  syntactic-type hierarchy.  `Quote` reuses the engine's NAT machinery, so a quoted term
  needs no new engine support to reify and type.  `(quotingFunction Quote)` here is what
  arms the engine's mention-opacity walk; since `Quote` is definitionally quoting, this
  rides with the functor — present wherever `Quote` is, and nowhere else."
  '[(reifiableFunction Quote)
    (quotingFunction Quote)
    (result Quote cycl_expression)
    (genl cycl_denotational_term          cycl_expression)
    (genl cycl_reifiable_denotational_term cycl_denotational_term)
    (genl cycl_closed_denotational_term   cycl_denotational_term)
    (genl cycl_constant                   cycl_reifiable_denotational_term)
    (genl cycl_formula                    cycl_expression)
    (genl cycl_sentence                   cycl_formula)
    (genl cycl_closed_atomic_sentence     cycl_sentence)
    (genl cycl_variable                   cycl_expression)])

(def excluded-predicates
  "Cyc predicates dropped wholesale, each under the reason it is dropped **for** —
  three unlike things that shared one `:excluded` count until the reasons were read
  back and nobody could tell them apart.

  `afterAdding` / `afterRemoving` hold SubL code rather than knowledge; `sharedNotes`
  and `myCreationPurpose` are the KB editors' notes to each other; `termOfUnit`
  restates a NART's own definition, which this reader now writes itself (see
  `nat-definitions`) and vaelii would otherwise mint.

  Only the editorial pair is a **policy**, and `--editorial` reverses it — see
  `drop-flags`."
  '{cyc/afterAdding      :trigger-code
    cyc/afterRemoving    :trigger-code
    cyc/afterAddingMt    :trigger-code
    cyc/afterRemovingMt  :trigger-code
    cyc/sharedNotes      :editorial
    cyc/myCreationPurpose :editorial
    cyc/termOfUnit       :nart-definition})

(def drop-flags
  "For each `:filtered` drop, the convert option that keeps it — or a **string saying why
  there is none**, which is the only other admissible entry.

  A filtered drop is one this reader decided against on somebody else's behalf, so the
  person converting the KB gets to disagree.  Where they cannot, that is a claim about
  the content and it has to be written down rather than left to the absence of a flag:
  SubL is executable code and vaelii has no form for code, so there is nothing to import
  it *as* and a flag would only produce a fact nobody could read.

  A `:code` rule is the interesting one, and it is a flag rather than a justification
  because Cyc states the implication in full and then says it implements it in SubL
  instead of chaining it.  The logical content is right there; whether the code does
  something the formula only approximates is a judgement about a particular KB, which is
  exactly the sort of thing a flag is for.

  `plugin-test/every-filtered-drop-is-either-reversible-or-explained` requires an entry
  here for every reason `drop-kinds` calls `:filtered`, and requires each keyword to be
  an option `vaelii.foreign.convert` can actually set."
  {:editorial    :editorial?
   :code-rule    :code-rules?
   :trigger-code "SubL the engine runs on a KB event; there is no vaelii form for code"
   :subl-code    "a bare Lisp symbol anywhere in the formula — the same, one level down"})

(defn- kept?
  "Does `opts` ask for the drops of `reason` to be imported after all?  False for a
  reason whose `drop-flags` entry is a justification rather than an option, which is how
  \"there is no flag for this\" stays one statement instead of two."
  [opts reason]
  (let [flag (drop-flags reason)]
    (boolean (and (keyword? flag) (get opts flag)))))

;;; ── pass 1: classify every constant ───────────────────────────────────

(defn nameable-nat?
  "Is `x` a non-atomic term this reader can mint a name for — `(F a b …)`, a constant
  head over constants and literals?

  **Depth one only, and the reason is vaelii's, not Cyc's.**  A minted name is tied to
  its expression by `(termOfUnit K E)`, and `termOfUnit` quotes: nothing inside `E` is
  reified.  So a nested `(CollectionIntersection2Fn (SomeFn X) Y)` written into a
  `termOfUnit` keeps its inner NAT structural, while the same term reached through an
  ordinary argument position has its inner NAT reified first — two spellings of one
  expression, and the lookup that ties the name to the term misses.  A term this
  reader cannot name is left structural, which is what it was before."
  [x]
  (and (seq? x)
       (cycl/constant? (first x))
       (next x)
       (every? #(or (cycl/constant? %) (number? %) (string? %)) (rest x))))

(defn- note-seen
  "Record every constant `formula` mentions, at any depth.  A constant no positional
  rule ever reaches still needs a role, and `:seen` is what makes it an individual by
  residue rather than leaving it unnamed."
  [acc formula]
  (cond
    (cycl/constant? formula) (update acc :seen (fnil conj #{}) formula)
    (seq? formula)           (reduce note-seen acc formula)
    :else                    acc))

(declare note-formula)

(defn- note-term
  "Role evidence inside a **term** — a non-atomic term such as `(FruitFn AppleTree)`.
  Its head denotes a function, and its arguments are terms in turn.  A logical
  operator can still appear inside a term (`(TheSetOf ?x (and …))`), and what it
  heads is a formula again."
  [acc x]
  (cond
    (cycl/constant? x) (update acc :seen (fnil conj #{}) x)
    (not (seq? x))     acc
    :else
    (let [head (first x)]
      (if (and (cycl/constant? head) (not (logical-operators head)))
        (reduce note-term
                (-> acc
                    (update :function (fnil conj #{}) head)
                    (update :seen (fnil conj #{}) head))
                (rest x))
        (note-formula acc x)))))

(defn- note-formula
  "Fold the role evidence a **formula** gives into `acc` — a map of role -> set of
  constants.  Its head is a predicate (unless it is a connective), its argument
  positions are read through the position tables, and what those arguments hold is a
  term — except under a connective, where the arguments are formulas again.  The
  formula/term distinction is what keeps `(FruitFn AppleTree)` from being classified
  as a predicate application."
  [acc formula]
  (if-not (and (seq? formula) (symbol? (first formula)))
    (note-seen acc formula)
    (let [head     (first formula)
          args     (vec (rest formula))
          logical? (boolean (logical-operators head))
          at       (fn [acc role positions]
                     (reduce (fn [a i]
                               (let [x (get args (dec (long i)))]
                                 (cond
                                   (cycl/constant? x) (update a role (fnil conj #{}) x)
                                   ;; a computed microtheory in a context slot needs a
                                   ;; symbol of its own as much as one in the
                                   ;; microtheory slot does — `(genlMt (MtSpaceFn …) M)`
                                   ;; is how the context topology names it
                                   (and (= :context role) (seq? x))
                                   (update a :nat-context (fnil conj #{}) x)
                                   ;; and a NART in a **collection** slot needs one for
                                   ;; the same reason.  `(isa CityOfEricksonCanada
                                   ;; (CityInCountryFn Canada))` says Erickson is a city
                                   ;; in Canada; vaelii writes a membership as `(type
                                   ;; Individual)`, and a functor has to be a name.
                                   (and (= :type role) (nameable-nat? x))
                                   (update a :nat-type (fnil conj #{}) x)
                                   :else a)))
                             acc positions))
          acc      (cond-> (if (cycl/constant? head)
                             (update acc :seen (fnil conj #{}) head)
                             acc)
                     (and (cycl/constant? head) (not logical?))
                     (update :head (fnil conj #{}) head))
          acc      (-> acc
                       (at :type      (collection-args head))
                       (at :predicate (predicate-args head))
                       (at :context   (context-args head))
                       (at :function  (function-args head)))
          acc      (if-let [role (and (= 'cyc/isa head) (declared-role (second args)))]
                     (if (cycl/constant? (first args))
                       (update acc (keyword (str "declared-" (name role)))
                               (fnil conj #{}) (first args))
                       acc)
                     acc)
          ;; membership of a metatype vaelii reads as metadata says the subject is a
          ;; relation — `(isa siblingOf SymmetricBinaryPredicate)` names a predicate
          ;; as surely as an arity declaration does
          acc      (if (and (= 'cyc/isa head)
                            (cycl/constant? (first args))
                            (not (declared-role (second args)))
                            (or (metadata-metatypes (second args))
                                (functional-metatypes (second args))))
                     (update acc :declared-predicate (fnil conj #{}) (first args))
                     acc)]
      ;; `quotedIsa`'s arg 1 is a **mention** — the term named as syntax — so it is not
      ;; walked into `:seen`, which would make a term that appears only quoted an individual
      ;; by residue and spell it apart from its used occurrences.  Its collection (arg 2) is
      ;; still classified through `collection-args` above and the walk below.
      (reduce (if logical? note-formula note-term) acc
              (if (= 'cyc/quotedIsa head) (rest args) args)))))

(defn classify
  "Pass 1.  Read every assertion and return `{role #{constant …}}` — the raw
  evidence, before precedence is applied.  A microtheory that is itself a non-atomic
  term (`(MtSpace (MtTimeDimFn Now))` — Cyc computes microtheories as well as naming
  them) is collected under `:nat-context`, to be minted a context symbol of its own:
  vaelii names a context with a symbol, so a computed one has to be reified exactly
  as a NAT is.  A NART in a **collection** slot is collected under `:nat-type` for the
  same reason — vaelii writes a membership as `(type Individual)`, so a collection Cyc
  computes needs a name before anything can be said to belong to it."
  [assertions]
  (reduce (fn [acc {:keys [formula mt]}]
            (let [acc (cond
                        (cycl/constant? mt) (update acc :context (fnil conj #{}) mt)
                        (seq? mt)           (-> (note-term acc mt)
                                                (update :nat-context (fnil conj #{}) mt))
                        :else               acc)]
              (note-formula acc formula)))
          {}
          assertions))

(def role-precedence
  "`[role evidence-key]` in decreasing authority — the first rule that names a
  constant decides it.

  A **microtheory** is never anything else.  A term used as a **formula head** is a
  predicate whatever else is claimed of it, because vaelii refuses a functor that is
  not spelled as one: a wrong role there costs every fact about the term, while a
  wrong role anywhere else costs only the term's appearance.  Then Cyc's own `(isa X
  <metatype>)` **declarations**, which are the most direct statement of role there
  is.  Then **position** — a collection slot, a function slot, a predicate slot —
  and finally an **individual by residue**, Cyc's own default for a term that is
  neither a collection nor a relation."
  [[:context    :context]
   [:predicate  :head]
   [:function   :declared-function]
   [:type       :declared-type]
   [:predicate  :declared-predicate]
   [:context    :declared-context]
   [:individual :declared-individual]
   [:type       :type]
   [:function   :function]
   [:predicate  :predicate]
   [:individual :seen]])

(defn roles
  "The single role each constant is given, resolving the pass-1 evidence by
  `role-precedence`."
  [evidence]
  (term/resolve-role role-precedence evidence))

;;; ── renaming ──────────────────────────────────────────────────────────

(defn- nat-parts
  "The name parts of a non-atomic term, outermost first: its constants **and its
  literal numbers**.  The numbers are what tell Cyc's date-sliced microtheories apart
  — `(MtSpace M (MtTimeDimFn (YearFn 1980)))` and its 1981 sibling name the same
  constants — so dropping them would collapse thousands of distinct contexts onto one
  name and leave the difference to a numeric suffix that means nothing."
  [form]
  (cond
    (cycl/constant? form) [(cycl/cyc-name form)]
    (number? form)        [(str form)]
    (seq? form)           (vec (mapcat nat-parts form))
    :else                 []))

(defn name-table
  "The Cyc term -> vaelii term map, plus each term's role.  Keyed by constant, and
  additionally by the whole form for the two kinds of non-atomic term that need a name
  of their own: a **computed microtheory** (a NART in the microtheory slot, minted a
  context symbol) and a **computed collection** (a NART in a collection slot, minted a
  type symbol, so `(isa X (CityInCountryFn Canada))` has a functor to be written with).

  Built in sorted order so a collision resolves the same way on every run, whatever
  order the dump was read in; the constants go first, so a minted name is the one that
  takes a suffix when it collides with a name Cyc actually wrote."
  [role-map nat-contexts nat-types]
  (term/name-table
   (concat (for [c (sort-by str (keys role-map))]
             [c (role-map c) (cycl/cyc-name c)])
           (for [form (sort-by pr-str nat-contexts)]
             [form :context (term/abbreviate (str/join (nat-parts form)))])
           (for [form (sort-by pr-str nat-types)]
             [form :type (term/abbreviate (str/join "-" (nat-parts form)))]))))

;;; ── pass 2: translate ─────────────────────────────────────────────────

(defn- rename-term
  "The vaelii term for `x`: a renamed constant, a variable or literal unchanged, a
  nested application renamed part-wise (a non-atomic term stays structural — vaelii
  reifies it in `vaelii.impl.nat`).

  A whole form is looked up first, because a **computed microtheory** was minted a
  symbol under the form itself: wherever `(MtSpace M (YearFn 1980))` appears — the
  microtheory slot, or either side of a `genlMt` — it has to come out as the one
  context symbol that names it, or the topology would talk about a context nothing
  asserts into."
  [names x]
  (cond
    (cycl/constant? x) (:term (names x) (symbol (cycl/cyc-name x)))
    (seq? x)           (or (:term (names x))
                           (apply list (map #(rename-term names %) x)))
    :else              x))

(defn nat-definitions
  "`(termOfUnit K E)` for every computed **collection** in `names` — the sentence that
  ties the minted name to the expression it names.

  Written into the corpus rather than left to the engine, which mints its own opaque
  `nat/g19374`: identical in behaviour, unreadable in a corpus, and different on every
  run — which is exactly what a diff between two converted corpora cannot see through.
  With the definition written, `(genl (CityInCountryFn Canada) city)` and
  `(city_in_country_fn_canada CityOfEricksonCanada)` are one term, and it is a term with
  a name somebody can look up in the source.

  Computed **microtheories** get no such sentence.  A context is only ever used as a
  context, where `rename-term` has already resolved the whole form to its symbol, so
  the structural spelling never reaches the engine and has nothing to be tied to."
  [names]
  (for [[form {:keys [term role]}] (sort-by (comp pr-str key) names)
        :when (and (seq? form) (= :type role))]
    (list 'termOfUnit term (apply list (map #(rename-term names %) form)))))

(defn- unusable
  "The reason `formula` cannot become a vaelii sentence, or nil.  A SubL symbol is
  code; a `:nart` / `:unresolved-assertion` marker is a reference the dumper could
  not resolve; either makes the whole formula unusable however deeply it sits."
  [formula]
  (let [found (atom nil)]
    (letfn [(walk [x]
              (cond
                @found                nil
                (cycl/subl? x)        (reset! found :subl-code)
                (and (seq? x) (keyword? (first x))) (reset! found :unresolved)
                (seq? x)              (run! walk x)
                :else                 nil))]
      (walk formula)
      @found)))

(defn- ground?
  "Is `formula` variable-free?  vaelii refuses a non-ground fact: stored as a premise
  it would unify with every goal of its shape."
  [formula]
  (cond
    (symbol? formula) (not (str/starts-with? (name formula) "?"))
    (seq? formula)    (every? ground? formula)
    :else             true))

(defn- arg-isa-position
  "The argument number an `argNIsa` / `argNGenl` / `argNQuotedIsa` / `argNFormat`
  predicate names, or nil."
  [head]
  (when-let [[_ n] (re-matches #"arg(\d+)(Isa|Genl|QuotedIsa|Format)" (cycl/cyc-name head))]
    (Long/parseLong n)))

(def connectives
  "The CycL connectives vaelii writes the same way.  Anything else structural makes the
  formula untranslatable rather than being flattened into something it does not say."
  '{cyc/and and cyc/not not cyc/implies implies})

(defn- clause-literal
  "A formula read as one literal of a **clause**: `[:neg L]` for `(not L)`, `[:pos L]`
  otherwise.  A doubly-negated or compound-negated form is not a literal and comes back
  `[:pos <the whole thing>]`, which the caller's own translation then refuses."
  [x]
  (if (and (seq? x) (= 'cyc/not (first x)) (= 2 (count x)) (seq? (second x)))
    [:neg (second x)]
    [:pos x]))

(defn- wrap-rule
  "A translated implication under the wrappers its assertion's strength and direction
  call for: Cyc's `:default` is vaelii's defeasible rule, and Cyc's forward/backward
  direction is vaelii's."
  [body strength direction]
  (let [r (cond->> body (= :default strength) (list 'set/defaultRule))]
    (case direction
      :forward  (list 'set/forwardRule r)
      :backward (list 'set/backwardRule r)
      r)))

(defn rewrite-literal
  "One CycL literal as a vaelii literal.  This is the whole predicate mapping, and it
  runs on a rule's antecedents exactly as it runs on a standalone fact: a rule whose
  body kept Cyc's `(isa ?x Dog)` would never match the `(dog Rover)` the same
  translation stores.  Returns nil for a literal with no vaelii reading."
  [names literal]
  (let [term #(rename-term names %)
        head (first literal)
        args (vec (rest literal))
        arg  #(get args (dec (long %)))
        ;; Nothing upstream of here holds a formula to its predicate's arity, and a dump
        ;; is somebody else's file: `(genls A)` arrives as readily as `(genls A B)`.  The
        ;; four sentences below are binary by construction, so an argument that is not
        ;; there would be written as a literal `nil` — a term naming nothing, in a
        ;; corpus, indistinguishable from one Cyc meant.  Absent, the literal has no
        ;; reading and the caller drops it with a reason.
        binary? (= 2 (count args))]
    (case (str head)
      "cyc/genls"        (when binary? (list 'genl (term (arg 1)) (term (arg 2))))
      ;; vaelii's predicate hierarchy is the same genl closure as its type hierarchy
      ;; — a rule concluding a sub-predicate answers a super-predicate goal — so
      ;; genlPreds and genls translate alike.
      "cyc/genlPreds"    (when binary? (list 'genl (term (arg 1)) (term (arg 2))))
      "cyc/genlMt"       (when binary? (list 'genlCx (term (arg 1)) (term (arg 2))))
      "cyc/disjointWith" (when binary? (list 'disjoint (term (arg 1)) (term (arg 2))))
      "cyc/comment"      (when (string? (arg 2)) (list 'comment (term (arg 1)) (arg 2)))
      "cyc/argIsa"       (when (integer? (arg 2))
                           (list 'arg (term (arg 1)) (arg 2) (term (arg 3))))
      "cyc/argGenl"      (when (integer? (arg 2))
                           (list 'genlArg (term (arg 1)) (arg 2) (term (arg 3))))
      ;; A function's output type. The engine spells the two `result` and `genlResult`
      ;; — named for what they say rather than for the check that reads them, as `arg`
      ;; is — so they translate here rather than renaming through the default branch.
      "cyc/resultIsa"    (when binary? (list 'result (term (arg 1)) (term (arg 2))))
      "cyc/resultGenl"   (when binary? (list 'genlResult (term (arg 1)) (term (arg 2))))
      ;; `(argQuotedIsa P n C)` types argument n **as a term** — vaelii's `quotedArg`, with
      ;; the Cyc quoted-type collection mapped to a syntactic type where it has one.
      "cyc/argQuotedIsa" (when (integer? (arg 2))
                           (list 'quotedArg (term (arg 1)) (arg 2)
                                 (or (cyc-syntactic-types (arg 3)) (term (arg 3)))))
      ;; `(isa I C)` is vaelii's `(c I)`: a type *is* the unary predicate.  A computed
      ;; collection was minted a type name in pass 1 and renames to one; anything that
      ;; still comes back structural has no functor to be written with.
      "cyc/isa"          (let [c (term (arg 2))]
                           (when (symbol? c) (list c (term (arg 1)))))
      ;; `(quotedIsa X C)` is `(isa X C)` said of `X` **as syntax**: the term `X`, not its
      ;; referent, is a `C`.  vaelii has no `isa`, so it is the unary membership `(c (Quote
      ;; X))` — `Quote` reifies `(Quote X)` to a mention constant that `c` types.  `X` stays
      ;; a live symbol (spelling stays congruent with its used occurrences); the collection
      ;; renames through `cycl-collection-names` so `CycLConstant` is `cycl_constant`, not
      ;; `cyc_l_constant`.
      "cyc/quotedIsa"    (when binary?
                           (let [c (or (cycl-collection-names (arg 2)) (term (arg 2)))]
                             (when (symbol? c) (list c (list 'Quote (term (arg 1)))))))
      (if-let [n (arg-isa-position head)]
        ;; the argNIsa / argNGenl / argNQuotedIsa / argNFormat family folds into one
        ;; positional form — Isa/Genl/QuotedIsa to the renamed `arg` / `genlArg` /
        ;; `quotedArg` (its type through the syntactic map), the rest kept under their
        ;; concatenated spelling (an inert `argFormat`, etc.).
        (let [kind   (subs (cycl/cyc-name head) (count (str "arg" n)))
              target ({"Isa" 'arg, "Genl" 'genlArg} kind)]
          (cond
            (= "QuotedIsa" kind)
            (list 'quotedArg (term (arg 1)) n (or (cyc-syntactic-types (arg 2)) (term (arg 2))))
            target
            (list target (term (arg 1)) n (term (arg 2)))
            :else
            (apply list (symbol (str "arg" kind)) (term (arg 1)) n (map term (rest args)))))
        (apply list (term head) (map term args))))))

(defn rewrite-formula
  "A whole CycL formula as a vaelii sentence — the connectives kept, every literal
  under them rewritten.  Returns nil if any part has no vaelii reading."
  [names formula]
  (if-not (seq? formula)
    nil
    (if-let [connective (connectives (first formula))]
      (let [parts (map #(rewrite-formula names %) (rest formula))]
        (when (every? some? parts) (apply list connective parts)))
      (when-not (logical-operators (first formula))
        (rewrite-literal names formula)))))

(defn translate
  "The vaelii sentences one CycL assertion becomes: `{:sentences [...] :context C
  :strength s}`, or `{:dropped reason}`.  A `:sentences` vector rather than one
  sentence because a single Cyc assertion can state two vaelii things (a metadata
  declaration is also a type membership), and `:context` may be `:topology` for a
  sentence that belongs to the context wiring rather than to any one context.

  **A Cyc assertion is a clause, and its positive/negative literals are vaelii's own
  polarity**, so the structural arms below are the clause shapes rather than a list of
  connectives that happen to be handled:

  | clause                | CycL                          | vaelii                     |
  |-----------------------|-------------------------------|----------------------------|
  | one positive literal  | `(P a b)`                     | a fact                     |
  | one negative literal  | `(not (P a b))`               | a fact at `:false`         |
  | one positive, n negative | `(implies (and A B) C)` / `(or (not A) (not B) C)` | a rule |
  | all negative          | `(not (and A B))`             | — an integrity constraint  |
  | several positive      | `(or C1 C2)`                  | — a real disjunction       |

  The last two have no vaelii reading and are dropped under their own reasons rather
  than one shared `:unsupported-connective`, because they are refused for different
  reasons and only one of them is a candidate for ever being supported.

  Reading the **EL formula** rather than the clause Cyc canonicalizes it to is
  deliberate: `¬A ∨ ¬B` says nothing about which literal the author wrote as the
  conclusion, and `(implies A (not B))` does.  A rule direction that survives the trip
  is worth more than a normal form."
  [{:keys [formula mt strength direction] :as assertion} names opts]
  (let [term  #(rename-term names %)
        head  (when (seq? formula) (first formula))
        args  (if (seq? formula) (vec (rest formula)) [])
        arg   #(get args (dec (long %)))
        why   (delay (unusable formula))]
    (cond
      (not (seq? formula))              {:dropped :not-a-formula}
      (nil? (:term (names mt)))         {:dropped :unresolved-mt}
      (not (cycl/constant? head))       {:dropped :no-head}
      (and (excluded-predicates head)
           (not (kept? opts (excluded-predicates head))))
      {:dropped (excluded-predicates head)}
      @why                              {:dropped @why}
      :else
      (let [ctx (:term (names mt))
            ok  (fn [sentences & [where]]
                  {:sentences (vec sentences) :context (or where ctx) :strength strength})]
        (cond
          ;; the context topology is its own file: every context has to exist before
          ;; the sentences whose checks read it
          (= 'cyc/genlMt head)
          (if-let [s (rewrite-literal names formula)]
            (ok [s] :topology)
            {:dropped :untranslatable})

          ;; a rule.  `:code` names a rule Cyc implements in SubL rather than states,
          ;; so there is nothing to translate.
          (= 'cyc/implies head)
          (if (and (= :code direction) (not (kept? opts :code-rule)))
            {:dropped :code-rule}
            (if-let [body (rewrite-formula names formula)]
              (ok [(wrap-rule body strength direction)])
              {:dropped :untranslatable-rule}))

          ;; a negative unit clause.  vaelii stores `(not S)` as a first-class sentex —
          ;; the record's own `:truth`, not a wrapper — so this is the same fact with
          ;; the other polarity, and dropping it would lose a claim we can hold.
          (= 'cyc/not head)
          (let [[polarity inner] (clause-literal formula)]
            (cond
              (= :pos polarity)                 {:dropped :untranslatable}
              (logical-operators (first inner)) {:dropped :all-negative-clause}
              (not (ground? inner))             {:dropped :non-ground}
              :else (if-let [s (rewrite-literal names inner)]
                      (ok [(list 'not s)])
                      {:dropped :untranslatable})))

          ;; a disjunction is a rule exactly when one literal is positive: the Horn
          ;; clause `¬A ∨ ¬B ∨ C` is `A ∧ B ⇒ C`, which is the shape vaelii's rules are.
          ;; With several positive literals it is a real disjunction and there is
          ;; nothing here to write it as.
          (= 'cyc/or head)
          (let [lits (map clause-literal args)
                pos  (keep (fn [[p l]] (when (= :pos p) l)) lits)
                neg  (keep (fn [[p l]] (when (= :neg p) l)) lits)]
            (cond
              (empty? pos)                        {:dropped :all-negative-clause}
              (next pos)                          {:dropped :disjunction}
              ;; no negative literal: the clause is a unit, so it is the fact itself
              ;; and is held to a fact's groundness
              (and (empty? neg)
                   (not (ground? (first pos))))   {:dropped :non-ground}
              :else
              (let [body (if (seq neg)
                           (list 'cyc/implies
                                 (if (next neg) (apply list 'cyc/and neg) (first neg))
                                 (first pos))
                           (first pos))]
                (if-let [s (rewrite-formula names body)]
                  (ok [(if (seq neg) (wrap-rule s strength direction) s)])
                  {:dropped (if (seq neg) :untranslatable-rule :untranslatable)}))))

          ;; a conjunction of assertions is n assertions, and each is translated as one:
          ;; a conjunct may itself be an `isa` that states two vaelii things.  They have
          ;; to land in one context, since the assertion names one microtheory and a
          ;; conjunct routed elsewhere (`genlMt`, to the topology) would split it.
          (= 'cyc/and head)
          (let [parts (mapv #(translate (assoc assertion :formula %) names opts) args)]
            (cond
              (empty? parts)                          {:dropped :untranslatable}
              (some :dropped parts)                   {:dropped (some :dropped parts)}
              (not (apply = (map :context parts)))    {:dropped :mixed-context-conjunction}
              :else (ok (vec (mapcat :sentences parts)) (:context (first parts)))))

          ;; a type membership, which may also be a metadata declaration.  The
          ;; collection is usually a constant and does not have to be: pass 1 minted a
          ;; type name for every computed collection it could, so `(isa
          ;; CityOfEricksonCanada (CityInCountryFn Canada))` has a functor to be written
          ;; with.  What is required here is only that the collection renames to a
          ;; **symbol** — a functor is a name, and a NAT this reader could not name is
          ;; still one vaelii has no membership form for.
          (= 'cyc/isa head)
          (let [c  (arg 2)
                ct (term c)]
            (cond
              ;; being a microtheory is carried by the context topology instead
              (= 'cyc/Microtheory c) {:dropped :context-declaration}
              (not (symbol? ct))     {:dropped :unnameable-type}
              :else
              (let [meta-pred  (and (cycl/constant? c)
                                    (or (metadata-metatypes c)
                                        (when (:functional? opts) (functional-metatypes c))))
                    membership (list ct (term (arg 1)))]
                (ok (if meta-pred
                      [membership (list meta-pred (term (arg 1)))]
                      [membership])))))

          (logical-operators head)
          {:dropped :unsupported-connective}

          (not (ground? formula))
          {:dropped :non-ground}

          :else
          (if-let [s (rewrite-literal names formula)]
            (ok [s])
            {:dropped :untranslatable}))))))

;;; ── writing the corpus ────────────────────────────────────────────────

(def drop-kinds
  "What each of this reader's drop reasons **is** — see `corpus/drop-kinds`.  A reason
  missing here counts as `:unread`.

  The `:filtered` four are the deliberate ones: Cyc's `afterAdding` triggers hold SubL
  the engine runs rather than a claim it holds, `sharedNotes` and `myCreationPurpose`
  are the KB editors writing to each other, and a formula containing a SubL symbol
  anywhere is code however deeply it sits.  Two of the four are reversible from the
  command line and two are not, which `drop-flags` says one reason at a time.

  What is left in `:unread` is the honest remainder — a disjunction, an integrity
  constraint, a non-ground fact — and every one of those is a shape vaelii has no form
  for, not a shape this reader failed to read.  `:unresolved` is the exception and
  belongs to the dump: a reference its own writer could not resolve."
  '{:trigger-code        :filtered
    :editorial           :filtered
    :subl-code           :filtered
    :code-rule           :filtered
    :nart-definition     :restated
    :context-declaration :restated})

(defn convert!
  "Convert the CycL dump at `dump-path` into a vaelii corpus under `out-dir`.  Two
  passes over the dump: the first classifies every constant, the second writes the
  sentences.  Returns the report map (also written as `report.edn`).

  Options: `:functional?` imports Cyc's functional-predicate declarations as vaelii's
  `functional` (off — see `metadata-metatypes`); `:editorial?` keeps `sharedNotes` and
  `myCreationPurpose` (off); `:code-rules?` keeps the rules Cyc states in full and then
  implements in SubL (off — both are `drop-flags`); `:limit` reads only the first n
  assertions, for a quick sample."
  ([dump-path out-dir] (convert! dump-path out-dir {}))
  ([dump-path out-dir opts]
   (let [limit (:limit opts)
         ;; A capped read is a sample of the dump and not a reading of it, which is why
         ;; `units/with-assertions` checks the dump's own record count only when the read
         ;; reached the end of the file.
         cap   (fn [xs] (if limit (take limit xs) xs))]
     (trove/log! {:level :info :id ::classify :msg "pass 1: classifying constants"})
     (let [evidence (with-assertions dump-path #(classify (cap %)))
           role-map (roles (dissoc evidence :nat-context :nat-type))
           names    (name-table role-map (:nat-context evidence) (:nat-type evidence))
           ;; `Quote` is *definitionally* a quoting function, so its preamble — including
           ;; `(quotingFunction Quote)`, which arms the engine's mention-opacity walk —
           ;; must be present whenever a `Quote` term is, and absent otherwise (the engine's
           ;; zero-cost-until-declared contract).  Two things put a `Quote` in the output:
           ;; the `quotedIsa` arm emits one, and a raw `#$Quote` in the dump renames to it
           ;; and passes through.  Pass 1 saw both — `quotedIsa` as a predicate head, `Quote`
           ;; as a seen constant — so the union is the exact condition, erring toward
           ;; emitting (a dropped occurrence over-emits harmlessly; a missed one would leave
           ;; a live mention un-opaque).
           quotes?  (or (contains? (:head evidence) 'cyc/quotedIsa)
                        (contains? (:seen evidence) 'cyc/Quote))]
       (trove/log! {:level :info :id ::translate
                    :msg (str "pass 2: translating (" (count names) " constants)")})
       (let [report
             (corpus/write!
              out-dir
              {:format         :vaelii-cyc-corpus/v1
               :source         dump-path
               :options        opts
               :names          names
               ;; Cyc's own root microtheory is this corpus's root: every other
               ;; microtheory is under it already, so the corpus hangs off vaelii's
               ;; vocabulary at exactly the point Cyc's own hierarchy has a top.
               :root-context   'CxBaseKB
               :notice
               (str "Cyc(R) Knowledge Base (C) 1995-2008 Cycorp, Inc., Austin, TX, USA.\n"
                    "The OpenCyc Knowledge Base is licensed under the Apache License,\n"
                    "Version 2.0, and opencyc-4.0/LEGAL.txt extends those terms to\n"
                    "\"renamings and other logically equivalent reformulations of the\n"
                    "Knowledge Base (or portions thereof) in any natural or formal\n"
                    "language\" -- which is what this corpus is.  It is therefore\n"
                    "Apache-2.0 content carrying Cycorp's copyright, and cannot be\n"
                    "relicensed.  Cyc and OpenCyc are trademarks of Cycorp, Inc.\n"
                    "\nResearchCyc and the full Cyc KB are licensed separately and are\n"
                    "not redistributable on these terms.\n")}
              (fn [emit!]
                (let [counts  (atom {:read 0 :dropped 0})
                      by-pred (atom {})
                      dropped (atom {})
                      defs    (vec (nat-definitions names))]
                  ;; The computed collections, defined before anything uses one — see
                  ;; `corpus/term-definition?` for why that is an ordering requirement
                  ;; and not a preference.  They go in the root context because a term's
                  ;; identity is not a claim any one microtheory gets to hold.  The quote
                  ;; preamble rides beside them, before the first `(cycl_… (Quote …))`.
                  (emit! 'CxBaseKB :monotonic (into (if quotes? quote-vocabulary []) defs))
                  (with-assertions
                    dump-path
                    (fn [as]
                      (doseq [a (cap as)]
                        (swap! counts update :read inc)
                        (let [head (when (seq? (:formula a)) (first (:formula a)))
                              r    (translate a names opts)]
                          (if-let [why (:dropped r)]
                            (do (swap! counts update :dropped inc)
                                (swap! dropped update why (fnil inc 0))
                                (swap! by-pred update-in [(str head) :dropped] (fnil inc 0)))
                            (let [{:keys [sentences context strength]} r]
                              (swap! by-pred update-in [(str head) :written]
                                     (fnil + 0) (count sentences))
                              (emit! context strength sentences)))))))
                  {:source       (str dump-path)
                   :assertions   (:read @counts)
                   :dropped      (:dropped @counts)
                   :drop-reasons (into (sorted-map) @dropped)
                   :drop-kinds   drop-kinds
                   :nat-terms    (count defs)
                   :constants    (frequencies (map :role (vals names)))
                   :by-predicate (into (sorted-map) @by-pred)})))]
         (trove/log! {:level :info :id ::converted
                      :msg (str "converted " (:assertions report) " assertions -> "
                                (:sentences report) " sentences in " (:contexts report)
                                " contexts (" (:dropped report) " dropped)")})
         report)))))

;;; ── loading a corpus ──────────────────────────────────────────────────

(def profiles
  "Named subsets of a corpus.  `:full` loads everything; `:ontology` drops the
  natural-language and bookkeeping layers, which are the bulk of the corpus and none
  of its inference; `:core` keeps only Cyc's own upper vocabulary."
  {:full     {}
   :ontology {:drop-contexts #{'CxEnglish 'CxBookkeeping 'CxGeneralLexicon}
              :drop-predicates '#{prettyString prettyStringCanonical broaderTerm
                                  synonymousExternalConcept nameString
                                  termStrings termPhrases myCreator myCreationTime
                                  myCreationSecond myCreationPurpose}}
   :core     {:keep-contexts #{'CxBaseKB 'CxUniversalVocabulary
                               'CxCoreCycL 'CxCoreCycLImplementation}}})

(defn load-dir!
  "Load the corpus at `dir` into `kb` — `vaelii.foreign.corpus/load-dir!` with this
  format's `profiles`, which is the whole of what is Cyc-specific about reading one
  back.  See there for the options, the layering, and what a refusal count means."
  ([kb dir] (load-dir! kb dir {}))
  ([kb dir opts] (corpus/load-dir! kb dir profiles opts)))

(def reader
  "This format's reader, as the seam (`vaelii.impl.foreign`) hands it out — declared by
  `resources/vaelii/foreign.edn`, and the whole surface a caller reaches through it."
  {:name       "OpenCyc corpus"
   :load-dir!  load-dir!
   :convert!   convert!
   :profiles   profiles
   :drop-kinds drop-kinds
   :drop-flags drop-flags})
