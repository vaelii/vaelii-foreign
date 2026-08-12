(ns vaelii.foreign.convert
  "The converter's command line, across every format this artifact reads.

  Converting an ontology is a **one-off**, and it is the point of the whole repo: a
  reader is finished the day its corpus has been converted once into the format vaelii
  writes.  So the CLI is one verb per thing you do to a corpus, and the format only
  appears where it has to.  `-main`'s docstring is the usage text a run prints; the
  README has the worked examples.

  **`load` takes no format.**  Every converter here writes the same corpus
  (`vaelii.foreign.corpus`), so loading one is format-independent: all the format decides
  is which `profiles` the `--profile` name is looked up in, and the corpus says which it
  is in its own `meta.edn`.

  Nothing here is required by the readers: this namespace depends on all five and none
  of them depends on it, so the plugin seam still loads exactly the one namespace whose
  format was asked for."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [vaelii.core :as v]
            [vaelii.foreign.atomic :as atomic]
            [vaelii.foreign.cyc :as cyc]
            [vaelii.foreign.obo :as obo]
            [vaelii.foreign.rdf :as rdf]
            [vaelii.foreign.term :as term]
            [vaelii.foreign.wordnet :as wordnet])
  (:gen-class))

(def formats
  "`<format> -> {:reader :corpus-format :source}`, the CLI's own registry.

  It is deliberately *not* read from `resources/vaelii/foreign.edn`: that manifest tells
  the **engine** which kinds it can load, keyed by the kind an engine call site asks
  for, while this table is what a person types and carries the prose that goes with it.
  Two audiences, two tables, and the plugin test keeps the reader vars in step."
  {"cyc"     {:reader cyc/reader     :corpus-format :vaelii-cyc-corpus/v1
              :source "an OpenCyc units/ dump directory, or a CycL text re-dump"}
   "rdf"     {:reader rdf/reader     :corpus-format :vaelii-rdf-corpus/v1
              :source "an .nt / .nq / .ttl / .owl file (.gz fine) of RDF, RDFS or OWL"}
   "wordnet" {:reader wordnet/reader :corpus-format :vaelii-wordnet-corpus/v1
              :source "a WordNet dict/ directory, or one data.<pos> file"}
   "obo"     {:reader obo/reader     :corpus-format :vaelii-obo-corpus/v1
              :source "an OBO-format ontology file (.obo, .gz fine)"}
   "atomic"  {:reader atomic/reader  :corpus-format :vaelii-atomic-corpus/v1
              :source "an ATOMIC-2020 directory of .tsv files, or one file"}})

(def ^:private by-corpus-format
  (into {} (map (fn [[nm {:keys [corpus-format reader]}]] [corpus-format [nm reader]])) formats))

(def boolean-flags
  "`--flag -> [option value]` for the flags that are just present or absent.

  A **`:filtered` drop is one this table can reverse**, and that is what the kind means
  rather than a description of it: a reader declining to import an obsolete term, an
  editorial note or a language nobody asked for has made a policy decision on somebody
  else's behalf, and the person converting the ontology gets to disagree.  Each reader
  names the option that reverses each of its filtered drops in `:drop-flags`, and
  `plugin-test/every-filtered-drop-is-either-reversible-or-explained` is what keeps the
  two tables from drifting."
  {"--functional"          [:functional? true]
   "--obsolete"            [:obsolete? true]
   "--editorial"           [:editorial? true]
   "--code-rules"          [:code-rules? true]
   "--empty-tails"         [:empty-tails? true]
   "--no-word-forms"       [:word-forms? false]
   "--no-node-text"        [:node-text? false]
   "--no-n-ary"            [:n-ary? false]
   "--no-entailment-rules" [:entailment-rules? false]})

(def value-flags
  "`--flag -> [option parse]` for the flags that take the next argument.

  `--context` is spelled through `term/spell` like any other context name, rather than
  interned as typed.  A context name becomes a file name inside the corpus, and this is
  the one context name a person supplies rather than a reader mints — so it goes through
  the same alnum-only spelling every minted one does.  An already-spelled name keeps its
  spelling: `spell` prepends the `Cx` marker, so a leading one is dropped first and
  `Zoo`, `zoo` and `CxZoo` all name `CxZoo`."
  {"--context"   [:context (fn [s] (symbol (term/spell :context (str/replace s #"\ACx" ""))))]
   "--languages" [:languages (fn [s] (set (str/split s #",")))]
   "--limit"     [:limit (fn [s] (Long/parseLong s))]})

(def options
  "Every option key a command line can set — the vocabulary a reader's `:drop-flags` is
  allowed to name."
  (into (set (map first (vals boolean-flags)))
        (map first (vals value-flags))))

(defn- flag-options
  "The convert options a flag list carries.  Every flag is shared across formats where
  it means the same thing and ignored by a reader it does not apply to, which is what
  keeps one command line over five converters honest."
  [args]
  (let [present (set args)
        value   (fn [flag] (second (drop-while #(not= flag %) args)))]
    (merge (into {} (for [[flag [k v]] boolean-flags :when (present flag)] [k v]))
           (into {} (for [[flag [k parse]] value-flags
                          :let  [raw (value flag)]
                          :when raw]
                      [k (parse raw)])))))

;;; ── failing legibly ───────────────────────────────────────────────────

(defn- die
  "Say `msg` on stderr and leave with a non-zero status.

  Both halves matter and they answer to different readers.  A conversion is run by hand
  about as often as it is run from a shell script, so a mistake has to be **legible** to
  the first and **detectable** to the second: a message nobody can act on is as useless
  as a status nobody can test, and this CLI used to give the second one away for free —
  an unknown format printed a helpful sentence and then exited 0."
  [& msg]
  (binding [*out* *err*] (println (apply str msg)))
  (shutdown-agents)
  (System/exit 1))

(defn- existing
  "`path` as a `File`, or a clean exit saying it is not there.

  Checked before a reader is handed the path rather than left to the open, because the
  alternative is what a typo produced until now: a `FileNotFoundException` stack trace
  and a `Full report at: /var/folders/…` line, for the commonest mistake there is."
  ^java.io.File [what path]
  (cond
    (nil? path)              (die "missing " what " — `lein convert` prints the usage")
    (.exists (io/file path)) (io/file path)
    :else                    (die what " does not exist: " path)))

(defn- corpus-dir
  "`path` as a corpus directory, or a clean exit.  A directory is one when it has both
  the `meta.edn` that says what wrote it and the `kb/` that holds what was written;
  saying which is missing beats an EOF from `edn/read-string` on a nil slurp, or a
  `listFiles` on a directory that is not there."
  ^java.io.File [path]
  (let [d (existing "corpus directory" path)]
    (cond
      (not (.exists (io/file d "meta.edn")))
      (die path " is not a corpus directory — no meta.edn in it")

      (not (.isDirectory (io/file d "kb")))
      (die path " is not a corpus directory — no kb/ in it")

      :else d)))

(defn- corpus-file
  "One of a corpus's own files, or a clean exit.  `write!` writes all of them together,
  so one missing means a write that did not finish rather than a corpus of another kind."
  ^java.io.File [^java.io.File dir fname]
  (let [f (io/file dir fname)]
    (if (.exists f)
      f
      (die (.getPath dir) " has no " fname " — the write did not finish"))))

(defn- corpus-reader
  "The reader whose corpus `dir` holds, by the `:format` line in its `meta.edn`."
  [^java.io.File dir]
  (let [m (edn/read-string (slurp (corpus-file dir "meta.edn")))]
    (or (by-corpus-format (:format m))
        (die (.getPath dir) " is in an unknown corpus format: " (pr-str (:format m))
             " — this build reads " (str/join ", " (sort (map str (keys by-corpus-format))))))))

(defn -main
  "  convert <format> <source> <out-dir> [flags]   write a corpus
  load    <corpus-dir> <kb-dir> [--profile <name>]  load one into a disk KB
  report  <corpus-dir>                              print a written corpus's report
  diff    <corpus-dir> <corpus-dir> [--edn]         what one says and the other does not
  formats                                           what can be read, and from what

  flags: --limit n, --functional, --context <Name>, --languages en,fr,
         --no-n-ary, --no-word-forms, --no-node-text, --no-entailment-rules

  what a reader filters out by default, and the flag that keeps it anyway:
         --obsolete     obo: terms the ontology has retired
         --editorial    cyc: sharedNotes, myCreationPurpose — the KB editors' notes
         --code-rules   cyc: rules Cyc implements in SubL but states in full anyway
         --empty-tails  atomic: rows whose tail is the `none` annotation
         --languages    rdf: which language-tagged literals survive"
  [& [cmd a b c & more]]
  (case cmd
    "convert"
    (let [{:keys [reader]} (or (get formats a)
                               (die "unknown format " (pr-str a) " — one of "
                                    (str/join ", " (sort (keys formats)))))
          source (existing "source" b)]
      (when (nil? c) (die "missing out-dir — `lein convert` prints the usage"))
      (println (pr-str (dissoc ((:convert! reader) (str source) c (flag-options more))
                               :by-predicate))))

    "load"
    (let [dir (corpus-dir a)
          [fmt reader] (corpus-reader dir)
          _ (when (nil? b) (die "missing kb-dir — `lein convert` prints the usage"))
          kb (v/open-kb {:backend :disk :dir b :recover? false})
          profile (keyword (or (second (drop-while #(not= "--profile" %) (cons c more)))
                               "full"))]
      ((requiring-resolve 'vaelii.impl.core-context/load-into) kb)
      (println (str "loading a " fmt " corpus, profile " profile))
      (println (pr-str ((:load-dir! reader) kb (str dir) {:profile profile})))
      ;; In this process, and it has to be: what is owed a derivation is tracked in
      ;; memory, so a `forward-chain` after a reopen would report deriving nothing.
      (println (pr-str (v/forward-chain kb {}))))

    "report" (println (slurp (corpus-file (corpus-dir a) "report.edn")))

    "diff"
    ;; Any two corpora are comparable, because every converter here writes the same one.
    ;; What that is *for* is in `vaelii.foreign.diff`'s docstring: OpenCyc ships its KB
    ;; twice, this repo reads both by code paths that share nothing, and comparing the
    ;; results is the only check either reader has that is not a fixture written to
    ;; match what the code already did.
    (let [[x y] [(corpus-dir a) (corpus-dir b)]
          d ((requiring-resolve 'vaelii.foreign.diff/compare-corpora) (str x) (str y))]
      ((requiring-resolve 'vaelii.foreign.diff/print-report) d)
      (when (some #{"--edn"} more) (println (pr-str d))))

    "formats"
    (doseq [[nm {:keys [reader source]}] (sort-by key formats)]
      (println (format "  %-8s %-26s %s" nm (:name reader) source))
      (println (format "  %-8s %-26s profiles: %s" "" ""
                       (str/join ", " (map name (sort (keys (:profiles reader))))))))

    ;; No command is a request for the usage; a wrong one is a mistake, and printing the
    ;; usage over a zero exit is how a typo reads as a clean run to whatever called this.
    (if (nil? cmd)
      (println (:doc (meta #'-main)))
      (die "unknown command " (pr-str cmd) " — one of "
           (str/join ", " ["convert" "diff" "formats" "load" "report"])
           "\n\n" (:doc (meta #'-main)))))
  (shutdown-agents))
