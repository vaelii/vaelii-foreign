(ns vaelii.foreign.atomic
  "Read ATOMIC-2020 — a commonsense knowledge graph of if-then inferences — and
  translate it into a vaelii corpus.

  Of everything this repo reads, ATOMIC is the one whose content is **already the shape
  vaelii's assumption strengths were built for**.  Cyc marks a handful of assertions
  `:default`; an OBO ontology and an OWL vocabulary are definitional throughout.  Every
  one of ATOMIC's 1.3 million tuples is a defeasible generalization — *if PersonX
  abandons the cat, PersonX probably wants to find a new home for it* — collected by
  asking people what usually follows.  Nothing in it is a definition, so nothing in it
  is written `:monotonic`, and a corpus where every sentence can be retracted by a later
  one is a corpus a truth-maintenance system has something to do with.

  ## The format

  ATOMIC-2020 ships as tab-separated triples, split into `train.tsv`, `dev.tsv` and
  `test.tsv`:

      PersonX abandons ___ altogether \\t xIntent \\t to be selfish
      PersonX abandons ___ altogether \\t xEffect \\t none
      baseball bat \\t ObjectUse \\t hit a ball

  The 23 relations fall into three families, and the families are this corpus's
  contexts:

  * **social** — what a person intends, needs, feels, wants, or becomes because of an
    event (`xIntent`, `xNeed`, `xAttr`, `xEffect`, `xReact`, `xWant`, `xReason`, and the
    `o*` versions for the *other* people in the event);
  * **event** — how events sit next to each other (`isAfter`, `isBefore`, `Causes`,
    `HasSubEvent`, `HinderedBy`, `isFilledBy`);
  * **physical** — what a thing is for, where it is, what it is made of (`ObjectUse`,
    `AtLocation`, `MadeUpOf`, `HasProperty`, `CapableOf`, `Desires`, `NotDesires`).

  ## Nodes are phrases, and stay phrases

  An ATOMIC node is a fragment of English — `PersonX abandons ___ altogether`, `to be
  selfish`, `baseball bat`.  It is not a term and has no definition anywhere, so each
  one becomes an **individual** named from its own text, with the text itself kept
  alongside as `(nodeText PersonXAbandonsAltogether \"PersonX abandons ___ altogether\")`.

  The temptation is to make `baseball bat` a type and `PersonX abandons ___ altogether`
  an event template, and it is worth saying why that is not done: ATOMIC never marks
  which of its nodes are kinds, the same string appears on both sides of relations from
  different families, and a type with no members and no supertype is a name with nothing
  attached.  Inventing a type system the corpus does not have would make the output look
  more structured than the source and be wrong in ways nothing could check.

  Two rows with the same text are the **same** node, which is what makes the result a
  graph rather than a list: `to be selfish` reached as PersonX's intent and reached
  again as somebody's reaction is one individual with two relations to it.

  A `none` tail — ATOMIC's own marker for \"annotators said nothing follows\" — is
  dropped and counted.  It is a real annotation and it is not a fact."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [taoensso.trove :as trove]
            [vaelii.foreign.corpus :as corpus]
            [vaelii.foreign.term :as term]))

;;; ── the format ────────────────────────────────────────────────────────

(def relations
  "The 23 ATOMIC-2020 relations, and the family — this corpus's context — each belongs
  to.  A relation outside this table is read as an ordinary predicate in the event
  context and counted, so a release that adds one is imported rather than silently
  dropped."
  {"xIntent" :social "xNeed" :social "xAttr" :social "xEffect" :social
   "xReact"  :social "xWant" :social "xReason" :social
   "oEffect" :social "oReact" :social "oWant" :social

   "isAfter" :event "isBefore" :event "HasSubEvent" :event "Causes" :event
   "HinderedBy" :event "isFilledBy" :event

   "ObjectUse" :physical "AtLocation" :physical "MadeUpOf" :physical
   "HasProperty" :physical "CapableOf" :physical "Desires" :physical
   "NotDesires" :physical})

(def ^:private family-contexts
  {:social 'CxAtomicSocial :event 'CxAtomicEvent :physical 'CxAtomicPhysical})

(def ^:private empty-tails
  "Tail values that are the annotation \"nothing follows here\" rather than a claim.  They
  are a large fraction of a real release and importing them would state, of a great many
  events, that they cause a thing called None."
  #{"none" "None" "NONE" "" "null"})

(defn parse-line
  "One TSV row as `{:head :relation :tail}`, or nil for a blank line, a header row, or a
  row without three fields.  Surrounding whitespace goes; internal whitespace and the
  `___` blank are content."
  [^String line]
  (when-not (str/blank? line)
    (let [f (mapv str/trim (str/split line #"\t"))]
      (when (and (>= (count f) 3)
                 (not (str/blank? (nth f 0)))
                 (not= "head" (nth f 0)))          ; a header row, if the file has one
        {:head (nth f 0) :relation (nth f 1) :tail (nth f 2)}))))

(defn with-rows
  "Call `(f rows)` on a seq of every row in the ATOMIC release at `path` — a directory
  of `.tsv` files, or one file.  `.gz` is decompressed on the way through.  The seq is
  only valid inside `f`."
  [path f]
  (let [^java.io.File p (io/file path)
        files (if (.isDirectory p)
                (sort-by #(.getName ^java.io.File %)
                         (filter #(re-find #"\.tsv(\.gz)?$" (.getName ^java.io.File %))
                                 (.listFiles p)))
                [p])]
    (f (mapcat (fn [^java.io.File file]
                 (with-open [in (io/input-stream file)]
                   (let [in (if (str/ends-with? (str/lower-case (.getName file)) ".gz")
                              (java.util.zip.GZIPInputStream. in)
                              in)]
                     (with-open [r (io/reader in)]
                       ;; forced per file: the reader closes when this file is done, and
                       ;; a lazy seq escaping it would read from a closed stream
                       (into [] (keep parse-line) (line-seq r))))))
               files))))

;;; ── pass 1: names ─────────────────────────────────────────────────────

(defn classify
  "Pass 1.  `{:nodes #{text …} :relations #{name …} :rows n}`.

  Every node is an individual, so there is no role to resolve — what pass 1 is for here
  is the **set** of them: a node reached by two rows must be one term, and that cannot
  be known one row at a time.

  `:empty-tails?` has to be read *here* as well as in `translate`: an empty tail kept by
  the flag needs a name, and pass 2 has no way to mint one."
  ([rows] (classify rows {}))
  ([rows opts]
   (let [empty? (if (:empty-tails? opts) (constantly false) empty-tails)]
     (reduce (fn [acc {:keys [head relation tail]}]
               (cond-> (-> acc
                           (update :rows (fnil inc 0))
                           (update :nodes (fnil conj #{}) head)
                           (update :relations (fnil conj #{}) relation))
                 (not (empty? tail))
                 (update :nodes conj tail)))
             {}
             rows))))

(defn name-table
  "The node text -> vaelii term map, plus a predicate per relation.  Sorted by text so a
  collision — two long phrases that abbreviate to the same head — resolves the same way
  on every run."
  [{:keys [nodes relations]}]
  (term/name-table
   (concat (for [n (sort nodes)] [n :individual (term/abbreviate n)])
           (for [r (sort relations)] [[:relation r] :predicate r]))))

;;; ── pass 2: translate ─────────────────────────────────────────────────

(defn translate
  "The sentences one row becomes, or `{:dropped reason}`.  Everything lands `:default`:
  an ATOMIC tuple is what people said usually follows, and there is no other strength
  that is honest about it."
  ([row names] (translate row names {}))
  ([{:keys [head relation tail]} names opts]
   (let [h (:term (names head))
         t (:term (names tail))
         p (:term (names [:relation relation]))]
     (cond
       (and (contains? empty-tails tail) (not (:empty-tails? opts))) {:dropped :no-tail}
       (nil? h)                     {:dropped :unnamed-head}
       (nil? t)                     {:dropped :unnamed-tail}
       (nil? p)                     {:dropped :unnamed-relation}
       :else
       {:context   (family-contexts (relations relation) 'CxAtomicEvent)
        :sentences [(list p h t)]}))))

;;; ── converting ────────────────────────────────────────────────────────

(def drop-kinds
  "What each of this reader's drop reasons **is** — see `corpus/drop-kinds`.  A reason
  missing here counts as `:unread`.

  `:no-tail` is ATOMIC's `none` — the annotators' own marker for \"nothing follows\".  It
  is a real annotation and it is not a fact, so declining to write it is a policy and
  not a failure to read one."
  '{:no-tail :filtered})

(def drop-flags
  "The convert option that keeps each `:filtered` drop — see `cyc/drop-flags` for the
  contract and `plugin-test` for what enforces it.

  `--empty-tails` is a flag and not a justification because the objection to importing
  these is a judgement rather than a fact about the format: writing them states, of a
  great many events, that they cause a thing called None, which is a bad reading of
  \"the annotators looked and found nothing\".  Whether that is worse than losing the
  annotation entirely depends on what the corpus is for, and this is not the place to
  decide it."
  {:no-tail :empty-tails?})

(defn convert!
  "Convert the ATOMIC release at `path` — a directory of `.tsv` files, or one file —
  into a vaelii corpus under `out-dir`.  Two passes: the first collects the nodes and
  names them, the second writes the sentences.  Returns the report map.

  Options: `:node-text?` writes a `nodeText` fact carrying each node's original English
  (on — it is the only way back to the source); `:empty-tails?` keeps the rows whose tail
  is the `none` annotation (off — see `drop-flags`); `:limit` reads only the first n
  rows."
  ([path out-dir] (convert! path out-dir {}))
  ([path out-dir opts]
   (let [limit (:limit opts)
         cap   (fn [xs] (if limit (take limit xs) xs))]
     (trove/log! {:level :info :id ::classify :msg "pass 1: collecting nodes"})
     (let [evidence (with-rows path #(classify (cap %) opts))
           names    (name-table evidence)]
       (trove/log! {:level :info :id ::translate
                    :msg (str "pass 2: translating (" (count (:nodes evidence)) " nodes, "
                              (count (:relations evidence)) " relations)")})
       (let [report
             (corpus/write!
              out-dir
              {:format       :vaelii-atomic-corpus/v1
               :source       path
               :options      opts
               :names        names
               :root-context 'CxAtomic
               :notice
               (str "ATOMIC-2020, from the Allen Institute for AI, is distributed under\n"
                    "CC-BY 4.0.  This corpus is a reformulation of it and carries the same\n"
                    "terms: attribution to AI2 travels with it.\n"
                    "\nEvery tuple is a crowdsourced generalization about what usually\n"
                    "follows, not a definition -- which is why none of it is written at\n"
                    "monotonic strength.  Treat it as the defeasible knowledge it is.\n")}
              (fn [emit!]
                (let [counts  (atom {:rows 0 :dropped 0})
                      dropped (atom {})]
                  ;; the node texts first, in one context: a node is reached from every
                  ;; family and its text belongs to none of them
                  (when (:node-text? opts true)
                    (emit! 'CxAtomic :default
                           (vec (for [[text {:keys [term]}] (sort-by (comp str key) names)
                                      :when (string? text)]
                                  (list 'nodeText term text)))))
                  (with-rows
                    path
                    (fn [rows]
                      (doseq [row (cap rows)]
                        (swap! counts update :rows inc)
                        (let [r (translate row names opts)]
                          (if-let [why (:dropped r)]
                            (do (swap! counts update :dropped inc)
                                (swap! dropped update why (fnil inc 0)))
                            (emit! (:context r) :default (:sentences r)))))))
                  {:source       (str path)
                   :rows         (:rows @counts)
                   :dropped      (:dropped @counts)
                   :drop-reasons (into (sorted-map) @dropped)
                   :drop-kinds   drop-kinds
                   :nodes        (count (:nodes evidence))
                   :relations    (into (sorted-set) (:relations evidence))})))]
         (trove/log! {:level :info :id ::converted
                      :msg (str "converted " (:rows report) " rows -> "
                                (:sentences report) " sentences over " (:nodes report)
                                " nodes (" (:dropped report) " dropped)")})
         report)))))

;;; ── loading ───────────────────────────────────────────────────────────

(def profiles
  "Named subsets, one per relation family — which is how ATOMIC is actually used, since
  the social half and the physical half answer different questions.  `:ontology` keeps
  every relation and drops the node texts, which are a third of the sentences."
  {:full     {}
   :ontology {:drop-predicates '#{nodeText}}
   :social   {:keep-contexts #{'CxAtomic 'CxAtomicSocial}}
   :physical {:keep-contexts #{'CxAtomic 'CxAtomicPhysical}}
   :events   {:keep-contexts #{'CxAtomic 'CxAtomicEvent}}})

(defn load-dir!
  "Load the corpus at `dir` into `kb` — `vaelii.foreign.corpus/load-dir!` with this
  format's `profiles`."
  ([kb dir] (load-dir! kb dir {}))
  ([kb dir opts] (corpus/load-dir! kb dir profiles opts)))

(def reader
  "This format's reader, as the seam (`vaelii.impl.foreign`) hands it out."
  {:name       "ATOMIC commonsense graph"
   :load-dir!  load-dir!
   :convert!   convert!
   :profiles   profiles
   :drop-kinds drop-kinds
   :drop-flags drop-flags})
