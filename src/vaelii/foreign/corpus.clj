(ns vaelii.foreign.corpus
  "The corpus — what every reader in this repo converts **to**, and the one thing that
  loads it.

  A translated ontology is not a dump and not a jar resource: it is a directory of plain
  vaelii sentence files, one s-expression per line, partitioned by context.

      meta.edn                     format, source, counts, context load order
      names.edn                    foreign term -> vaelii term, with its role
      report.edn                   what converted, what dropped, and why
      NOTICE                       whose knowledge this is, and on what terms
      kb/Topology.txt          the genlCx wiring of every context
      kb/Cx<C>.txt             that context's `:default`-strength sentences
      kb/Cx<C>.monotonic.txt   its `:monotonic` ones

  **The `NOTICE` is not decoration.**  A translated ontology is a *reformulation* of
  somebody else's knowledge, and every source this repo reads attaches attribution terms
  that survive one — OpenCyc says so outright, extending its licence to \"renamings and
  other logically equivalent reformulations … in any formal language\".  So the
  obligation travels with the corpus and not with this code, which means it has to be
  written into the directory: a corpus is data on a filesystem, and the notice is what
  keeps it attributable once somebody has copied it somewhere else.

  **One format, five converters.**  Cyc, RDF, WordNet, OBO and ATOMIC disagree about
  everything except the shape of the answer, so the answer is where they are made to
  agree: a corpus says nothing about where it came from beyond `meta.edn`'s `:format`
  line, and `load-dir!` never asks.  That is worth more than tidiness — vaelii's own
  catalog recognizes a corpus by `meta.edn` holding a `:context-order` and loads it
  through one reader, so a directory any of these converters wrote is a directory the
  catalog already knows how to open.

  The files are read off the **filesystem**, not the classpath: a converted corpus is
  data beside a KB directory, not shipped schema, and the interesting ones do not fit
  in a jar.

  ## Why loading is layered

  `load-dir!` does not read the files in the order they were written.  It reads the
  whole corpus five times — term definitions, hierarchy, the rest of the schema, type
  memberships, then everything else — and the reason is not speed:

  * term definitions first is an **identity** argument.  `(reifiableFunction F)` and
    `(termOfUnit K E)` decide which constant a non-atomic term reifies to, and every
    later sentence mentioning that term is reified against them as they stand then.
    Arriving late, they mint a second constant for an expression that already has one —
    or miss the reification altogether.

  * `genl` edges next is a **cost** argument.  An edge retires the memo of the cached
    transitive closure and everything else reads that closure back, so interleaved, each
    fact pays for a closure the next edge is about to retire.

  * memberships before facts is a **correctness** argument.  `arg` is open-world
    about an argument with no type at all and closed about one that has any, so a fact
    checked before its arguments' memberships have arrived is refused on a partial
    answer — and the same corpus in a different order would keep a different set of
    sentences.  Loading every membership first is what makes \"what types does this term
    have\" complete before anything asks.

  A refusal is **counted, not swallowed**: which of vaelii's checks a foreign ontology
  trips is the most useful thing an import has to say about it."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [taoensso.trove :as trove]
            [vaelii.core :as v]
            [vaelii.impl.disk.durability :as dur]
            [vaelii.impl.naming :as nm])
  (:import (java.io File PushbackReader Writer)))

;;; ── what a drop is ────────────────────────────────────────────────────

(def drop-kinds
  "The four things a counted drop can be, worst last.

  A reason **name** says what was refused; it does not say whether refusing it lost
  anything, and summing four unlike things into one `:dropped` figure is how a report
  ends up alarming in the wrong direction.  On OpenCyc's OWL export 98.5% of the drops
  are `:restated` — `X a owl:Class` when the term and its role are already in
  `names.edn` — so a reader that reported only the total would look like it lost a
  twentieth of the graph and would be ignored, taking the tenth of a percent that
  matters with it.

  * `:restated`  said elsewhere in the corpus.  Nothing is lost, and there is nothing
                 to fix.
  * `:filtered`  deliberately not imported: an obsolete term, a language nobody asked
                 for, executable code, an editorial note.  A policy, reversible by a
                 flag wherever one exists.
  * `:weakened`  read in part.  The sentence *was* written; what the source said and
                 what the corpus holds are not the same claim.
  * `:unread`    a claim with no reading here.  **The only number that counts against
                 the reader**, and the one to drive down or defend reason by reason.

  A reason no reader classifies counts as `:unread`, so a new drop is guilty until its
  author says otherwise."
  [:restated :filtered :weakened :unread])

(defn drop-summary
  "`{:drops {kind {reason count}} :unread n}` for `reasons` (a `reason -> count` map)
  under `kinds` (a reader's `reason -> kind` table).  Kinds with nothing in them are
  left out, so a clean conversion's report says so by being short."
  [reasons kinds]
  (let [by-kind (reduce-kv (fn [m reason n]
                             (if (zero? (long n))
                               m
                               (assoc-in m [(get kinds reason :unread) reason] n)))
                           {} (or reasons {}))]
    {:drops  (into (sorted-map)
                   (for [k drop-kinds :when (seq (get by-kind k))]
                     [k (into (sorted-map) (get by-kind k))]))
     :unread (reduce + 0 (vals (get by-kind :unread)))}))

;;; ── writing ───────────────────────────────────────────────────────────

(defn sentence-line
  "One sentence, printed so `clojure.edn/read` reads back exactly what was written."
  ^String [sentence]
  (binding [*print-length* nil *print-level* nil *print-readably* true]
    (pr-str sentence)))

(defn- inside
  "`fname` resolved against `dir`, or a refusal if it lands anywhere but directly in it.

  A file name here is a context name, and every reader mints those through `term/spell`,
  which is alnum-only.  `--context` is the one a person supplies, so this is the check
  that a corpus writes only inside itself however the name arrived."
  ^File [^File dir ^String fname]
  (let [f (io/file dir fname)]
    (if (= (.getCanonicalFile dir) (.getCanonicalFile (.getParentFile f)))
      f
      (throw (ex-info (str "context name would write outside the corpus: " (pr-str fname))
                      {:type :corpus/escaping-name :name fname :dir (str dir)})))))

(defn- pooled-writers
  "A bounded pool of append writers keyed by file name, so a corpus with more context
  files than the process may hold open still writes in one streaming pass.  Returns
  `[get-writer close-all]`; `get-writer` reopens a file it evicted, which is why every
  file is truncated up front.

  Which open file is evicted is arbitrary, and the pool spends nothing on choosing a
  better one: eviction costs a reopen and nothing else, since every file is truncated up
  front and reopened in append mode."
  [^File dir cap]
  (let [open (atom {})]
    [(fn [^String fname]
       (or (get @open fname)
           (let [_ (when (>= (count @open) (long cap))
                     (let [[victim ^Writer w] (first @open)]
                       (.close w)
                       (swap! open dissoc victim)))
                 f (inside dir fname)
                 w (io/writer f :append (.exists f))]
             (swap! open assoc fname w)
             w)))
     (fn [] (run! (fn [[_ ^Writer w]] (.close w)) @open) (reset! open {}))]))

(defn general-first
  "The nodes of a `[sub super]` edge set, **most general first** — a topological order,
  with any cycle broken by name so the result is total.

  Contexts load in this order so a context's supercontexts, and the vocabulary its checks
  read through them, are in place before its own sentences arrive.  The topology file is
  written in it too, which is only a readability choice: what an edge costs to assert is
  the engine's business, and `with-deferred-settle` makes it independent of the order
  they arrive in."
  [edges contexts]
  (let [supers  (reduce (fn [m [sub super]] (update m sub (fnil conj #{}) super)) {} edges)
        present (set contexts)]
    (loop [pending (sort-by str contexts), placed #{}, out []]
      (if (empty? pending)
        out
        (let [{ready true waiting false}
              (group-by #(every? (fn [s] (or (placed s) (not (present s)))) (supers %))
                        pending)]
          (if (seq ready)
            (recur waiting (into placed ready) (into out ready))
            ;; a cycle in the context hierarchy: break it by taking the first name
            (recur (rest waiting) (conj placed (first waiting))
                   (conj out (first waiting)))))))))

(defn- spit-edn
  [^File f x]
  (spit f (with-out-str (binding [*print-length* nil *print-level* nil] (prn x)))))

(defn write!
  "Write a corpus under `out-dir` and return its report.

  `emit-fn` is called with one argument, `emit!` — `(fn [context strength sentences])`,
  where `context` is a context symbol or `:topology` for a sentence that belongs to the
  context wiring rather than to any one context, and `strength` is `:default` or
  `:monotonic`.  Whatever `emit-fn` returns is merged into the report, so a reader says
  what it dropped and why in its own vocabulary.

  `spec` is `{:format :source :options :names :root-context :notice}`.
  The root context is where this corpus hangs off vaelii's own vocabulary: it is wired
  under `CxCore`, and every context the source never placed under another is wired
  under it, so no context arrives orphaned however incomplete the source's own hierarchy
  was.  `:notice` is the reader's attribution text
  for what it just translated — see the namespace docstring for why every corpus gets
  one whether or not the reader supplies it.

  Streaming: sentences go straight to disk as they are emitted, through a bounded writer
  pool, so the peak memory is the reader's own working set and not the corpus."
  [out-dir {:keys [format source options names root-context notice]} emit-fn]
  (let [^File dir (io/file out-dir)
        ^File kb  (io/file dir "kb")
        root      (or root-context 'CxImported)]
    (.mkdirs kb)
    (run! #(.delete ^File %) (.listFiles kb))
    (let [[writer close-all] (pooled-writers kb 96)
          topology  (atom #{})
          written   (atom 0)
          ctx-count (atom {})
          emit!     (fn [context strength sentences]
                      (when (seq sentences)
                        (swap! written + (count sentences))
                        (if (= :topology context)
                          (swap! topology into sentences)
                          (let [fname (str context
                                           (when (= :monotonic strength) ".monotonic")
                                           ".txt")
                                ^Writer w (writer fname)]
                            (swap! ctx-count update context (fnil + 0) (count sentences))
                            (doseq [s sentences]
                              (.write w (sentence-line s))
                              (.write w "\n"))))))
          extra     (try (emit-fn emit!) (finally (close-all)))
          stated    (map (fn [[_ sub super]] [sub super]) @topology)
          contexts  (vec (sort-by str (conj (into (set (keys @ctx-count)) cat stated) root)))
          ;; The wiring this writer adds: the one edge hanging the corpus's root under
          ;; vaelii's vocabulary, and a root for every context the source never placed.
          ;; They go into the **sort** as well as into the file — a root whose own
          ;; sentences loaded after its children's would be a context arriving after the
          ;; checks that read it.
          subs      (set (map first stated))
          added     (cons [root 'CxCore]
                          (for [c contexts :when (and (not (subs c)) (not= root c))] [c root]))
          order     (general-first (concat stated added) contexts)]
      ;; Written supercontext-first, so the file reads down the hierarchy the way the
      ;; contexts load.
      (with-open [^Writer w (io/writer (io/file kb "Topology.txt"))]
        (let [rank  (into {} (map-indexed (fn [i c] [c i])) order)
              depth (fn [[_ sub _]] [(rank sub Long/MAX_VALUE) (str sub)])]
          (doseq [s (concat (map (fn [[sub super]] (list 'genlCx sub super)) added)
                            (sort-by depth @topology))]
            (.write w (sentence-line s))
            (.write w "\n"))))
      (let [report (as-> (merge extra {:sentences @written :contexts (count contexts)}) r
                     ;; Every reader's report gets the same drop classification, computed
                     ;; here rather than five times: what a reason *is* is the reader's
                     ;; to declare (`:drop-kinds`), what the summary looks like is not.
                     (if (:drop-reasons r)
                       (merge (dissoc r :drop-kinds)
                              (drop-summary (:drop-reasons r) (:drop-kinds r)))
                       r))]
        (spit (io/file dir "NOTICE")
              (str "This directory holds a vaelii corpus translated from:\n\n    "
                   source "\n\n"
                   "It is a reformulation of that source's content, not this converter's\n"
                   "work, and the source's own licence and attribution terms apply to it.\n"
                   "Redistributing this corpus means redistributing them with it.\n\n"
                   (or notice
                       (str "The converter does not know this source's terms — check them\n"
                            "before redistributing, and replace this paragraph with them.\n"))
                   "\nTranslated by vaelii-foreign (Apache-2.0), format " format ".\n"
                   "The translation is lossy and reports what it dropped in report.edn.\n"))
        (spit-edn (io/file dir "names.edn")
                  (into (sorted-map-by (fn [a b] (compare (str a) (str b)))) names))
        (spit-edn (io/file dir "report.edn") report)
        (spit-edn (io/file dir "meta.edn")
                  {:format        format
                   :source        (str source)
                   :options       options
                   :root-context  root
                   :context-order order
                   :counts        (select-keys report [:sentences :contexts :dropped :unread])})
        report))))

;;; ── loading ───────────────────────────────────────────────────────────

(defn read-file-sentences
  "Every sentence in KB file `f`, read with `clojure.edn` — the corpus is data and can
  never run code, exactly as a `resources/kb` file cannot."
  [^File f]
  (with-open [r (PushbackReader. (io/reader f))]
    (let [eof (Object.)]
      (loop [acc []]
        (let [form (edn/read {:eof eof} r)]
          (if (identical? form eof) acc (recur (conj acc form))))))))

(def schema-functors
  "The sentences that define the vocabulary rather than state a fact in it: the two
  transitive relations, the disjointness and argument constraints, the predicate
  metadata, and a function's result declarations.  Every one of them **changes a cached
  closure, or is a declaration the engine reads while storing something else** — an
  `arg` when a fact is checked, a `resultIsa` when a NAT is reified.  A declaration
  that arrives after the content it governs does not apply to it retroactively, so its
  layer is not a cost decision."
  '#{genl genlCx disjoint disjointMetatype arg genlArg
     transitive symmetric reflexive asymmetric functional inverse
     decontextualizedPredicate forcedDecontextualizedPredicate
     reifiableFunction unreifiableFunction resultIsa resultGenl})

(defn schema?
  "Does `sentence` define the vocabulary rather than state a fact in it?"
  [sentence]
  (and (seq? sentence) (contains? schema-functors (first sentence))))

(def term-functors
  "The sentences that say what a **non-atomic term** is, before anything uses one.

  A NAT — `(CityInCountryFn Canada)`, `(QuantityFn 5 Meter)` — is reified to a constant
  as it reaches the store, and these five decide the whole of that: which function
  reifies at all, which constant already names an expression, and what a freshly minted
  constant is an instance of.  Every one is read *while storing something else*, and
  none applies retroactively."
  '#{reifiableFunction unreifiableFunction termOfUnit resultIsa resultGenl})

(defn term-definition?
  "Does `sentence` define a non-atomic term rather than use one?

  Its own layer, ahead of everything, because it decides **term identity**: a sentence
  mentioning `(F a)` structurally is reified against the declarations as they stand at
  that moment.  Arriving first, a `(termOfUnit K (F a))` is adopted; arriving after, it
  mints a second constant for an expression that already has one — same corpus, two
  terms, and a collision to reconcile, for no reason but file order.  A
  `reifiableFunction` that arrives late is worse still: the NAT was stored as a compound
  and no later declaration goes back for it."
  [sentence]
  (and (seq? sentence) (contains? term-functors (first sentence))))

(defn hierarchy-edge?
  "A `genl` / `genlCx` edge — the schema sentences everything else reads through the
  cached closure, so they are their own layer, loaded first."
  [sentence]
  (and (seq? sentence) (contains? '#{genl genlCx} (first sentence))))

(defn type-membership?
  "A positive unary application — `(dog Rover)`, `(binaryPredicate ownerOf)` — which is
  how vaelii states a type membership, and so is what every `arg` / `genlArg` /
  disjointness check on an *ordinary* fact reads back.

  Structural rather than a functor list, because the corpus's types are the corpus's:
  arity 1, and a functor that is a name rather than a frame.  `not` is the only frame a
  translation can put in that shape, and `(not (dog Rover))` withdraws a membership
  rather than stating one, so it is an ordinary fact."
  [sentence]
  (and (seq? sentence) (= 1 (nm/arity sentence))
       (symbol? (nm/functor sentence))
       (not= 'not (nm/functor sentence))))

(defn layer-of
  "Which of `layers` a sentence belongs to."
  [sentence]
  (cond (term-definition? sentence) :terms
        (hierarchy-edge? sentence)  :hierarchy
        (schema? sentence)          :schema
        (type-membership? sentence) :memberships
        :else                       :facts))

(def layers
  "The load order.  Each layer is read by the one after it — see the namespace docstring
  for why the order is a correctness claim and not a schedule."
  [:terms :hierarchy :schema :memberships :facts])

(defn resolve-profile
  "The load options a `:profile` name stands for, with any explicit `:keep-contexts` /
  `:drop-contexts` / `:drop-predicates` in `opts` overriding it."
  [profiles opts]
  (merge (get profiles (:profile opts :full)) opts))

(defn load-dir!
  "Load the corpus at `dir` into `kb`.  Loads `Topology.txt` first — a context's
  supercontexts must exist before its own sentences are checked against them — then each
  context file in the meta's topological order, layer by layer (see `layers`).

  `profiles` is the reader's own map of named subsets; `:profile` in `opts` picks one, or
  pass `:keep-contexts` / `:drop-contexts` / `:drop-predicates` / `:keep-layers`
  directly.  `:keep-layers` is how a vocabulary is loaded without its instance data:
  `#{:hierarchy :schema}` reads the axioms and stops.

  `:chain?` (default false) is passed to `assert`: a bulk load derives nothing useful
  fact-by-fact, so chaining once at the end (`forward-chain`) is both faster and the same
  fixpoint.  **In the same process, though** — what is owed a derivation is tracked in
  memory, not in the store, so a `forward-chain` against a KB reopened from disk returns
  `{:derived 0}` at once rather than chaining the corpus that is sitting there.  Deferring
  the chain means deferring it to the end of this call, not to a later session.

  `:bulk? true` runs the engine's bulk-load mode (`vaelii.core/*bulk-load?*`), which
  skips the per-fact definitional checks — the dominant cost is the live `(arg pred ?n
  ?type)` query every fact pays — and the dedup probe.  It stores what a checked load
  would have **refused**, so it is for a corpus a checked load has already reported on,
  not for a first look.

  `:on-progress` is called every few thousand sentences with `{:phase :done :note}`;
  `:note` is the context being read.  The total is deliberately absent: only the corpus's
  own `report.edn` knows how many sentences it holds, so a caller that wants a percentage
  supplies the denominator it read from there.  A callback that **throws** aborts the
  load where it stands — that is how a caller cancels one, and the KB is left holding
  what had already landed.

  Returns `{:asserted n :refused n :refusals {reason count} :contexts n}`.  A refusal is
  the engine declining a translated sentence — the `:type` of the `ex-info` `assert`
  throws.  `:on-refusal` is handed each one as `{:sentence :context :reason :phase :ex}`,
  which is what turns that tally into a diagnosis: a count says a check fired, only the
  sentence says whether the ontology disagrees with us or we are wrong.  Sampling rather
  than keeping every one is the caller's decision, so nothing is retained here.

  Named `load-dir!` rather than `load-dir` because a failed load leaves the KB partly
  populated — the one irreversible thing about it."
  ([kb dir] (load-dir! kb dir {} {}))
  ([kb dir profiles opts]
   (let [^File d   (io/file dir)
         meta      (edn/read-string (slurp (io/file d "meta.edn")))
         profile   (resolve-profile profiles opts)
         keep-ctx  (:keep-contexts profile)
         drop-ctx  (:drop-contexts profile #{})
         drop-pred (:drop-predicates profile #{})
         want-layers (:keep-layers profile)
         chain?    (:chain? opts false)
         kb-dir    (io/file d "kb")
         asserted  (atom 0)
         refused   (atom 0)
         refusals  (atom {})
         ;; where the load is, for `:on-progress`.  The two volatiles are what the
         ;; per-sentence tick reports without threading a phase argument through every
         ;; loop; the tick fires outside `assert1!`'s catch, so a callback that throws to
         ;; cancel is not swallowed as a refusal.
         on-prog   (:on-progress opts (fn [_]))
         on-refuse (:on-refusal opts (fn [_]))
         phase     (volatile! :topology)
         note      (volatile! nil)
         tick!     (fn []
                     (let [n (+ @asserted @refused)]
                       (when (zero? (rem (long n) 5000))
                         (on-prog {:phase @phase :done n :note @note}))))
         refuse!   (fn [s ctx reason e]
                     (swap! refused inc)
                     (swap! refusals update reason (fnil inc 0))
                     (on-refuse {:sentence s :context ctx :reason reason
                                 :phase @phase :ex e}))
         ;; Each sentence still stores and checks individually, and is caught
         ;; individually: a translated corpus is not guaranteed well-formed, and one
         ;; refusal must not cost the rest of the file.
         assert1!  (fn [s ctx opts]
                     (try (v/assert kb s ctx opts)
                          (swap! asserted inc)
                          (catch clojure.lang.ExceptionInfo e
                            (refuse! s ctx (:type (ex-data e) :unknown) e))
                          (catch Exception e
                            (refuse! s ctx (symbol (.getName (class e))) e)))
                     (tick!))
         assert-opts (fn [strength] (cond-> {:chain? chain?}
                                      (= :monotonic strength) (assoc :strength :monotonic)))
         keep?      (fn [s] (not (and (seq? s) (contains? drop-pred (first s)))))
         load-file! (fn [^File f ctx strength want]
                      (run! #(assert1! % ctx (assert-opts strength))
                            (filter #(and (keep? %) (= want (layer-of %)))
                                    (read-file-sentences f))))
         files     (vec (for [ctx (:context-order meta)
                              :when (and (not (drop-ctx ctx)) (or (nil? keep-ctx) (keep-ctx ctx)))
                              [suffix strength] [["" :default] [".monotonic" :monotonic]]
                              :let [f (io/file kb-dir (str ctx suffix ".txt"))]
                              :when (.exists f)]
                          [f ctx strength]))]
     ;; **One batch, one settle.**  `with-deferred-settle` wraps the whole load, not a
     ;; file of it: under it an assert stores and chains but does not reconcile belief,
     ;; and the taxonomy leaves its depth potential loose rather than repairing it per
     ;; edge — a repair proportional to that edge's descendants, which on a large
     ;; hierarchy is the whole cost of the load and depends on the order the edges happen
     ;; to arrive in.  The closing settle pays both once.  Belief is computed from
     ;; current state, so the KB is identical to one loaded assert by assert.
     ;; **And one compaction, afterwards.**  A bulk load grows the index and the record
     ;; log monotonically, so the durability daemon's dead-ratio trigger keeps firing
     ;; mid-load and each firing rewrites the whole (by then larger) store under the
     ;; backend lock, stalling the writer that is filling it.  Paused for the load and
     ;; left to the daemon afterwards, exactly as `io.import` does — and a no-op on
     ;; `:memory`, which registers no daemon.
     (dur/call-with-compaction-paused
      (fn []
        (binding [v/*bulk-load?* (boolean (:bulk? opts))]
          (v/with-deferred-settle kb
            (run! #(assert1! % 'CxUniverse {:chain? chain?})
                  (read-file-sentences (io/file kb-dir "Topology.txt")))
            (trove/log! {:level :info :id ::topology
                         :msg (str "topology: " @asserted " context edges")})
            (doseq [want layers
                    :when (or (nil? want-layers) (contains? want-layers want))]
              (trove/log! {:level :info :id ::phase :msg (str "phase " (name want))})
              (vreset! phase want)
              (let [milestone (atom @asserted)]
                (doseq [[f ctx strength] files]
                  (vreset! note (str ctx))
                  (load-file! f ctx strength want)
                  ;; a large corpus loads for minutes; say so every 250k sentences
                  (when (> @asserted (+ @milestone 250000))
                    (reset! milestone @asserted)
                    (trove/log! {:level :info :id ::progress
                                 :msg (str @asserted " sentences, at " ctx)})))))
            (vreset! phase :settle)
            (on-prog {:phase :settle :done (+ @asserted @refused)
                      :note "settling belief and repairing the taxonomy"})))))
     (on-prog {:phase :done :done (+ @asserted @refused) :note nil})
     (trove/log! {:level :info :id ::loaded
                  :msg (str "loaded " @asserted " sentences (" @refused " refused)")})
     {:asserted @asserted :refused @refused :refusals @refusals
      :contexts (count (:context-order meta))})))
