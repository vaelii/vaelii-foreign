(ns vaelii.foreign.obo
  "Read an OBO-format ontology — the OBO Foundry's own stanza syntax — and translate it
  into a vaelii corpus.

  OBO is the format the life sciences standardized on: the Gene Ontology, ChEBI, Uberon,
  the Disease Ontology and about two hundred others, all CC-BY, all built by curators
  rather than generated.  A `.obo` file is a header followed by stanzas:

      [Term]
      id: GO:0000278
      name: mitotic cell cycle
      namespace: biological_process
      def: \"The cell cycle of a eukaryotic cell...\" [GOC:mah]
      is_a: GO:0007049 ! cell cycle
      relationship: part_of GO:0051301

      [Typedef]
      id: part_of
      is_transitive: true
      inverse_of: has_part

  which is why it gets its own reader rather than going through the RDF one: the same
  ontologies also publish an OWL serialization, and the OWL is *generated from* the OBO
  by a mapping that turns every `relationship:` into an existential restriction — which
  is precisely the OWL construct that has no Horn form.  Reading the OBO directly keeps
  the relations vaelii can hold.

  ## What comes across

  | OBO                                | vaelii                                        |
  |------------------------------------|-----------------------------------------------|
  | `[Term]`                           | a **type** (snake_case, named from `name:`)   |
  | `[Typedef]`                        | a **predicate** (camelCase, named from `id:`) |
  | `[Instance]`                       | an **individual**                             |
  | `namespace:`                       | the **context** the stanza's sentences land in |
  | `is_a: X`                          | `(genl this x)`                               |
  | `relationship: R X`                | `(r this x)`                                  |
  | `disjoint_from: X`                 | `(disjoint this x)`                           |
  | `intersection_of:` (all plain)     | both `genl` edges **and** the sufficient-condition rule |
  | `union_of: X`                      | `(genl x this)`, each member                  |
  | `domain:` / `range:`               | `(argIsa p 1 c)` / `(argIsa p 2 c)`           |
  | `is_transitive:` and its siblings  | `(transitive p)`, `(symmetric p)`, …          |
  | `inverse_of: Q`                    | `(inverse p q)`                               |
  | `holds_over_chain: A B`            | `(implies (and (a ?x ?y) (b ?y ?z)) (p ?x ?z))` |
  | `transitive_over: R`               | `(implies (and (p ?x ?y) (r ?y ?z)) (p ?x ?z))` |
  | `def:` / `comment:`                | `(comment t \"…\")`                             |
  | `name:` / `synonym:` / `xref:`     | `(label t \"…\")`, `(synonym t \"…\")`, `(xref t \"…\")` |

  ## What `relationship:` means here, and what it does not

  `relationship: part_of GO:0051301` is read as the **class-level fact** `(partOf
  mitotic_cell_cycle cell_division)`.  That is what the OBO tooling means by it in
  practice and what every browser shows, but it is *not* what the OWL mapping says: there
  it is `MitoticCellCycle ⊑ ∃part_of.CellDivision`, an existential restriction on each
  instance.  The two agree about the ontology's shape and disagree about what follows for
  an individual, and only the first has a form vaelii can store.  Taking it is a
  deliberate weakening, recorded here so nobody has to rediscover it from the output.

  **An obsolete term is dropped**, with its stanza counted, unless `:obsolete? true`.
  OBO never deletes: a retired term keeps its id, gains `is_obsolete: true`, and loses
  its `is_a` edges, so importing one gives a term with no place in the hierarchy and a
  name that collides with its replacement's."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [taoensso.trove :as trove]
            [vaelii.foreign.corpus :as corpus]
            [vaelii.foreign.term :as term]))

;;; ── the lexer ─────────────────────────────────────────────────────────

(defn unescape
  "OBO's backslash escapes.  `\\W` is a space, which is the one that surprises: a tag
  value is whitespace-delimited, so a space inside one has to be written."
  ^String [^String s]
  (if-not (str/includes? s "\\")
    s
    (let [sb (StringBuilder.) n (count s)]
      (loop [i 0]
        (if (>= i n)
          (str sb)
          (let [c (.charAt s i)]
            (if (and (= \\ c) (< (inc i) n))
              (do (.append sb (case (.charAt s (inc i))
                                \n \newline \t \tab \W \space
                                \r \return \f \formfeed
                                (.charAt s (inc i))))
                  (recur (+ i 2)))
              (do (.append sb c) (recur (inc i))))))))))

(defn- strip-comment
  "A tag value without its trailing `! …` comment.  A `!` inside quotes or escaped is
  content — `xref: KEGG:C00001 ! water` has one and `def: \"… ! …\"` does not."
  ^String [^String s]
  (let [n (count s)]
    (loop [i 0, in-quote false]
      (cond
        (>= i n) s
        (= \\ (.charAt s i)) (recur (+ i 2) in-quote)
        (= \" (.charAt s i)) (recur (inc i) (not in-quote))
        (and (= \! (.charAt s i)) (not in-quote)) (str/trimr (subs s 0 i))
        :else (recur (inc i) in-quote)))))

(defn- strip-qualifiers
  "A tag value without its trailing `{name=\"value\", …}` block.

  **Every** OBO tag may carry one, and dropping it is not a loss of content this reader
  could otherwise use: the qualifiers say things like `all_only=\"true\"` — that a
  relationship holds of *all* instances and only those — which is a cardinality-shaped
  claim with no Horn reading, exactly like the `owl:minCardinality` the RDF reader
  refuses.  What matters is that the qualifier not be mistaken for part of the value.
  The Relations Ontology writes 19 of its `relationship:` and `holds_over_chain:` lines
  this way, and reading them as three words rather than two made every one of them look
  malformed."
  ^String [^String s]
  (let [t (str/trimr s)]
    (if (str/ends-with? t "}")
      (if-let [i (str/last-index-of t "{")] (str/trimr (subs t 0 (long i))) t)
      t)))

(defn quoted-value
  "The `\"…\"` head of a value like `def: \"text\" [refs]` or `synonym: \"text\" EXACT []`,
  or nil when the value does not start with one."
  [^String s]
  (when (str/starts-with? s "\"")
    (let [n (count s)]
      (loop [i 1, sb (StringBuilder.)]
        (cond
          ;; a value whose closing quote never arrives is still the text it held: the
          ;; escapes are unescaped either way, so one line does not read differently
          ;; from the next because a curator left a quote off
          (>= i n)             (unescape (str sb))
          (= \\ (.charAt s i)) (do (.append sb (.charAt s i))
                                   (.append sb (.charAt s (min (inc i) (dec n))))
                                   (recur (+ i 2) sb))
          (= \" (.charAt s i)) (unescape (str sb))
          :else                (do (.append sb (.charAt s i)) (recur (inc i) sb)))))))

(defn stanzas
  "The stanzas of an OBO file, as a lazy seq of `{:type \"Term\" :tags {tag [value …]}}`
  maps — the header first, as a stanza of type nil.

  Tag values keep their order and repeat: `is_a` appears once per parent, and reading
  only the last would silently make the hierarchy a tree."
  [lines]
  (letfn [(step [ty tags ls]
            (lazy-seq
             (if-let [line (first ls)]
               (let [l (str/trim line)]
                 (cond
                   (or (str/blank? l) (str/starts-with? l "!"))
                   (step ty tags (rest ls))

                   (and (str/starts-with? l "[") (str/ends-with? l "]"))
                   (cons {:type ty :tags tags}
                         (step (subs l 1 (dec (count l))) {} (rest ls)))

                   :else
                   (if-let [i (str/index-of l ":")]
                     (let [tag (subs l 0 i)
                           val (-> (subs l (inc i)) str/trim strip-comment
                                   strip-qualifiers str/trim)]
                       (step ty (update tags tag (fnil conj []) val) (rest ls)))
                     (step ty tags (rest ls)))))
               (list {:type ty :tags tags}))))]
    (step nil {} lines)))

(defn tag
  "The first value of `t`, or nil."
  [stanza t] (first (get (:tags stanza) t)))

(defn tags
  "Every value of `t`, in file order."
  [stanza t] (get (:tags stanza) t []))

(defn true-tag?
  "Is boolean tag `t` present and `true`?  OBO writes a flag as the string, and an
  absent flag and an explicit `false` mean the same thing."
  [stanza t]
  (= "true" (tag stanza t)))

(defn with-stanzas
  "Open the OBO file at `path`, call `(f stanzas)`, close the file after.  `.gz` is
  decompressed on the way through.  The seq is only valid inside `f`."
  [path f]
  (with-open [in (io/input-stream path)]
    (let [in (if (str/ends-with? (str/lower-case (str path)) ".gz")
               (java.util.zip.GZIPInputStream. in)
               in)]
      (with-open [r (io/reader in)]
        (f (stanzas (line-seq r)))))))

;;; ── pass 1: names ─────────────────────────────────────────────────────

(def ^:private stanza-roles
  {"Term" :type "Typedef" :predicate "Instance" :individual})

(defn obsolete?
  "Has this stanza been retired?  OBO never deletes — see the namespace docstring for
  what importing one would cost."
  [stanza]
  (true-tag? stanza "is_obsolete"))

(defn- two-word
  "A `relationship: part_of GO:1` / `holds_over_chain: A B` value as `[first second]`,
  or nil when it is not two whitespace-separated words."
  [^String s]
  (let [parts (str/split (str/trim s) #"\s+")]
    (when (= 2 (count parts)) parts)))

(defn- relations-used
  "The relation ids a stanza *references*, whether or not the file declares them.

  It usually does not.  An OBO ontology names relations by id across ontology
  boundaries — `part_of` is RO's, not the Gene Ontology's — and the OWL product resolves
  them through `owl:imports` rather than by restating them.  So a reader that only named
  a relation it had seen a `[Typedef]` for would be wrong about most of the Foundry:
  `uo.obo` declares **no** typedefs at all and still uses `has:prefix` eighty times, with
  a target that is right there in the file."
  [stanza]
  (concat (keep #(first (two-word %)) (tags stanza "relationship"))
          (keep #(first (two-word %)) (tags stanza "intersection_of"))
          (keep #(first (two-word %)) (tags stanza "property_value"))
          (mapcat #(or (two-word %) ()) (tags stanza "holds_over_chain"))
          (tags stanza "transitive_over")
          (tags stanza "disjoint_over")
          (tags stanza "inverse_of")))

(defn classify
  "Pass 1.  `{:entries [[id role base] …] :contexts #{namespace …} :header stanza}` —
  everything pass 2 needs that it cannot see one stanza at a time.

  A `[Term]` is named from its `name:` and a `[Typedef]` from its `id:`, and that
  asymmetry is the formats': a term's id is an opaque accession (`GO:0000278`) and its
  name is what every tool shows, while a typedef's id *is* its name (`part_of`) and its
  `name:` is a human phrase (`part of`) that would spell to the same thing anyway.

  `:used-relations` is every relation id referenced anywhere — see `relations-used` for
  why those have to be named even though nothing here declares them."
  [stanzas opts]
  (reduce
   (fn [acc stanza]
     (let [ty (:type stanza)
           id (tag stanza "id")]
       (cond
         (nil? ty)   (assoc acc :header stanza)
         (nil? id)   (update acc :skipped (fnil inc 0))
         (and (obsolete? stanza) (not (:obsolete? opts)))
         (update acc :obsolete (fnil inc 0))
         :else
         (let [role (stanza-roles ty :individual)
               base (if (= :predicate role) id (or (tag stanza "name") id))]
           (cond-> (-> acc
                       (update :entries (fnil conj []) [id role (unescape base)])
                       (update :used-relations (fnil into #{}) (relations-used stanza)))
             (tag stanza "namespace")
             (update :namespaces (fnil conj #{}) (tag stanza "namespace")))))))
   {}
   stanzas))

(defn name-table
  "The OBO id -> vaelii term map, plus a context term per namespace.  Sorted by id so a
  collision — two terms sharing a `name:`, which every large ontology has — resolves the
  same way on every run.

  Relations referenced but never declared are named last, from their ids.  Last, so a
  declared term always wins a collision against one; named at all, because otherwise
  every fact stated with an imported relation is dropped."
  [{:keys [entries namespaces used-relations]}]
  (let [declared (set (map first entries))
        imported (sort (remove declared used-relations))]
    (term/name-table
     (concat (for [[id role base] (sort-by first entries)] [id role (term/abbreviate base)])
             (for [ns (sort namespaces)] [[:namespace ns] :context ns])
             (for [id imported] [id :predicate (term/abbreviate id)])))))

;;; ── pass 2: translate ─────────────────────────────────────────────────

(defn- rule [antecedents consequent]
  (list 'implies
        (if (next antecedents) (apply list 'and antecedents) (first antecedents))
        consequent))

(def ^:private relation-flags
  "`is_*: true` tags that are vaelii predicate metadata."
  {"is_transitive" 'transitive "is_symmetric" 'symmetric "is_reflexive" 'reflexive
   "is_anti_symmetric" 'asymmetric})

(def ^:private annotation-tags
  "Tags carrying a human-readable string, and the vaelii predicate each becomes.  A
  `synonym:` and a `def:` carry their text in a `\"…\"` head, the rest carry it bare."
  {"name" 'label "def" 'comment "comment" 'comment "synonym" 'synonym
   "xref" 'xref "alt_id" 'altId "subset" 'subset "replaced_by" 'replacedBy
   "consider" 'consider "created_by" 'createdBy "creation_date" 'creationDate})

(defn translate
  "The sentences one stanza becomes: `{:context C :sentences [[strength sentence] …]
  :drops {reason n}}`.

  A sentence carries its **own** strength rather than the stanza doing so, because an
  OBO stanza states both kinds at once: `is_a` is definitional and `synonym` is a note
  somebody attached, and giving both the same strength would either make a label
  irretractable or make the hierarchy defeasible."
  [stanza names default-context opts]
  (let [id    (tag stanza "id")
        self  (:term (names id))
        t     #(:term (names %))
        ctx   (or (some->> (tag stanza "namespace") (vector :namespace) names :term)
                  default-context)
        out   (volatile! [])
        add!  (fn [strength s] (when s (vswap! out conj [strength s])))
        axiom (partial add! :monotonic)
        fact  (partial add! :default)
        drops (volatile! {})
        drop! (fn [why] (vswap! drops update why (fnil inc 0)))]
    (when self
      ;; ---- the hierarchy ------------------------------------------------
      (doseq [p (tags stanza "is_a")]
        (if-let [x (t p)] (axiom (list 'genl self x)) (drop! :unknown-parent)))

      (doseq [d (tags stanza "disjoint_from")]
        (if-let [x (t d)] (axiom (list 'disjoint self x)) (drop! :unknown-disjoint)))

      (doseq [u (tags stanza "union_of")]
        ;; each member of a union is a subclass of it; the other direction is a real
        ;; disjunction and there is nothing here to write it as
        (if-let [x (t u)] (axiom (list 'genl x self)) (drop! :unknown-union-member)))

      ;; `intersection_of` is a definition: `C ≡ D1 ⊓ D2 ⊓ …`.  Both halves are Horn
      ;; when every member is a plain class — the edges one way, the rule the other.  A
      ;; member with a relation (`intersection_of: part_of GO:1`) is an existential and
      ;; only its genl half survives.
      (let [members (tags stanza "intersection_of")
            plain   (keep #(when-not (two-word %) (t %)) members)]
        (doseq [m members]
          (if-let [[r target] (two-word m)]
            (if (and (t r) (t target))
              ;; `intersection_of: part_of GO:1` is `C ≡ … ⊓ ∃part_of.GO:1`.  The fact is
              ;; written — this is a *weakening*, not a loss — but the biconditional it
              ;; came from is not, so it is counted rather than passing silently.
              (do (fact (list (t r) self (t target))) (drop! :existential-intersect))
              (drop! :unknown-intersect))
            (when-not (t m) (drop! :unknown-intersect))))
        (doseq [x plain] (axiom (list 'genl self x)))
        (when (and (seq plain) (= (count plain) (count members)))
          (axiom (rule (mapv #(list % '?x) plain) (list self '?x)))))

      ;; ---- relations ----------------------------------------------------
      (doseq [r (tags stanza "relationship")]
        (if-let [[rel target] (two-word r)]
          (if (and (t rel) (t target))
            (axiom (list (t rel) self (t target)))
            (drop! :unknown-relationship))
          (drop! :malformed-relationship)))

      ;; ---- what a Typedef declares ---------------------------------------
      (when (= "Typedef" (:type stanza))
        (doseq [[tg pred] relation-flags]
          (when (true-tag? stanza tg) (axiom (list pred self))))
        (when (and (:functional? opts) (true-tag? stanza "is_functional"))
          (axiom (list 'functional self)))
        (when-let [x (some-> (tag stanza "inverse_of") t)] (axiom (list 'inverse self x)))
        (when-let [x (some-> (tag stanza "domain") t)]     (axiom (list 'argIsa self 1 x)))
        (when-let [x (some-> (tag stanza "range") t)]      (axiom (list 'argIsa self 2 x)))
        ;; `holds_over_chain: A B` is `A ∘ B ⊑ this`, and `transitive_over: R` is
        ;; `this ∘ R ⊑ this` — the same rule with one link already named
        (doseq [c (tags stanza "holds_over_chain")]
          (if-let [[a b] (two-word c)]
            (if (and (t a) (t b))
              (axiom (rule [(list (t a) '?x '?y) (list (t b) '?y '?z)] (list self '?x '?z)))
              (drop! :unknown-chain))
            (drop! :malformed-chain)))
        (doseq [r (tags stanza "transitive_over")]
          (if-let [x (t r)]
            (axiom (rule [(list self '?x '?y) (list x '?y '?z)] (list self '?x '?z)))
            (drop! :unknown-chain))))

      ;; ---- what an Instance declares --------------------------------------
      (when (= "Instance" (:type stanza))
        (doseq [i (tags stanza "instance_of")]
          (if-let [x (t i)] (fact (list x self)) (drop! :unknown-instance-type)))
        (doseq [pv (tags stanza "property_value")]
          (if-let [[r value] (two-word pv)]
            (if-let [x (t r)]
              ;; the value is a term id when the ontology declares one, and a literal
              ;; otherwise — `property_value: part_of TT:2` and `property_value: shown_in
              ;; "a figure"` are both legal and mean different things
              (fact (list x self (or (t value) (quoted-value value) (unescape value))))
              (drop! :unknown-property))
            (drop! :malformed-property-value))))

      ;; ---- annotations ----------------------------------------------------
      ;; `def:` and `synonym:` carry their text in a `"…"` head followed by a bracketed
      ;; reference list; the rest carry it bare and keep their spaces — a `name:` read
      ;; up to the first space would turn \"mitotic cell cycle\" into \"mitotic\".
      (doseq [[tg pred] annotation-tags
              value (tags stanza tg)
              :let [text (or (quoted-value value) (unescape value))]
              :when (not (str/blank? text))]
        (fact (list pred self text))))
    {:context ctx :sentences @out :drops @drops}))

;;; ── converting ────────────────────────────────────────────────────────

(defn- header-context
  "The context an ontology's own header names — its `ontology:` id, else its
  `default-namespace:`, else a name built from the file."
  [header path]
  (symbol (term/spell :context
                      (term/abbreviate
                       (or (some-> header (tag "ontology"))
                           (some-> header (tag "default-namespace"))
                           (str/replace (.getName (java.io.File. (str path))) #"\.obo(\.gz)?$" ""))))))

(def drop-kinds
  "What each of this reader's drop reasons **is** — see `corpus/drop-kinds`.  A reason
  missing here counts as `:unread`.

  The two named here would each read as a loss without a kind, and neither is one.
  `:obsolete` is a **policy**: the stanza is well-formed, this reader declines to import
  a term the ontology has retired, and `--obsolete` imports it after all.  PATO retires
  919 of its 2,820 stanzas, so a clean conversion of it reads as a third broken until
  the kind says otherwise.  `:existential-intersect` is a **weakening**: the fact is
  written and only the biconditional is lost, so `uo.obo` reports 81 drops of 574
  stanzas while losing nothing at all.  What is left is `:unread` and every one of those
  is a name the ontology uses and never declares."
  '{:obsolete              :filtered
    :existential-intersect :weakened})

(def drop-flags
  "The convert option that keeps each `:filtered` drop — see `cyc/drop-flags` for the
  contract and `plugin-test` for what enforces it.

  One entry, and it is the clearest case in the repo: an obsolete term is well-formed
  OBO that this reader declines to import, `--obsolete` imports it, and the only cost of
  saying yes is a term with no place in the hierarchy and a name that collides with its
  replacement's."
  {:obsolete :obsolete?})

(defn convert!
  "Convert the OBO file at `path` into a vaelii corpus under `out-dir`.  Two passes: the
  first names every stanza, the second writes the sentences.  Returns the report map
  (also written as `report.edn`).

  Options: `:obsolete?` keeps obsolete terms (off — see the namespace docstring);
  `:functional?` imports `is_functional` as vaelii's `functional` (off); `:limit` reads
  only the first n stanzas."
  ([path out-dir] (convert! path out-dir {}))
  ([path out-dir opts]
   (let [limit (:limit opts)
         cap   (fn [xs] (if limit (take limit xs) xs))]
     (trove/log! {:level :info :id ::classify :msg "pass 1: naming stanzas"})
     (let [evidence (with-stanzas path #(classify (cap %) opts))
           names    (name-table evidence)
           root     (header-context (:header evidence) path)]
       (trove/log! {:level :info :id ::translate
                    :msg (str "pass 2: translating (" (count names) " terms)")})
       (let [report
             (corpus/write!
              out-dir
              {:format       :vaelii-obo-corpus/v1
               :source       path
               :options      opts
               :names        names
               :root-context root
               :notice
               (str "An OBO Foundry ontology, translated.  Foundry principle 1 requires\n"
                    "an open licence and in practice that is CC-BY 4.0 -- the Gene\n"
                    "Ontology, ChEBI, Uberon and the Disease Ontology all are -- so this\n"
                    "corpus normally carries an attribution obligation to the source\n"
                    "ontology and its consortium.  The source's own header names it;\n"
                    "check there and replace this paragraph with what it says.\n")}
              (fn [emit!]
                ;; No `:dropped` tally beside these: a stanza that got no name was
                ;; already counted by pass 1, under `:obsolete` or `:no-id`, and a second
                ;; count of the same thing is the one kind of number this report must not
                ;; carry.
                (let [counts  (atom {:stanzas 0})
                      dropped (atom {:obsolete (:obsolete evidence 0)
                                     :no-id    (:skipped evidence 0)})]
                  (with-stanzas
                    path
                    (fn [ss]
                      (doseq [stanza (cap ss)
                              :when (:type stanza)]
                        (swap! counts update :stanzas inc)
                        (let [{:keys [context sentences drops]}
                              (translate stanza names root opts)]
                          (swap! dropped #(merge-with + % drops))
                          ;; one emit per strength: the corpus splits its files that way,
                          ;; and a stanza states both kinds
                          (doseq [[strength group] (group-by first sentences)]
                            (emit! context strength (mapv second group)))))))
                  {:source       (str path)
                   :stanzas      (:stanzas @counts)
                   :dropped      (reduce + (vals @dropped))
                   :drop-reasons (into (sorted-map) (remove (comp zero? val)) @dropped)
                   :drop-kinds   drop-kinds
                   :terms        (frequencies (map :role (vals names)))})))]
         (trove/log! {:level :info :id ::converted
                      :msg (str "converted " (:stanzas report) " stanzas -> "
                                (:sentences report) " sentences in " (:contexts report)
                                " contexts")})
         report)))))

;;; ── loading ───────────────────────────────────────────────────────────

(def profiles
  "Named subsets.  `:ontology` drops the curation trail and the lexical layer, which on
  the Gene Ontology is over half the sentences and none of the inference; `:taxonomy`
  keeps only the hierarchy and the relation declarations."
  {:full     {}
   :ontology {:drop-predicates '#{label synonym xref altId subset consider replacedBy
                                  createdBy creationDate}}
   :taxonomy {:drop-predicates '#{label synonym xref altId subset consider replacedBy
                                  createdBy creationDate comment}
              :keep-layers     #{:terms :hierarchy :schema}}})

(defn load-dir!
  "Load the corpus at `dir` into `kb` — `vaelii.foreign.corpus/load-dir!` with this
  format's `profiles`."
  ([kb dir] (load-dir! kb dir {}))
  ([kb dir opts] (corpus/load-dir! kb dir profiles opts)))

(def reader
  "This format's reader, as the seam (`vaelii.impl.foreign`) hands it out."
  {:name       "OBO ontology"
   :load-dir!  load-dir!
   :convert!   convert!
   :profiles   profiles
   :drop-kinds drop-kinds
   :drop-flags drop-flags})
