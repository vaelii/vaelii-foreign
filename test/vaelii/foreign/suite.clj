(ns vaelii.foreign.suite
  "Running somebody else's conformance suite against our readers.

  The W3C publishes a syntax test suite for each RDF serialization — Turtle, N-Triples,
  N-Quads and RDF/XML — as a directory of documents plus a `manifest.ttl` saying, of
  each, whether it must parse and what graph it must yield.  That is a far better test
  of a lexer than anything we would write for ourselves, because it was assembled from
  the mistakes real parsers made.

  Three things this namespace has to supply:

  1. **Where the suite is.**  Cached by `scripts/fetch-suites.sh`, never vendored, so
     every test that needs it skips when it is absent and CI stays offline.
  2. **Reading the manifest.**  It is Turtle, so `vaelii.foreign.turtle` reads it — the
     suite for a reader is described in the language that reader reads.  Circular only
     in appearance: a manifest exercises none of what the hard tests are about, and a
     reader too broken to read one fails everything anyway.
  3. **Comparing graphs.**  An eval test names the expected graph as N-Triples, and two
     RDF graphs are equal up to a *renaming of blank nodes* — so this needs real graph
     isomorphism, not set equality.  `isomorphic?` is the only substantial code here."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [vaelii.foreign.turtle :as ttl]))

;;; ── the cache ─────────────────────────────────────────────────────────

(def cache-dir
  "Where `scripts/fetch-suites.sh` puts third-party test material.  The same
  `VAELII_FOREIGN_CACHE` the script reads, so several checkouts can share one copy."
  (io/file (or (System/getenv "VAELII_FOREIGN_CACHE") ".cache")))

(defn cached
  "The cached file at `parts`, or nil when it is not there.  Every suite test calls this
  first and skips on nil: a checkout that has not fetched still runs the whole
  hand-authored suite, which is the part that must never need a network."
  [& parts]
  (let [f (apply io/file cache-dir parts)]
    (when (.exists f) f)))

(defn missing-note
  "What to print when a suite is absent, said once and the same way everywhere."
  [what]
  (str "  … " what " not cached — run scripts/fetch-suites.sh to include it"))

;;; ── manifests ─────────────────────────────────────────────────────────

(def ^:private mf "http://www.w3.org/2001/sw/DataAccess/tests/test-manifest#")
(def ^:private rdft "http://www.w3.org/ns/rdftest#")
(def ^:private rdf-type (ttl/iri (str ttl/rdf-ns "type")))

(defn- relative
  "A manifest's reference to a test file, as a path under the manifest's own directory.

  The two suites disagree about layout — Turtle keeps every document beside the
  manifest, RDF/XML groups them one directory per test — so this keeps the whole
  reference rather than its last segment.  An absolute reference is cut back to the part
  after `base`, which is what `mf:assumedTestBase` exists to make possible."
  [base x]
  (when (ttl/iri? x)
    (let [s (ttl/iri-str x)]
      (cond
        (and base (str/starts-with? s base))       (subs s (count base))
        (re-find #"^[A-Za-z][A-Za-z0-9+.-]*:" s)   (subs s (inc (long (str/last-index-of s "/"))))
        :else                                       s))))

(defn manifest
  "The tests `manifest.ttl` in `dir` declares, as
  `[{:name :type :action :result :base} …]`.

  `:type` is the local part of the `rdft:` class — `TestTurtleEval`,
  `TestXMLNegativeSyntax` and so on — and is what decides how the test is run.  `:base`
  is the manifest's `mf:assumedTestBase` with the action's filename on the end, which is
  what the suite's relative IRIs are written against: get it wrong and every eval test
  in a file using relative IRIs fails for one reason."
  [dir]
  (let [m  (io/file dir "manifest.ttl")
        ts (with-open [r (io/reader m)] (doall (ttl/triples r)))
        by-subject (group-by :s ts)
        of (fn [s p] (some (fn [t] (when (= (ttl/iri-str (:p t)) p) (:o t))) (by-subject s)))
        base (some (fn [t] (when (= (ttl/iri-str (:p t)) (str mf "assumedTestBase"))
                             (ttl/iri-str (:o t))))
                   ts)]
    (->> (for [[s trips] by-subject
               :let [ty (some (fn [t] (when (and (= (:p t) rdf-type)
                                                 (ttl/iri? (:o t))
                                                 (str/starts-with? (ttl/iri-str (:o t)) rdft))
                                        (subs (ttl/iri-str (:o t)) (count rdft))))
                              trips)]
               :when ty
               :let [action (relative base (of s (str mf "action")))]]
           {:name   (ttl/lex (of s (str mf "name")))
            :type   ty
            :action action
            :result (relative base (of s (str mf "result")))
            :dir    (io/file dir)
            :base   (when (and base action) (str base action))})
         (sort-by :name)
         vec)))

;;; ── graph isomorphism ─────────────────────────────────────────────────

(defn- bnode-set [g]
  (into #{} (comp (mapcat (juxt :s :o)) (filter ttl/bnode?)) g))

(defn- colours
  "A colour per blank node, refined until it stops splitting.

  The colour of a node is a hash of the triples it takes part in, with any blank node in
  those triples standing in as *its* colour from the previous round.  Two nodes that end
  up different colours cannot be matched, which is what makes the search that follows
  cheap: refinement usually leaves every node alone in its class, and then there is
  nothing to search."
  [g]
  (let [bs (bnode-set g)]
    (loop [c (zipmap bs (repeat 0)), n 0]
      (let [c' (into {} (for [b bs]
                          [b (hash [(c b)
                                    (sort-by str (for [{:keys [s p o]} g :when (= s b)]
                                                   [:out p (if (ttl/bnode? o) (c o) o)]))
                                    (sort-by str (for [{:keys [s p o]} g :when (= o b)]
                                                   [:in p (if (ttl/bnode? s) (c s) s)]))])]))]
        (if (or (= (frequencies (vals c')) (frequencies (vals c))) (> n 8))
          c'
          (recur c' (inc n)))))))

(defn- rename [g m]
  (into #{} (map (fn [t] (-> t (update :s #(m % %)) (update :o #(m % %))))) g))

(defn isomorphic?
  "Are `a` and `b` the same RDF graph, up to a renaming of blank nodes?

  Blank nodes are existential variables with document-local names, so `_:x` in one file
  and `_:b0` in another are the same node if they sit in the same place.  Set equality
  would call two identical graphs different for having chosen different labels, and
  every eval test in the suite would fail on it.

  Colour refinement first, then backtracking over the nodes a colour could not separate.
  Suite graphs are tiny; this is not a general canonicalizer and does not need to be."
  [a b]
  (let [a (set a) b (set b)]
    (and
     (= (count a) (count b))
     (let [ground #(into #{} (remove (fn [{:keys [s o]}] (or (ttl/bnode? s) (ttl/bnode? o)))) %)]
       (= (ground a) (ground b)))
     (let [ba (bnode-set a), bb (bnode-set b)]
       (and (= (count ba) (count bb))
            (let [ca (colours a), cb (colours b)]
              (and (= (frequencies (vals ca)) (frequencies (vals cb)))
                   (let [by-colour (group-by cb bb)]
                     (letfn [(search [todo used m]
                               (if (empty? todo)
                                 (= (rename a m) b)
                                 (let [x (first todo)]
                                   (some (fn [y]
                                           (when-not (used y)
                                             (search (rest todo) (conj used y) (assoc m x y))))
                                         (by-colour (ca x))))))]
                       (boolean (search (sort-by str ba) #{} {})))))))))))

;;; ── running one test ──────────────────────────────────────────────────

(defn- read-graph
  "Parse `f` with `parse` and return its triples as a set, with any `:g` dropped: the
  eval tests compare graphs, and N-Quads' graph term is not part of one."
  [parse f opts]
  (with-open [r (io/reader f)]
    (into #{} (map #(dissoc % :g)) (parse r opts))))

(defn run
  "Run one manifest entry with `parse` (a `[reader opts] -> triples` fn) and return
  `{:name :type :outcome}` where `:outcome` is `:pass`, `:fail` or `:error`.

  A negative test passes by throwing.  That is the whole reason `turtle/triples` grew a
  `:strict?` option: its default is to skip a bad statement and carry on, which is right
  for a dump and would answer \"fine\" to every document the suite says is broken."
  [{:keys [type action result dir base] :as t} parse]
  (let [negative? (str/includes? type "Negative")
        f (io/file dir action)]
    (assoc (select-keys t [:name :type])
           :outcome
           (try
             (let [g (read-graph parse f {:base base :strict? true})]
               (cond
                 negative?    :fail                    ; it should not have parsed
                 (nil? result) :pass                   ; positive syntax: parsing is the test
                 :else
                 (let [want (read-graph (fn [r o] (ttl/triples r o))
                                        (io/file dir result) {:base base :strict? true})]
                   (if (isomorphic? g want) :pass :fail))))
             (catch Exception _ (if negative? :pass :error))))))

(defn summarize
  "`{:pass n :fail n :error n :total n :rate \"96%\"}` over `run` results."
  [results]
  (let [f (frequencies (map :outcome results))
        total (count results)]
    (assoc f :total total
           :rate (if (zero? total) "—"
                     (format "%.1f%%" (* 100.0 (/ (double (:pass f 0)) total)))))))
