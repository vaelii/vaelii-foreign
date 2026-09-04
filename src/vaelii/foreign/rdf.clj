(ns vaelii.foreign.rdf
  "Translate an RDF graph — RDFS and OWL, read by `vaelii.foreign.turtle` — into a
  vaelii corpus.

  This is the reader with the widest reach in the repo and the one that gives up the
  most, and both come from the same fact: **OWL is a description logic and vaelii is a
  Horn rule engine.**  So the translation is not a rename with a table of exceptions the
  way Cyc's is.  It is a *projection*: the fragment of OWL that has a Horn reading comes
  across as rules and declarations, and the rest is dropped under a counted reason.

  ## What comes across

  | RDF / OWL                                      | vaelii                              |
  |------------------------------------------------|-------------------------------------|
  | `C rdfs:subClassOf D`                          | `(genl c d)`                        |
  | `X rdf:type C`                                 | `(c X)`                             |
  | `P rdfs:subPropertyOf Q`                       | `(genl p q)`                        |
  | `P rdfs:domain C` / `rdfs:range C`             | `(arg p 1 c)` / `(arg p 2 c)` |
  | `C owl:disjointWith D`                         | `(disjoint c d)`                    |
  | `owl:AllDisjointClasses`                       | `(disjoint …)`, pairwise            |
  | `P owl:inverseOf Q`                            | `(inverse p q)`                     |
  | `P a owl:TransitiveProperty` (and the rest)    | `(transitive p)`, `(symmetric p)`, … |
  | `C owl:equivalentClass D`                      | `(genl c d)` **and** `(genl d c)`   |
  | `P owl:propertyChainAxiom (Q R)`               | `(implies (and (q ?x ?y) (r ?y ?z)) (p ?x ?z))` |
  | `C ⊑ ∀P.D`   (`owl:allValuesFrom`)             | `(implies (and (c ?x) (p ?x ?y)) (d ?y))` |
  | `C ⊑ ∃P.{v}` (`owl:hasValue`)                  | `(implies (c ?x) (p ?x V))`         |
  | `C ≡ ∃P.D`   (`owl:someValuesFrom`)            | `(implies (and (p ?x ?y) (d ?y)) (c ?x))` |
  | `C ≡ D1 ⊓ D2` (`owl:intersectionOf`)           | the rule **and** both `genl` edges  |
  | `O owl:imports O2`                             | `(genlCx CxO CxO2)`  |
  | anything else                                  | `(p S O)`, a plain fact             |

  ## What does not, and why that is the interesting half

  * **`C ⊑ ∃P.D`** — an existential in the *conclusion*.  \"Every hand has a finger\"
    obliges the reasoner to invent a finger, which is what a description logic does and
    a rule engine does not.  Counted as `:existential-superclass`, and it is the single
    largest drop on a real ontology.
  * **`owl:unionOf` in the ⊑ direction, `owl:oneOf`, `owl:complementOf`** — a real
    disjunction, a real closed class, a real negation.  Each is a claim vaelii could
    hold only by weakening it into something else.
  * **Cardinality** (`owl:minCardinality`, `maxCardinality`, `qualifiedCardinality`) —
    counting constraints have no Horn form at all.  `owl:FunctionalProperty` is the one
    exception and only behind `:functional?`, for the same reason it is behind that flag
    in the Cyc reader: vaelii's `functional` *merges* two values through the equality
    closure rather than refusing the second.

  Reading the two directions of `owl:equivalentClass` separately is what buys most of
  what is kept: `C ≡ ∃P.D` is worthless as a definition and perfectly good as the
  sufficient-condition half of one.

  ## Blank nodes, and the n-ary relations they stand for

  RDF triples are binary, so a relation with more than two arguments has to be written
  as a **node with one edge per argument** — the W3C n-ary pattern, Wikidata's
  statements, schema.org's Role, and every `[ :city \"Austin\" ; :zip \"78701\" ]` anybody
  ever wrote.  vaelii predicates have no arity limit, so such a node can become one fact
  of higher arity:

      ex:Bob ex:hasJob [ ex:employer ex:Acme ; ex:role ex:Engineer ; ex:since 2020 ] .
      -> (hasJob Bob Acme Engineer 2020)   with  (arity hasJob 4)

  That is only sound when the shape is **uniform**, and `bnode-plan` is the pass that
  decides.  Flattening positionally needs every node a predicate reaches to carry the
  same qualifiers, exactly once each, with nameable fillers, reached from one place, and
  the predicate to take no plain object anywhere else.  Miss any of those and the
  arguments silently misalign — `(hasJob Ann Beta 1999)` with a date where a rule reads
  a role.  vaelii is open-world about arity until something declares it, so nothing
  would refuse the misaligned fact; it would simply never match.  Hence the `(arity p
  n)` written beside the tuples: once declared, a later fact at the wrong arity is a
  refusal rather than a sentence quietly matching nothing.

  Every other blank node is **skolemized** — given a name built from the subject and
  predicate that reach it (`BobAward`), with its own triples kept as ordinary facts:

      ex:Bob ex:award [ ex:name \"Prize\" ; ex:year 2001 ] .
      -> (award Bob BobAward) (name BobAward \"Prize\") (year BobAward 2001)

  That loses nothing — it is what the n-ary pattern already means — and it is what a
  node with an identity of its own wants regardless.  A **typed** node is always
  skolemized: `[ a :Employment ; … ]` claims to be an instance of something, and
  flattening would take away the term the membership hangs on.  So is a **shared** one,
  since flattening would copy it rather than move it.

  Only the interior of an OWL construct is left alone, because the axiom that owns it
  has already said what it means.  `:n-ary? false` skolemizes everything, which is the
  reading to take if a rule set was written against the joined form.

  ## Naming and contexts

  A term is named from its IRI's **local part** — after the last `#` or `/` — and its
  role decides the spelling, so `ex:Mammal` used as a class becomes the type `mammal`
  and `ex:hasPart` used as a property becomes `hasPart`.  Two IRIs with the same local
  part in different namespaces collide, which is normal and is resolved by suffix, in a
  sorted pass, so a re-run of the same graph produces the same names.

  A **named graph** is a context (that is what N-Quads' fourth term is for), and so is
  an `owl:Ontology`.  A graph that declares neither lands in one context named for the
  source.  `owl:imports` is a `genlCx` edge, which makes an ontology's import
  closure the context hierarchy it always was."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [taoensso.trove :as trove]
            [vaelii.foreign.corpus :as corpus]
            [vaelii.foreign.rdfxml :as rdfxml]
            [vaelii.foreign.term :as term]
            [vaelii.foreign.turtle :as ttl]))

;;; ── which syntax ──────────────────────────────────────────────────────

(def ^:private xml-start
  "What an RDF/XML document begins with once comments and whitespace are past: the XML
  declaration, a doctype, or a start tag.  The last pattern is what tells RDF/XML from
  N-Triples, which also begins with `<`: `<rdf:RDF ` is a tag and `<http://a/s>` is not,
  because a tag's prefix is followed by a name where an IRI's scheme is followed by `//`."
  #"^(?:<\?xml|<!DOCTYPE|<[A-Za-z_][\w.-]*(?::[A-Za-z_][\w.-]*)?[\s>/])")

(defn syntax
  "Which lexer reads the document at `path` — `:rdf-xml` or `:turtle`.

  Decided by **looking**, not by the extension.  `.owl` is used for both RDF/XML and
  Turtle by different publishers (the OBO Foundry ships Turtle as `.owl`; OpenCyc and
  DOLCE ship RDF/XML as `.owl`), so an extension table would be wrong about real files
  from real sources.  The first few hundred bytes are unambiguous."
  [path]
  (with-open [in (io/input-stream path)]
    (let [in (if (str/ends-with? (str/lower-case (str path)) ".gz")
               (java.util.zip.GZIPInputStream. in)
               in)
          buf (char-array 4096)
          n   (.read (io/reader in) buf 0 4096)
          head (-> (String. buf 0 (max 0 n))
                   ;; XML comments and the shebang-ish leading noise say nothing about
                   ;; the syntax; Turtle's `#` comments are stripped for the same reason
                   (str/replace #"(?s)<!--.*?-->" "")
                   (str/replace #"(?m)^\s*#.*$" "")
                   str/trim)]
      (if (re-find xml-start head) :rdf-xml :turtle))))

(defn with-triples
  "`(f triples)` over the RDF at `path`, whichever syntax it is in.  The one place this
  namespace touches a lexer, which is why adding RDF/XML changed nothing below it."
  ([path f] (with-triples path f {}))
  ([path f opts]
   (if (= :rdf-xml (syntax path))
     (rdfxml/with-triples path f opts)
     (ttl/with-triples path f opts))))

;;; ── the vocabulary ────────────────────────────────────────────────────

;; The namespaces and the individual IRIs are this reader's own spelling of the
;; vocabulary it reads, and nothing outside reaches for one: what a caller wants is the
;; tables below, which say what a reading *is* — `annotation-predicates`,
;; `consumed-vocabulary`, `meta-types`.  Same arrangement, and for the same reason, as
;; `turtle`'s `rdf-first` and `rdfxml`'s `rdf-type-iri`.

(def ^:private rdfs "http://www.w3.org/2000/01/rdf-schema#")
(def ^:private owl  "http://www.w3.org/2002/07/owl#")
(def ^:private skos "http://www.w3.org/2004/02/skos/core#")

(defn- v [^String ns-iri ^String local] (ttl/iri (str ns-iri local)))

(def ^:private a-type      (v ttl/rdf-ns "type"))
(def ^:private a-first     (v ttl/rdf-ns "first"))
(def ^:private a-rest      (v ttl/rdf-ns "rest"))
(def ^:private a-nil       (v ttl/rdf-ns "nil"))
(def ^:private a-property  (v ttl/rdf-ns "Property"))

(def ^:private a-subclass  (v rdfs "subClassOf"))
(def ^:private a-subprop   (v rdfs "subPropertyOf"))
(def ^:private a-domain    (v rdfs "domain"))
(def ^:private a-range     (v rdfs "range"))
(def ^:private a-label     (v rdfs "label"))
(def ^:private a-comment   (v rdfs "comment"))
(def ^:private a-class     (v rdfs "Class"))
(def ^:private a-datatype  (v rdfs "Datatype"))

(def ^:private a-owl-class (v owl "Class"))
(def ^:private a-ontology  (v owl "Ontology"))
(def ^:private a-imports   (v owl "imports"))
(def ^:private a-disjoint  (v owl "disjointWith"))
(def ^:private a-all-disjoint (v owl "AllDisjointClasses"))
(def ^:private a-members   (v owl "members"))
(def ^:private a-eq-class  (v owl "equivalentClass"))
(def ^:private a-eq-prop   (v owl "equivalentProperty"))
(def ^:private a-inverse   (v owl "inverseOf"))
(def ^:private a-chain     (v owl "propertyChainAxiom"))
(def ^:private a-on-prop   (v owl "onProperty"))
(def ^:private a-all-values  (v owl "allValuesFrom"))
(def ^:private a-some-values (v owl "someValuesFrom"))
(def ^:private a-has-value   (v owl "hasValue"))
(def ^:private a-intersection (v owl "intersectionOf"))
(def ^:private a-union        (v owl "unionOf"))

(def property-metatypes
  "`rdf:type` objects that declare a **predicate** and, for most of them, a vaelii
  metadata predicate with it.  A nil value declares the role and nothing more."
  {(v owl "ObjectProperty")           nil
   (v owl "DatatypeProperty")         nil
   (v owl "AnnotationProperty")       nil
   (v owl "OntologyProperty")         nil
   a-property                         nil
   (v owl "TransitiveProperty")       'transitive
   (v owl "SymmetricProperty")        'symmetric
   (v owl "ReflexiveProperty")        'reflexive
   (v owl "AsymmetricProperty")       'asymmetric
   (v owl "IrreflexiveProperty")      nil
   (v owl "InverseFunctionalProperty") nil})

(def functional-metatypes
  "The `rdf:type` objects that map to vaelii's `functional`, behind `:functional?` —
  `owl:FunctionalProperty` constrains a property to one value, while vaelii's
  `functional` *merges* a second value into the first through the equality closure,
  which is not quite the same claim."
  {(v owl "FunctionalProperty") 'functional})

(def class-metatypes
  "`rdf:type` objects that declare a **type**."
  #{a-owl-class a-class a-datatype (v owl "Restriction") (v rdfs "Container")})

(def meta-types
  "Every `rdf:type` object that is a declaration about the vocabulary rather than a
  membership to store.  `(owlClass mammal)` says nothing a reader of the corpus wants."
  (into (set (keys property-metatypes))
        (concat (keys functional-metatypes) class-metatypes
                [a-ontology a-all-disjoint (v owl "AllDifferent")
                 (v owl "NamedIndividual") (v owl "Axiom") (v rdfs "Resource")])))

(def annotation-predicates
  "Predicates carrying a human-readable string rather than a claim about the world.
  They are the bulk of a real ontology's triples and none of its inference, which is why
  `:profiles` can drop them wholesale."
  {a-comment           'comment
   (v skos "definition") 'comment
   (v skos "scopeNote")  'comment
   a-label             'label
   (v skos "prefLabel")  'label
   (v skos "altLabel")   'label})

(def structural-predicates
  "Predicates that exist to hold an OWL construct together.  Every one is read by the
  axiom that owns it, so emitting it again as a plain fact would restate the axiom in a
  vocabulary nothing understands."
  #{a-first a-rest a-on-prop a-all-values a-some-values a-has-value
    a-intersection a-union a-members
    (v owl "onClass") (v owl "onDataRange") (v owl "withRestrictions")
    (v owl "distinctMembers") (v owl "annotatedSource") (v owl "annotatedProperty")
    (v owl "annotatedTarget")})

;;; ── pass 1: classify ──────────────────────────────────────────────────

(defn- note
  [acc k x]
  (if (ttl/iri? x) (update acc k (fnil conj #{}) x) acc))

(defn classify
  "Pass 1.  Fold every triple into `{evidence-key #{iri …}}` plus `:bnodes`, the triples
  whose subject is a blank node.

  The blank nodes are held in memory and the rest is streamed, which is the structure of
  the
  data rather than a compromise: an OWL construct is a small closed cluster of blank
  nodes, and a graph large enough to matter (a Wikidata or YAGO dump) has none at all."
  [triples]
  (reduce
   (fn [acc {:keys [s p o g]}]
     (let [acc (cond-> acc
                 (ttl/bnode? s) (update-in [:bnodes s] (fnil conj []) [p o])
                 ;; what *reaches* a blank node, as well as what hangs off it: a node's
                 ;; incoming edges are what decide whether it can be flattened into a
                 ;; tuple (one edge) or has to keep an identity of its own (several, or
                 ;; none) — see `bnode-plan`.
                 (ttl/bnode? o) (update-in [:bnode-refs o] (fnil conj []) [s p])
                 ;; A predicate that also takes a plain object cannot be flattened: some
                 ;; of its facts would come out at the tuple's arity and the rest at 2,
                 ;; and no one rule would match both.
                 (not (ttl/bnode? o)) (update :plain-object (fnil conj #{}) p)
                 g              (update :declared-context (fnil conj #{}) g))
           acc (-> acc (note :seen s) (note :seen o) (note :predicate p))]
       (condp = p
         a-type      (cond
                       (contains? property-metatypes o) (note acc :declared-predicate s)
                       (contains? functional-metatypes o) (note acc :declared-predicate s)
                       (contains? class-metatypes o)    (note acc :declared-type s)
                       ;; `:ontology` as well as `:declared-context`: an import names
                       ;; two contexts and only one of them is the file's own subject,
                       ;; which is what `convert!` needs to pick a default
                       (= a-ontology o)                 (-> acc
                                                            (note :declared-context s)
                                                            (note :ontology s))
                       :else                            (note acc :type o))
         a-subclass  (-> acc (note :type s) (note :type o))
         a-eq-class  (-> acc (note :type s) (note :type o))
         a-disjoint  (-> acc (note :type s) (note :type o))
         a-domain    (-> acc (note :declared-predicate s) (note :type o))
         a-range     (-> acc (note :declared-predicate s) (note :type o))
         a-subprop   (-> acc (note :declared-predicate s) (note :declared-predicate o))
         a-eq-prop   (-> acc (note :declared-predicate s) (note :declared-predicate o))
         a-inverse   (-> acc (note :declared-predicate s) (note :declared-predicate o))
         a-chain     (note acc :declared-predicate s)
         a-on-prop   (note acc :declared-predicate o)
         a-all-values  (note acc :type o)
         a-some-values (note acc :type o)
         a-imports   (-> acc (note :declared-context s) (note :declared-context o))
         acc)))
   {}
   triples))

(def role-precedence
  "`[role evidence-key]` in decreasing authority.

  A **graph or ontology IRI is a context** and never anything else.  A term used in
  **predicate position** is a predicate whatever else is claimed of it — the same rule
  the Cyc reader applies for the same reason: vaelii refuses a functor that is not
  spelled as one, so a wrong role there costs every triple about the term while a wrong
  role anywhere else costs only its appearance.  Then RDF's own `rdf:type`
  **declarations**, then **position** in a class-shaped axiom, and finally an
  **individual by residue** — which is what the overwhelming majority of a data graph's
  IRIs are."
  [[:context   :ontology]
   [:context   :declared-context]
   [:predicate :predicate]
   [:predicate :declared-predicate]
   [:type      :declared-type]
   [:type      :type]
   [:individual :seen]])

(def consumed-vocabulary
  "The RDF/RDFS/OWL IRIs a reading of this reader's own consumes — every axiom
  predicate, every metatype, every structural link, every annotation predicate.

  None of them is ever *named*: each is read into a vaelii sentence chosen by hand
  (`rdfs:subClassOf` becomes `genl`, `owl:Class` becomes nothing at all), so minting
  `subClassOf` as a vaelii predicate would put a name in `names.edn` that no sentence
  uses and invite a reader of the corpus to think it means something.  An RDF vocabulary
  term this reader has **no** reading for is deliberately not in here — `rdfs:seeAlso`
  gets a name and becomes an ordinary fact, which is the honest outcome."
  (into (set (concat structural-predicates meta-types
                     (keys annotation-predicates)
                     [a-type a-subclass a-subprop a-domain a-range a-imports
                      a-disjoint a-eq-class a-eq-prop a-inverse a-chain a-nil]))))

(defn local-part
  "An IRI's local part — after the last `#`, `/` or `:` — falling back to the whole IRI
  when that is empty."
  ^String [i]
  (let [s (ttl/iri-str i)]
    (or (some->> (re-find #"[^#/:]+$" s) not-empty) s)))

(defn name-table
  "The IRI -> vaelii term map, plus a term per **skolemized** blank node.  A term is
  named from its IRI's local part, and a blank node from the subject and predicate that
  reach it (`BobHasJob`), so a name is readable and says where it came from.  Built in
  sorted order so a collision resolves the same way on every run.

  IRIs go first, so a minted name is the one that takes a suffix when it collides with a
  name somebody actually wrote."
  ([role-map] (name-table role-map nil))
  ([role-map plan]
   (term/name-table
    (concat
     (for [i (sort-by str (keys role-map))
           :when (not (contains? consumed-vocabulary i))]
       [i (role-map i) (term/abbreviate (local-part i))])
     (for [[b base] (sort-by (comp str key) (:skolem plan))]
       [b :individual (term/abbreviate base)])))))

;;; ── what a blank node is for ──────────────────────────────────────────

(def structural-markers
  "Predicates whose presence on a blank node means an OWL axiom already reads it.  Such
  a node is not data and must not be flattened or named — the axiom that owns it has
  already said what it means."
  #{a-on-prop a-intersection a-union a-first a-rest a-members})

(defn structural-bnode?
  "Is `b` the interior of an OWL construct rather than a node standing for something?"
  [bnodes b]
  (boolean (some (fn [[p o]]
                   (or (contains? structural-markers p)
                       (and (= a-type p) (contains? meta-types o))))
                 (get bnodes b))))

(defn- typed-bnode?
  "Does `b` claim to be an instance of something?  A node with a type is a *thing* — the
  W3C n-ary pattern types its relation nodes, and Wikidata types its statements — and a
  thing needs a name to hang the membership on, which flattening would take away."
  [bnodes b]
  (boolean (some (fn [[p o]] (and (= a-type p) (not (contains? meta-types o))))
                 (get bnodes b))))

(defn tuple-signature
  "The qualifier predicates of `b` in a fixed order, or nil when `b` cannot be read as a
  tuple at all:

  * **a repeated qualifier** — two values in one slot is not one tuple, it is two;
  * **a nested blank node** — a slot whose filler has no name;
  * **no qualifiers** — a node that states nothing.

  The order is the qualifiers' own IRIs, sorted.  It is arbitrary, and being arbitrary
  is why it has to be *fixed*: every instance of a predicate must lay its arguments out
  the same way or a rule reading position 2 gets a role from one fact and a date from
  the next."
  [bnodes b]
  (let [pairs (get bnodes b)
        ps    (map first pairs)]
    (when (and (seq pairs)
               (apply distinct? ps)
               (not-any? (fn [[_ o]] (ttl/bnode? o)) pairs))
      (vec (sort-by str ps)))))

(defn bnode-plan
  "Decide what becomes of every blank node: `{:n-ary {predicate [qualifier …]} :skolem
  {bnode name-base}}`.

  RDF has only binary triples, so an n-ary relation is written as a node with one edge
  per argument — the W3C n-ary pattern, Wikidata's statements, schema.org's Role, and
  every `[ :city \"Austin\" ; :zip \"78701\" ]` anybody ever wrote.  vaelii predicates have
  no arity limit, so such a node **can** become one fact of higher arity, and that is a
  better reading than either dropping it or making the caller join through a node.

  It is only sound when the shape is **uniform**, and that is what this pass is for.
  Flattening positionally requires that every node a predicate reaches carries the same
  qualifiers, exactly once each, with nameable fillers, and is reached from exactly one
  place.  Miss any of those and the arguments silently misalign — `(hasJob Ann Beta
  1999)` where position 2 was supposed to be a role.  vaelii is open-world about arity
  until something declares it, so nothing would refuse the misaligned fact either; it
  would simply never match.

  Everything else is **skolemized**: the node gets a name and its triples are kept as
  ordinary facts about it.  That loses nothing — it is what the n-ary pattern already
  means — and it is what a node with an identity of its own (a typed one, a shared one)
  wants regardless.

  `:n-ary? false` skolemizes everything, which is the conservative reading and the one
  to reach for if a downstream rule set was written against the joined form."
  [bnodes bnode-refs plain-object opts]
  (let [data     (remove #(structural-bnode? bnodes %) (keys bnodes))
        ;; a node reached from exactly one place can be that place's tuple; one reached
        ;; from several is shared, and flattening would copy it rather than move it
        sole-ref (fn [b] (let [refs (distinct (get bnode-refs b))]
                           (when (= 1 (count refs)) (first refs))))
        candidate? (fn [b] (and (:n-ary? opts true)
                                (not (typed-bnode? bnodes b))
                                (some? (sole-ref b))
                                (some? (tuple-signature bnodes b))))
        by-pred  (group-by #(second (sole-ref %)) (filter candidate? data))
        n-ary    (into {}
                       (keep (fn [[p bs]]
                               (let [sigs (set (map #(tuple-signature bnodes %) bs))]
                                 ;; one signature across every node this predicate
                                 ;; reaches, and no plain object anywhere else, or none
                                 ;; of them flattens
                                 (when (and (= 1 (count sigs))
                                            (not (contains? plain-object p)))
                                   [p (first sigs)]))))
                       by-pred)
        flat     (into #{} (mapcat (fn [[p bs]] (when (n-ary p) bs))) by-pred)]
    {:n-ary  n-ary
     :skolem (into {}
                   (keep (fn [b]
                           (when-not (contains? flat b)
                             [b (if-let [[s p] (sole-ref b)]
                                  (str (if (ttl/bnode? s) (name s) (local-part s))
                                       " " (local-part p))
                                  (str "node " (name b)))])))
                   data)}))

;;; ── reading a blank node ──────────────────────────────────────────────

(defn- bn-get
  "The single object of `pred` on blank node `b`, or nil."
  [bnodes b pred]
  (some (fn [[p o]] (when (= p pred) o)) (get bnodes b)))

(defn rdf-list
  "The members of an `rdf:first` / `rdf:rest` chain starting at `node`, or nil when it is
  not a well-formed list.  Bounded by the number of cells so a cyclic `rdf:rest` — which
  a corrupt dump can carry — cannot spin."
  [bnodes node]
  (loop [n node, acc [], budget (inc (count bnodes))]
    (cond
      (= n a-nil)   acc
      (zero? budget) nil
      (nil? (get bnodes n)) nil
      :else
      (let [f (bn-get bnodes n a-first)
            r (bn-get bnodes n a-rest)]
        (if (or (nil? f) (nil? r))
          nil
          (recur r (conj acc f) (dec budget)))))))

(defn restriction
  "The OWL restriction blank node `b` states, as `{:on P :kind :all|:some|:value :filler
  X}` — or nil when `b` is not a restriction this reader has a Horn reading for (a
  cardinality, a data range, something malformed)."
  [bnodes b]
  (when-let [on (bn-get bnodes b a-on-prop)]
    ;; `some?` rather than a truthy test: `owl:hasValue false` is a restriction whose
    ;; filler is the boolean, and a truthy test would read it as an absent one.
    (or (let [x (bn-get bnodes b a-all-values)]  (when (some? x) {:on on :kind :all   :filler x}))
        (let [x (bn-get bnodes b a-some-values)] (when (some? x) {:on on :kind :some  :filler x}))
        (let [x (bn-get bnodes b a-has-value)]   (when (some? x) {:on on :kind :value :filler x})))))

;;; ── pass 2: translate ─────────────────────────────────────────────────

(defn- ->term
  "The vaelii term for an RDF term: a renamed IRI, a literal's value, a **skolemized**
  blank node's minted name — and nil for anything with no vaelii spelling, which after
  `bnode-plan` means a blank node that was flattened into its parent or read by an OWL
  axiom, and in either case has already been said elsewhere."
  [names x]
  (cond
    (ttl/iri? x)    (:term (names x))
    (ttl/tagged? x) (:lex x)
    (ttl/bnode? x)  (:term (names x))
    :else           x))

(defn- typed?  [names x role] (= role (:role (names x))))

(defn- literal? [x] (not (or (ttl/iri? x) (ttl/bnode? x))))

(defn- lang-of [x] (when (ttl/tagged? x) (:lang x)))

(defn- rule
  "An `implies` at monotonic strength — an OWL axiom is definitional, so there is no
  direction wrapper and no defeasibility to re-apply."
  [antecedents consequent]
  (list 'implies
        (if (next antecedents) (apply list 'and antecedents) (first antecedents))
        consequent))

(defn- class-rules
  "The sentences a `C <axiom> <blank node>` triple states, given the axiom's direction.

  `:sub` is `C ⊑ X` and `:equiv` is `C ≡ X`, and they keep **different halves** of the
  same construct — which is the whole reason they are read separately rather than
  `equivalentClass` being turned into two `subClassOf`s and handed to one function."
  [names bnodes direction c b]
  (let [ct (->term names c)
        t  #(->term names %)]
    ;; A blank node carrying `owl:onProperty` **is** a restriction, whether or not this
    ;; reader has a Horn form for it, so the unreadable ones are reported as the
    ;; restrictions they are rather than as an unrecognized expression.
    (if (some? (bn-get bnodes b a-on-prop))
      (let [{:keys [on kind filler]} (restriction bnodes b)
            p (t on) f (t filler)]
        (cond
          (nil? kind) [nil :unsupported-restriction]
          (nil? p) [nil :unnamed-property]
          ;; C ⊑ ∀P.D — every P-value of a C is a D.  A universal in the conclusion is
          ;; exactly a rule.
          (and (= :sub direction) (= :all kind) (symbol? f))
          [[(rule [(list ct '?x) (list p '?x '?y)] (list f '?y))] nil]

          ;; C ⊑ ∃P.{v} — a hasValue restriction names the value, so there is nothing to
          ;; invent and the existential is really a ground conclusion.
          (and (= :sub direction) (= :value kind) (some? f))
          [[(rule [(list ct '?x)] (list p '?x f))] nil]

          ;; C ≡ ∃P.D — worthless as a definition, and perfectly good as its
          ;; sufficient-condition half: anything with a P-value in D is a C.
          (and (= :equiv direction) (= :some kind) (symbol? f))
          [[(rule [(list p '?x '?y) (list f '?y)] (list ct '?x))] nil]

          (and (= :equiv direction) (= :value kind) (some? f))
          [[(rule [(list p '?x f)] (list ct '?x))] nil]

          (= :some kind) [nil :existential-superclass]
          (= :all kind)  [nil :universal-subclass]
          :else          [nil :unsupported-restriction]))

      ;; an intersection: `C ⊑ D1 ⊓ D2` gives both edges, and `C ≡ D1 ⊓ D2` gives the
      ;; sufficient condition as well
      (if-let [members (some->> (bn-get bnodes b a-intersection) (rdf-list bnodes))]
        (let [parts (keep t members)]
          (if (empty? parts)
            [nil :unnamed-intersection]
            [(cond-> (mapv #(list 'genl ct %) (filter symbol? parts))
               (= :equiv direction)
               (conj (rule (mapv #(list % '?x) (filter symbol? parts)) (list ct '?x))))
             nil]))

        ;; a union: only `D ⊑ C` for each member is Horn — the other direction is a real
        ;; disjunction and nothing here can write it
        (if-let [members (some->> (bn-get bnodes b a-union) (rdf-list bnodes))]
          (let [parts (filter symbol? (keep t members))]
            (if (empty? parts)
              [nil :unnamed-union]
              [(mapv #(list 'genl % ct) parts) nil]))
          [nil :unsupported-class-expression])))))

(defn- flatten-tuple
  "The one higher-arity fact a blank node stands for: `(p Subject q1 q2 …)`, its
  arguments in the signature's fixed order.  nil if any slot has no vaelii term, since a
  tuple with a hole in it is not the tuple the source wrote."
  [names bnodes signature p st b]
  (let [args (map #(->term names (bn-get bnodes b %)) signature)]
    (when (every? some? args)
      (apply list (:term (names p)) st args))))

(defn translate
  "The vaelii sentences one triple becomes: `{:sentences [...] :strength s}`, or
  `{:dropped reason}`.  `ctx` is `{:names :bnodes :plan :opts}` — pass 1's blank-node map
  and the plan for what becomes of each, which is what lets an axiom or an n-ary tuple
  be read here rather than deferred.

  A triple's **strength** follows what kind of claim it is: a schema axiom is
  definitional and lands `:monotonic`, an ordinary triple is somebody's assertion about
  the world and lands `:default`.  RDF itself draws no such line, so this one is ours —
  and it is the line that decides whether a later contradiction can retract a triple or
  is refused against it."
  [{:keys [s p o]} {:keys [names bnodes plan opts]}]
  (let [t     #(->term names %)
        st    (t s)
        ot    (t o)
        axiom (fn [& sentences] {:sentences (vec (remove nil? sentences)) :strength :monotonic})
        fact  (fn [& sentences] {:sentences (vec (remove nil? sentences)) :strength :default})]
    (cond
      ;; A blank node subject with no name was either read by the axiom that owns it or
      ;; folded into its parent's tuple.  Both have already been said; neither is a loss,
      ;; and they are counted apart so the report can tell them from a real drop.
      (and (ttl/bnode? s) (nil? st))
      {:dropped (if (structural-bnode? bnodes s) :structural :n-ary-member)}

      (nil? st)               {:dropped :unnamed-subject}
      (contains? structural-predicates p) {:dropped :structural}

      (= a-type p)
      (cond
        (contains? meta-types o)
        (if-let [meta-pred (or (get property-metatypes o)
                               (when (:functional? opts) (get functional-metatypes o)))]
          (axiom (list meta-pred st))
          {:dropped :vocabulary-declaration})
        (nil? ot)              {:dropped :unnamed-type}
        (not (symbol? ot))     {:dropped :literal-type}
        ;; `(c X)` — a type is a unary predicate, which is how vaelii states membership
        :else (fact (list ot st)))

      (= a-imports p)
      (if (and (typed? names s :context) (typed? names o :context))
        {:sentences [(list 'genlCx st ot)] :context :topology :strength :monotonic}
        {:dropped :unnamed-import})

      ;; ---- class axioms ------------------------------------------------
      (or (= a-subclass p) (= a-eq-class p))
      (let [direction (if (= a-subclass p) :sub :equiv)]
        (cond
          (ttl/bnode? o)
          (let [[sentences why] (class-rules names bnodes direction s o)]
            (if (seq sentences)
              {:sentences (vec sentences) :strength :monotonic}
              {:dropped (or why :unsupported-class-expression)}))
          (nil? ot)          {:dropped :unnamed-superclass}
          (= :sub direction) (axiom (list 'genl st ot))
          ;; equivalence is mutual subsumption, and both halves are Horn
          :else              (axiom (list 'genl st ot) (list 'genl ot st))))

      (= a-disjoint p) (if ot (axiom (list 'disjoint st ot)) {:dropped :unnamed-disjoint})

      ;; ---- property axioms ---------------------------------------------
      (= a-subprop p) (if ot (axiom (list 'genl st ot)) {:dropped :unnamed-superproperty})
      (= a-eq-prop p) (if ot (axiom (list 'genl st ot) (list 'genl ot st))
                          {:dropped :unnamed-superproperty})
      (= a-inverse p) (if ot (axiom (list 'inverse st ot)) {:dropped :unnamed-inverse})

      (or (= a-domain p) (= a-range p))
      (cond
        (nil? ot)                     {:dropped :unnamed-constraint}
        ;; a datatype range is a constraint on a value, and vaelii's `arg` names a
        ;; type of term — the claim does not survive the trip and is not faked
        (str/starts-with? (ttl/iri-str o) ttl/xsd) {:dropped :datatype-range}
        :else (axiom (list 'arg st (if (= a-domain p) 1 2) ot)))

      (= a-chain p)
      (let [members (some->> (rdf-list bnodes o) (keep t))]
        (if (and (seq members) (every? symbol? members))
          ;; P ∘ Q ⊑ R is a rule over a fresh variable per link in the chain
          (let [vars (mapv #(symbol (str "?x" %)) (range (inc (count members))))]
            {:sentences [(rule (vec (map-indexed (fn [i m] (list m (vars i) (vars (inc i))))
                                                 members))
                               (list st (first vars) (last vars)))]
             :strength :monotonic})
          {:dropped :unreadable-chain}))

      ;; ---- annotations --------------------------------------------------
      (contains? annotation-predicates p)
      (let [langs (:languages opts)
            l     (lang-of o)]
        (cond
          (not (literal? o))                        {:dropped :non-literal-annotation}
          (and l langs (not (contains? langs l)))   {:dropped :other-language}
          :else (fact (list (annotation-predicates p) st (ttl/lex o)))))

      ;; ---- everything else ----------------------------------------------
      (nil? (:term (names p)))  {:dropped :unnamed-predicate}

      ;; An n-ary relation, written the only way RDF can write one: a node with an edge
      ;; per argument.  It becomes one fact of that arity.
      (and (ttl/bnode? o) (get-in plan [:n-ary p]) (nil? ot))
      (if-let [sentence (flatten-tuple names bnodes (get-in plan [:n-ary p]) p st o)]
        (fact sentence)
        {:dropped :unnamed-tuple-slot})

      ;; An object with no vaelii spelling is one of two things, and they are not the
      ;; same news: a blank node this reader neither flattened nor named, or a term of
      ;; the vocabulary it consumes — `owl:Class` is deliberately never given a name, so
      ;; a triple whose object is one is not a blank node in any sense
      (nil? ot)                 {:dropped (if (ttl/bnode? o) :blank-node-object
                                              :vocabulary-object)}
      ;; the language filter is not an annotation-only concern: a data graph carries
      ;; multilingual string values under its own predicates too, and one term per
      ;; language of the same string is fifty facts saying one thing
      (let [l (lang-of o) langs (:languages opts)]
        (and l langs (not (contains? langs l)))) {:dropped :other-language}
      :else                     (fact (list (:term (names p)) st ot)))))

(def drop-kinds
  "What each of this reader's drop reasons **is** — see `corpus/drop-kinds`.  A reason
  missing here counts as `:unread`.

  The two that dominate any real graph are both `:restated`.  `X a owl:Class` is a
  vocabulary declaration, and the term, its role and its spelling are already in
  `names.edn` — the triple says nothing the corpus does not hold.  `rdf:first` /
  `rdf:rest` / `owl:annotatedSource` are the *plumbing* of a class expression or a
  reified axiom that `class-rules` read as a whole; counting the parts again after
  reading the assembly would be double entry."
  '{:vocabulary-declaration :restated
    :structural             :restated
    :n-ary-member           :restated
    :other-language         :filtered})

(def drop-flags
  "The convert option that keeps each `:filtered` drop — see `cyc/drop-flags` for the
  contract and `plugin-test` for what enforces it.

  `--languages` is the reversal rather than an on/off flag, because the policy is not
  \"drop these\" but \"keep this set\": the default is `en` plus every untagged literal,
  and any other set is one argument away.  On a multilingual dump the filter is not a
  nicety — fifty translations of one label are fifty facts saying one thing."
  {:other-language :languages})

(defn- disjoint-sets
  "The `owl:AllDisjointClasses` axioms in `bnodes`, as pairwise `(disjoint a b)`
  sentences.  They are blank nodes with no subject of their own, so nothing in the
  triple stream reaches them — this pass is how they are not silently lost."
  [names bnodes]
  (for [[b pairs] bnodes
        :when (some (fn [[p o]] (and (= p a-type) (= o a-all-disjoint))) pairs)
        :let [members (some->> (bn-get bnodes b a-members) (rdf-list bnodes))
              named   (filter symbol? (keep #(->term names %) members))]
        [x y] (for [i (range (count named)) j (range (inc i) (count named))]
                [(nth named i) (nth named j)])]
    (list 'disjoint x y)))

;;; ── converting ────────────────────────────────────────────────────────

(defn- source-context
  "The context a triple lands in: its named graph, else the ontology the file declares,
  else one named for the source.  A graph that says nothing about where its statements
  hold still has to put them somewhere, and a context named for the file is at least
  honest about being an artefact of the import."
  [names default {:keys [g]}]
  (or (when g (:term (names g))) default))

(defn convert!
  "Convert the RDF graph at `path` into a vaelii corpus under `out-dir`.  Two passes:
  the first classifies every IRI and holds the blank nodes, the second writes the
  sentences.  Returns the report map (also written as `report.edn`).

  `path` is any file `vaelii.foreign.turtle` reads — `.nt`, `.nq`, `.ttl`, and any of
  them `.gz`.

  Options: `:functional?` imports `owl:FunctionalProperty` as vaelii's `functional`
  (off); `:languages` keeps only these language tags on annotations (default `#{\"en\"}`,
  and an untagged literal is always kept); `:context` names the fallback context;
  `:limit` reads only the first n triples, for a quick sample."
  ([path out-dir] (convert! path out-dir {}))
  ([path out-dir opts]
   (let [opts  (merge {:languages #{"en"}} opts)
         limit (:limit opts)
         cap   (fn [xs] (if limit (take limit xs) xs))]
     (trove/log! {:level :info :id ::classify :msg "pass 1: classifying IRIs"})
     (let [evidence (with-triples path #(classify (cap %)))
           bnodes   (:bnodes evidence {})
           plan     (bnode-plan bnodes (:bnode-refs evidence {})
                                (:plain-object evidence #{}) opts)
           role-map (term/resolve-role role-precedence
                                       (dissoc evidence :bnodes :bnode-refs :plain-object))
           names    (name-table role-map plan)
           default  (or (:context opts)
                        ;; the ontology the file declares, if it declares exactly one.
                        ;; An *imported* ontology is a context too, and is not this one.
                        (let [os (keep #(:term (names %)) (:ontology evidence))]
                          (when (= 1 (count os)) (first os)))
                        (symbol (term/spell :context (term/abbreviate
                                                      (str (.getName (java.io.File. (str path)))
                                                           "Graph")))))]
       (trove/log! {:level :info :id ::translate
                    :msg (str "pass 2: translating (" (count names) " terms, "
                              (count bnodes) " blank nodes: "
                              (count (:n-ary plan)) " flattened predicates, "
                              (count (:skolem plan)) " named)")})
       (let [report
             (corpus/write!
              out-dir
              {:format       :vaelii-rdf-corpus/v1
               :source       path
               ;; `:languages` decides which facts survive, so a corpus that left it out
               ;; could not say how it was made.  Written as a sorted vector rather than
               ;; the set it is read as, so two runs of one conversion write one file.
               :options      (cond-> opts
                               (:languages opts) (update :languages #(vec (sort %))))
               :names        names
               :root-context (symbol (str "CxRdf" (str/replace (name default) #"\ACx" "")))
               ;; RDF is a syntax, not a source: what governs this corpus is whatever
               ;; governs the graph it was read from, and the converter cannot know
               ;; which that is.  Saying so beats guessing at a licence.
               :notice
               (str "RDF is a syntax and not a publisher, so the terms on this corpus\n"
                    "are the source graph's own and this converter cannot know them.\n"
                    "The usual ones: Wikidata is CC0, DBpedia and YAGO CC-BY-SA,\n"
                    "schema.org CC-BY-SA, BFO and most OBO Foundry OWL CC-BY.  Check\n"
                    "the source, and replace this paragraph with what it says.\n")}
              (fn [emit!]
                (let [counts  (atom {:read 0 :dropped 0})
                      dropped (atom {})
                      by-pred (atom {})]
                  (with-triples
                    path
                    (fn [ts]
                      (doseq [tr (cap ts)]
                        (swap! counts update :read inc)
                        (let [r (translate tr {:names names :bnodes bnodes
                                               :plan plan :opts opts})
                              k (when (ttl/iri? (:p tr)) (ttl/iri-str (:p tr)))]
                          (if-let [why (:dropped r)]
                            (do (swap! counts update :dropped inc)
                                (swap! dropped update why (fnil inc 0))
                                (swap! by-pred update-in [k :dropped] (fnil inc 0)))
                            (let [{:keys [sentences strength context]} r]
                              (swap! by-pred update-in [k :written] (fnil + 0) (count sentences))
                              (emit! (or context (source-context names default tr))
                                     strength sentences)))))))
                  ;; The disjointness axioms nothing points at, and the arity of every
                  ;; predicate a tuple was flattened into.  Declaring the arity is what
                  ;; makes a later fact at the wrong one a **refusal** rather than a
                  ;; sentence that quietly matches nothing — vaelii is open-world about
                  ;; arity until something says otherwise, and here something can.
                  (emit! default :monotonic (vec (disjoint-sets names bnodes)))
                  (emit! default :monotonic
                         (vec (for [[p signature] (sort-by (comp str key) (:n-ary plan))
                                    :let [term (:term (names p))]
                                    :when term]
                                (list 'arity term (inc (count signature))))))
                  {:source       (str path)
                   :triples      (:read @counts)
                   :dropped      (:dropped @counts)
                   :drop-reasons (into (sorted-map) @dropped)
                   :drop-kinds   drop-kinds
                   :blank-nodes  (count bnodes)
                   :skolemized   (count (:skolem plan))
                   ;; the argument order of every flattened predicate, which is arbitrary
                   ;; and therefore has to be written down somewhere a reader can find it
                   :n-ary        (into (sorted-map)
                                       (for [[p signature] (:n-ary plan)
                                             :let [term (:term (names p))]
                                             :when term]
                                         [term (mapv #(or (:term (names %)) (local-part %))
                                                     signature)]))
                   :iris         (frequencies (map :role (vals names)))
                   :by-predicate (into (sorted-map) @by-pred)})))]
         (trove/log! {:level :info :id ::converted
                      :msg (str "converted " (:triples report) " triples -> "
                                (:sentences report) " sentences in " (:contexts report)
                                " contexts (" (:dropped report) " dropped)")})
         report)))))

;;; ── loading ───────────────────────────────────────────────────────────

(def profiles
  "Named subsets of a converted graph.  `:ontology` drops the labels and comments, which
  on a real ontology are most of the triples and none of the inference; `:schema` keeps
  only the axioms, for loading a vocabulary without its instance data."
  {:full     {}
   :ontology {:drop-predicates '#{label comment}}
   :schema   {:drop-predicates '#{label comment}
              :keep-layers     #{:terms :hierarchy :schema}}})

(defn load-dir!
  "Load the corpus at `dir` into `kb` — `vaelii.foreign.corpus/load-dir!` with this
  format's `profiles`.  See there for the options and the layering."
  ([kb dir] (load-dir! kb dir {}))
  ([kb dir opts] (corpus/load-dir! kb dir profiles opts)))

(def reader
  "This format's reader, as the extension point (`vaelii.impl.foreign`) hands it out."
  {:name       "RDF/OWL graph"
   :load-dir!  load-dir!
   :convert!   convert!
   :profiles   profiles
   :drop-kinds drop-kinds
   :drop-flags drop-flags})
