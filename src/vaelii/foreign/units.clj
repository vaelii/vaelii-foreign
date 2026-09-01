(ns vaelii.foreign.units
  "Read an OpenCyc KB dump directory — `server/cyc/run/units/<n>/` — as assertions.

  This is the other end of `vaelii.foreign.cycl`: the same
  `{:formula :mt :strength :direction}` maps, taken straight from the binary dump
  instead of from a text re-dump of it, so `vaelii.foreign.cyc` translates either
  without knowing which it got.

  **A dump is several files that only mean something together.**  Each holds a flat
  sequence of records — a copyright string, then `dump-id` followed by that record's
  fields — and every reference between them is an integer id resolved against another
  file:

      constant-shell.text     \"name\" -> dump-id, in plain text: the whole name table
      nart-hl-formula.cfasl   nart id -> its HL formula, whose head is a function
      clause-struc.cfasl      clause-struc id -> a CNF, which is how a rule is stored
      assertion.cfasl         id, formula-data, mt, flags, arguments, plist

  So the three small tables are read whole and the 780 MB assertion file streams
  against them.  `constant-shell.text` being text is not a convenience this reader
  invented — the dump writes the name table twice, once as CFASL and once as text, and
  the text one is complete.

  **A NART may name a NART defined later in the file.**  Resolving during the read would
  render that forward reference as an unresolved marker and then propagate it into every
  assertion mentioning the term, so the formulas are read with NART references left as
  markers and expanded when an assertion is *emitted* — by which point every NART is in
  the table.  `nart-depth-limit` and `nart-node-limit` are what a NART that refers back
  to itself would otherwise do.

  **What the counts are for.**  Nothing frames a CFASL object but its own opcode, so a
  mis-sized payload desynchronizes the rest of the file instead of failing in place.
  Each dump states its own record count in `<name>-count.text`, and a read that reaches
  the end of the file is compared against it: a short read means the stream went out of
  step, and it is an error rather than a smaller KB.  A read that stopped early has no
  such comparison to make — the stated count describes the whole file — so `:limit`
  samples a dump without the check and only without it.

  Provenance of the format, and which upstream sources may be used on it:
  `licenses/THIRD-PARTY.md`."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [vaelii.foreign.cfasl :as cfasl])
  (:import (java.io BufferedInputStream InputStream)))

;; Big enough that a 780 MB file is not read a syscall at a time; the reader pulls one
;; byte at a time by nature, so this buffer is doing all of the I/O batching.
(def ^:private ^:const stream-buffer-bytes (* 1024 1024))

(def ^:const nart-depth-limit
  "How far a chain of NART references is followed before one reads back as its own marker.

  A NART's formula may mention another NART, and nothing in a dump forbids a cycle, so
  the chain is bounded.  24 is deeper than any chain OpenCyc 4.0 actually states."
  24)

(def ^:const nart-node-limit
  "How many nodes one expansion may walk before the rest is left as it stands.

  Depth bounds a chain and not the work: a NART whose formula mentions a NART twice
  doubles at every hop, so 24 levels of it is 16.7M nodes and a heap the read does not
  come back from.  This counts what the walk actually touches, which is the quantity
  that has to stay finite.  100,000 is orders of magnitude past the largest formula
  OpenCyc 4.0 states, and small enough that a dump built to blow the expansion up costs
  a few megabytes instead of the process."
  100000)

;;; ── flags ─────────────────────────────────────────────────────────────

;; One integer carries three fields, as bit ranges: whether the assertion is a ground
;; atomic formula (bit 0), its direction (bits 1-2) and its truth value (bits 3-5).
;; Upstream spells these as byte specs — `(byte 1 0)`, `(byte 2 1)`, `(byte 3 3)`.
(def ^:private directions
  "Direction code -> what it names, in the dump's own order."
  [:backward :forward :code])

(def ^:private truth-values
  "Truth-value code -> what it names.  One code carries both truth and strength, which
  is why Cyc's monotonic-vs-default marking arrives as vaelii's assumption strength."
  [:true-mon :true-def :unknown :false-def :false-mon])

(defn decode-flags
  "The `{:gaf? :direction :truth :strength}` an assertion's flags integer states.

  `:truth` here is **Cyc's** truth value and is three-valued — `:true` / `:false` /
  `:unknown` — so it is not vaelii's `:polarity`, which is two-valued and is a slot on
  the record.  Nothing writes this to a slot: `assertion` consumes a `:false` into a
  `cyc/not` wrapper, and vaelii's canonicalization derives the record's polarity from
  that wrapper."
  [^long flags]
  (let [tv (get truth-values (bit-and (bit-shift-right flags 3) 7))]
    {:gaf?      (bit-test flags 0)
     :direction (get directions (bit-and (bit-shift-right flags 1) 3))
     :truth     (case tv
                  (:true-mon :true-def)   :true
                  (:false-mon :false-def) :false
                  :unknown)
     :strength  (case tv
                  (:true-mon :false-mon) :monotonic
                  :default)}))

;;; ── the name table ────────────────────────────────────────────────────

(defn read-constant-names
  "`constant-shell.text` as a dump-id -> name map.

  The file is a copyright string, the constant count, then `\"Name\" id` a line at a
  time.  Read as text rather than as Lisp: a name is a quoted string and an id an
  integer, and nothing else appears.  Latin-1, the encoding the dump's CFASL strings
  carry by construction — so a name with a high byte reads back the same characters
  from both tables instead of decoding to U+FFFD here."
  [dir]
  (with-open [r (io/reader (io/file dir "constant-shell.text") :encoding "ISO-8859-1")]
    (into {}
          (keep (fn [line]
                  (let [m (re-matches #"\s*\"((?:[^\"\\]|\\.)*)\"\s+(\d+)\s*" line)]
                    (when m [(parse-long (nth m 2)) (nth m 1)]))))
          (line-seq r))))

(defn- count-file
  "The record count a dump states for `name`, or nil when it states none."
  [dir name]
  (let [f (io/file dir (str name "-count.text"))]
    (when (.exists f)
      (some-> (slurp f) str/trim parse-long))))

;;; ── reading one dump file ─────────────────────────────────────────────

(defn- open-cfasl
  ^InputStream [dir name]
  (BufferedInputStream. (io/input-stream (io/file dir (str name ".cfasl")))
                        stream-buffer-bytes))

(defn- skip-header!
  "Consume the copyright string every dump file opens with.

  It is an ordinary CFASL string, so it has to be read rather than seeked past — the
  first record's `dump-id` starts wherever it ends."
  [^InputStream in]
  (let [o (cfasl/read-object in nil)]
    (when-not (string? o)
      (throw (ex-info "dump file did not open with its copyright string"
                      {:type :units/bad-header :got (type o)})))))

(defn records
  "A lazy seq of `[dump-id fields]` for every record in the dump file `name`.

  `field-count` is how many objects follow the id — 1 for a NART formula, 2 for a
  clause-struc, 5 for an assertion — and is the whole of a record's shape.  A record
  whose id is not an integer means the stream is out of step, which stops the read
  loudly rather than skipping."
  [^InputStream in ^long field-count resolvers]
  (lazy-seq
   (let [id (cfasl/read-object in resolvers)]
     (cond
       (= ::cfasl/eof id) nil
       (not (integer? id))
       (throw (ex-info "CFASL dump record did not start with an integer dump-id"
                       {:type :units/desynchronized :got id}))
       :else
       (let [fields (doall (repeatedly field-count #(cfasl/read-object in resolvers)))]
         (cons [id fields] (records in field-count resolvers)))))))

(defn- checked-count!
  "Fail when a dump read fewer records than the dump says it holds."
  [dir name ^long got]
  (when-let [want (count-file dir name)]
    (when (not= want got)
      (throw (ex-info (str name ": read " got " records, dump states " want)
                      {:type :units/count-mismatch :file name :read got :stated want}))))
  got)

;;; ── the tables an assertion is resolved against ───────────────────────

(defn read-table
  "The dump file `name` as a dump-id -> field map, checked against its stated count.

  `count-name` names the count file separately because one dump file does not share its
  stem with its count: the NART formulas are `nart-hl-formula.cfasl` against
  `nart-count.text`.  `select` picks the field to keep out of each record's fields — a
  clause-struc record carries its CNF and then the assertions using it, and only the CNF
  is wanted."
  ;; No primitive hint on `field-count`: Clojure supports those only up to four
  ;; parameters, and this takes six.
  [dir name count-name field-count resolvers select]
  (with-open [in (open-cfasl dir name)]
    (skip-header! in)
    (let [m (persistent!
             (reduce (fn [acc [id fields]] (assoc! acc id (select fields)))
                     (transient {})
                     (records in field-count resolvers)))]
      (checked-count! dir count-name (count m))
      m)))

(defn constant-resolvers
  "Resolvers that turn a constant handle into the `cyc/Name` symbol the translation reads.

  A name the table does not hold reads back as a marker rather than as a symbol, so a
  dump missing a constant is dropped with a reason instead of spelling a term nil."
  [names]
  {:constant (fn [id]
               (if-let [n (get names id)]
                 (symbol "cyc" n)
                 (list :unresolved-constant id)))})

(defn read-nart-formulas
  "`nart-hl-formula.cfasl` as a nart-id -> HL formula map, NART references left as
  markers for `expand-narts` to close over once the whole table is in hand."
  [dir names]
  (read-table dir "nart-hl-formula" "nart" 1 (constant-resolvers names) first))

(defn read-clause-strucs
  "`clause-struc.cfasl` as a clause-struc-id -> CNF map.

  A record is its CNF and then the assertions that use it; the CNF is `(neg-lits
  pos-lits)`, which is how every rule in the KB is stored."
  [dir names]
  (read-table dir "clause-struc" "clause-struc" 2 (constant-resolvers names) first))

(defn- marker?
  [x kind]
  (and (seq? x) (= kind (first x)) (integer? (second x))))

(defn expand-narts
  "Replace every `(:nart id)` marker in `x` with that NART's formula.

  Two bounds, because a chain and a fan-out are different pathologies.
  `nart-depth-limit` stops the chain: a NART naming a NART that names the first would
  otherwise not terminate.  `nart-node-limit` stops the width: a formula mentioning two
  NARTs doubles at every hop, and nesting says nothing about how wide each level is.
  Whichever runs out first, what is left keeps its markers and is dropped by the
  translation — the same answer an unresolvable reference gets."
  [x narts]
  (let [budget (volatile! nart-node-limit)]
    ((fn walk [x ^long depth]
       (cond
         ;; spent: the rest of the form stands as it is, markers and all
         (not (pos? (long (vswap! budget dec)))) x

         (marker? x :nart)
         (let [f (get narts (second x))]
           (if (and f (< depth nart-depth-limit))
             (walk f (inc depth))
             x))

         (seq? x) (apply list (map #(walk % depth) x))
         (vector? x) (mapv #(walk % depth) x)
         :else x))
     x 0)))

;;; ── an assertion's formula ────────────────────────────────────────────

(defn- ground?
  "Whether `x` mentions no variable.  A `?`-initial symbol is vaelii's spelling and the
  dump's alike, which is what makes one test serve both."
  [x]
  (cond
    (symbol? x)      (not (str/starts-with? (name x) "?"))
    (coll? x)        (every? ground? x)
    :else            true))

(defn cnf-formula
  "The formula a CNF states, given the assertion's `truth`.

  Negative literals are the antecedent and positive ones the conclusion, so a clause
  with both is an implication — and that is what carries a rule's direction across,
  which a bare `¬A ∨ ¬B` would not.  All-negative is an integrity constraint and
  several-positive a real disjunction; both come back as the formula they are, for the
  translation to drop under its own reason.

  `truth` is consulted only for a single ground positive literal, where it is the
  difference between a fact and its negation."
  [cnf truth]
  (let [neg-lits (first cnf)
        pos-lits (second cnf)
        neg      (if (next neg-lits) (apply list 'cyc/and neg-lits) (first neg-lits))
        pos      (if (next pos-lits) (apply list 'cyc/or pos-lits) (first pos-lits))]
    (cond
      (and neg pos) (list 'cyc/implies neg pos)
      neg           (list 'cyc/not neg)
      pos           (if (or (next pos-lits) (not (ground? pos)) (not= truth :false))
                      pos
                      (list 'cyc/not pos))
      :else         nil)))

(defn formula-of
  "The formula an assertion states, from its stored `formula-data`.

  Four cases, and the one easy to miss is the first: **a ground atomic formula may be
  stored as a clause too**, and then its formula is the clause's single positive
  literal rather than the implication `cnf-formula` would build.  83,464 of OpenCyc
  4.0's assertions are that shape, so reading the gaf bit without it leaves a
  clause-struc reference where a fact should be."
  [gaf? formula-data clause-strucs truth]
  (let [ref? (marker? formula-data :clause-struc)
        cnf  (when ref? (get clause-strucs (second formula-data)))]
    (cond
      ;; A reference the dump does not hold has no formula — emitting `formula-data`
      ;; here would put a bare `(:clause-struc n)` where a sentence belongs, and the
      ;; translation would read its head as a predicate.
      (and ref? (nil? cnf)) nil
      (and gaf? cnf) (first (second cnf))
      gaf?           formula-data
      cnf            (cnf-formula cnf truth)
      ;; Not a gaf and not a reference: the CNF is stored inline.
      (seq? formula-data) (cnf-formula formula-data truth)
      :else          nil)))

(defn variable-names
  "The authored variable names in an assertion's plist, in HL-variable-id order.

  A rule's literals carry `?var0`, `?var1`, … and the plist carries the names the rule
  was written with — `(\"?RELN\" \"?COL\")` — so a rule can come across reading the way
  its author wrote it.

  Found by **shape** rather than by its key.  The key is an index into a symbol table
  the server installs at runtime, and a dump on disk carries no such table, so the index
  is all a reader has and it is not a name.  The value needs no table: a list of strings
  that every one begins with `?` is a variable-name list and nothing else in a plist is."
  [plist]
  (some (fn [x]
          (when (and (seq? x) (seq x)
                     (every? #(and (string? %) (str/starts-with? % "?")) x))
            (vec x)))
        plist))

(defn rename-variables
  "Replace each `?varN` in `x` with `names[N]`, where the plist supplied one."
  [x names]
  (if (empty? names)
    x
    (letfn [(rename [v]
              (if-let [[_ n] (and (symbol? v) (re-matches #"\?var(\d+)" (name v)))]
                (if-let [nm (get names (parse-long n))] (symbol nm) v)
                v))
            (walk [v]
              (cond
                (seq? v)    (apply list (map walk v))
                (vector? v) (mapv walk v)
                :else       (rename v)))]
      (walk x))))

(defn assertion
  "One assertion record as `{:formula :mt :strength :direction}`, or nil when its
  formula has no reading.

  Cyc's own monotonic-vs-default marking comes across as vaelii's assumption strength,
  and a false truth value as the `not` the record's own `:polarity` will carry."
  [[_id [formula-data mt flags _arguments plist]] narts clause-strucs]
  (let [{:keys [gaf? direction truth strength]} (decode-flags (long flags))
        base    (formula-of gaf? formula-data clause-strucs truth)
        formula (if (and gaf? base (= :false truth))
                  (list 'cyc/not base)
                  base)]
    (when formula
      {:formula   (-> formula
                      (expand-narts narts)
                      (rename-variables (variable-names plist)))
       :mt        (expand-narts mt narts)
       :strength  strength
       :direction direction})))

;;; ── the public read ───────────────────────────────────────────────────

(defn- counted
  "`xs`, tallying into `seen` as it is walked and setting `whole?` at its end.

  Both are facts about the read that its consumer cannot report: a caller stops pulling
  for reasons of its own — `vaelii.foreign.cyc/convert!`'s `:limit` is one — and where
  it stopped is not visible from the records it took."
  [xs seen whole?]
  (lazy-seq
   (if-let [s (seq xs)]
     (do (vswap! seen inc)
         (cons (first s) (counted (rest s) seen whole?)))
     (do (vreset! whole? true) nil))))

(defn with-assertions
  "Read the KB dump directory `dir`, call `(f assertions)` on a lazy seq of its
  assertion maps, and close the dump after.

  The three name/NART/clause tables are read whole first — they are what an assertion's
  ids mean — and then `assertion.cfasl` streams against them, so peak memory is the
  tables and not the KB.  The seq is only valid inside `f`, which is the same contract
  `vaelii.foreign.cycl/with-assertions` keeps.

  A read `f` took to the end of the file is checked against the count the dump states,
  and a short one throws `:units/count-mismatch` rather than returning a smaller KB.  A
  read `f` stopped early is not: the stated count is the whole file's, so comparing a
  deliberate sample against it would make every `:limit` look like a desynchronized
  stream."
  [dir f]
  (let [dir          (io/file dir)
        names        (read-constant-names dir)
        narts        (read-nart-formulas dir names)
        clause-strucs (read-clause-strucs dir names)
        resolvers    (constant-resolvers names)]
    (with-open [in (open-cfasl dir "assertion")]
      (skip-header! in)
      (let [seen   (volatile! 0)
            whole? (volatile! false)
            xs     (->> (counted (records in 5 resolvers) seen whole?)
                        (map #(assertion % narts clause-strucs))
                        (remove nil?))
            result (f xs)]
        (when @whole? (checked-count! dir "assertion" @seen))
        result))))

(defn dump-directory?
  "Whether `dir` looks like a KB dump directory rather than a re-dumped text file.

  The name table and the assertions are what a read needs; a directory holding both is
  one this namespace can read."
  [dir]
  (let [d (io/file dir)]
    (and (.isDirectory d)
         (.exists (io/file d "constant-shell.text"))
         (.exists (io/file d "assertion.cfasl")))))
