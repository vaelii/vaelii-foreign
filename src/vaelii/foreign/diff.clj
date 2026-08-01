(ns vaelii.foreign.diff
  "Compare two corpora — what one says and the other does not.

  Every reader here writes the same corpus, so any two are comparable whatever produced
  them, and that turns out to be worth more than a debugging aid.

  **The case that motivated it.**  OpenCyc publishes its knowledge base twice: as the
  binary CFASL dump the inference engine loads, and as an OWL export.  This repo reads
  both, by two code paths that share nothing below `corpus/write!` — one is an opcode
  table over a byte stream, the other an XML lexer feeding a description-logic
  projection.  Converting the same KB through both and comparing is the only check
  either reader has that is not a fixture somebody wrote to match what the code already
  did.  If the taxonomies agree, two independent implementations agree; where they
  differ, the difference is a *finding* — about a reader, or about the two releases.

  Because they are two releases.  The OWL export and the CFASL dump ship on their own
  schedules, so the diff is not expected to be empty and an empty one would be the
  surprising result.  What the diff is for is telling the two kinds of difference apart:
  a term missing on one side because the release genuinely lacks it, and a term missing
  because a reader dropped it.

  **What is compared, and why not everything.**  The *taxonomy* — `genl` edges and the
  terms in them — plus a per-predicate sentence count.  Not the full sentence sets: a
  corpus of two million sentences would need a real graph diff to compare completely,
  and most of the difference would be lexical noise (labels, comments, identifiers) that
  says nothing about whether the two agree.  The taxonomy is the part with a truth
  value.

  Names are the join key, and that is sound here for a specific reason: both readers
  name from the same underlying Cyc term names, so `#$BadmintonAccessory` in the dump
  and `Badminton_accessory` in the OWL both spell to `badminton_accessory`.  Comparing
  corpora from unrelated sources would need a mapping and this offers none."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [vaelii.foreign.corpus :as corpus])
  (:import (java.io File)))

(defn- content-files
  "The per-context sentence files of the corpus at `dir`.  `Topology.txt` holds the
  context wiring rather than any one context's content, so it is not one of them."
  [dir]
  (let [^File kb (io/file dir "kb")]
    (when-not (.isDirectory kb)
      (throw (ex-info (str dir " is not a corpus: no kb/ directory in it")
                      {:type :diff/not-a-corpus :dir (str dir)})))
    (->> (.listFiles kb)
         (filter #(.isFile ^File %))
         (remove #(= "Topology.txt" (.getName ^File %))))))

(defn- context-of
  "The context whose sentences file `f` holds.  A context writes up to two files —
  `<Context>.txt` and `<Context>.monotonic.txt` — so the files are not the contexts."
  [^File f]
  (-> (.getName f) (str/replace #"\.txt\z" "") (str/replace #"\.monotonic\z" "")))

(defn summarize
  "The comparable shape of the corpus at `dir`:

      {:sentences n :contexts n :predicates {p n} :genl #{[child parent]} :terms #{t}}

  Streamed a file at a time, because the corpora this exists for hold millions of
  sentences and the point is to compare them, not to hold two of them at once."
  [dir]
  (let [files (content-files dir)]
    (reduce
     (fn [acc ^File f]
       (reduce
        (fn [acc s]
          (if-not (seq? s)
            acc
            (let [p (first s)]
              (cond-> (-> acc
                          (update :sentences inc)
                          (update-in [:predicates p] (fnil inc 0)))
                (and (= 'genl p) (= 3 (count s)))
                (-> (update :genl conj [(nth s 1) (nth s 2)])
                    (update :terms conj (nth s 1))
                    (update :terms conj (nth s 2)))))))
        acc
        (corpus/read-file-sentences f)))
     {:sentences 0 :contexts (count (into #{} (map context-of) files))
      :predicates {} :genl #{} :terms #{}}
     files)))

(defn compare-corpora
  "`(summarize a)` against `(summarize b)`, as a report.

  Each side's exclusives are capped at `:sample` (default 40) in `:examples`, because the
  count is the finding and forty names are enough to see *what kind* of thing is on one
  side only.  The counts themselves are never truncated."
  ([a b] (compare-corpora a b {}))
  ([a b {:keys [sample] :or {sample 40}}]
   (let [sa (summarize a)
         sb (summarize b)
         ;; sorted by `str`, not by `compare`: a Cyc corpus has non-atomic terms in it —
         ;; `(genl (FruitFn AppleTree) fruit)` names a collection with a function, and a
         ;; list is not Comparable.  Sorting is only here to make the examples stable
         only (fn [x y] (into #{} (remove y) x))
         sorted (fn [xs] (sort-by str xs))
         ta (only (:terms sa) (:terms sb))
         tb (only (:terms sb) (:terms sa))
         ga (only (:genl sa) (:genl sb))
         gb (only (:genl sb) (:genl sa))
         shared-terms (count (filter (:terms sb) (:terms sa)))]
     {:a {:dir (str a) :sentences (:sentences sa) :contexts (:contexts sa)
          :terms (count (:terms sa)) :genl (count (:genl sa))}
      :b {:dir (str b) :sentences (:sentences sb) :contexts (:contexts sb)
          :terms (count (:terms sb)) :genl (count (:genl sb))}
      :shared {:terms shared-terms
               :genl (count (filter (:genl sb) (:genl sa)))
               ;; of the terms both name, how much of the taxonomy they agree about —
               ;; the one number worth reading first, because it is the one that says
               ;; whether the two readers are describing the same knowledge base
               :genl-agreement
               (let [common (filter (:genl sb) (:genl sa))
                     total  (count (into (:genl sa) (:genl sb)))]
                 (when (pos? total)
                   (format "%.1f%%" (* 100.0 (/ (double (count common)) total)))))}
      :only-in-a {:terms (count ta) :genl (count ga)}
      :only-in-b {:terms (count tb) :genl (count gb)}
      :predicates {:only-in-a (into (sorted-map)
                                    (remove #(contains? (:predicates sb) (key %)))
                                    (:predicates sa))
                   :only-in-b (into (sorted-map)
                                    (remove #(contains? (:predicates sa) (key %)))
                                    (:predicates sb))}
      :examples {:terms-only-in-a (vec (take sample (sorted ta)))
                 :terms-only-in-b (vec (take sample (sorted tb)))
                 :genl-only-in-a  (vec (take sample (sorted ga)))
                 :genl-only-in-b  (vec (take sample (sorted gb)))}})))

(defn print-report
  "The comparison as something to read, rather than as edn to grep."
  [{:keys [a b shared only-in-a only-in-b predicates examples]}]
  (println (format "  %-46s %12s %12s" "" "A" "B"))
  (doseq [[label ka kb] [["sentences" (:sentences a) (:sentences b)]
                         ["contexts"  (:contexts a)  (:contexts b)]
                         ["terms in the taxonomy" (:terms a) (:terms b)]
                         ["genl edges" (:genl a) (:genl b)]]]
    (println (format "  %-46s %12d %12d" label ka kb)))
  (println)
  (println (format "  shared terms       %d" (:terms shared)))
  (println (format "  shared genl edges  %d  (%s of the union)"
                   (:genl shared) (or (:genl-agreement shared) "—")))
  (println (format "  only in A          %d terms, %d edges"
                   (:terms only-in-a) (:genl only-in-a)))
  (println (format "  only in B          %d terms, %d edges"
                   (:terms only-in-b) (:genl only-in-b)))
  (doseq [[side ps] [["A" (:only-in-a predicates)] ["B" (:only-in-b predicates)]]
          :when (seq ps)]
    (println (format "\n  predicates only in %s (%d):" side (count ps)))
    (println (str "    " (str/join ", " (take 24 (map key ps))))))
  (doseq [[label xs] [["terms only in A" (:terms-only-in-a examples)]
                      ["terms only in B" (:terms-only-in-b examples)]]
          :when (seq xs)]
    (println (format "\n  %s (first %d):" label (count xs)))
    (println (str "    " (str/join ", " xs))))
  ;; An edge on one side only is the finding the term counts point at, so the examples
  ;; that carry it get printed rather than left for `--edn` to dig out.
  (doseq [[label xs] [["genl edges only in A" (:genl-only-in-a examples)]
                      ["genl edges only in B" (:genl-only-in-b examples)]]
          :when (seq xs)]
    (println (format "\n  %s (first %d):" label (count xs)))
    (doseq [[child parent] xs]
      (println (format "    (genl %s %s)" child parent)))))
