(ns vaelii.foreign.units-test
  "Read a real OpenCyc KB dump directory.

  **Why the fixture is a real dump and not an invented one.**  Everywhere else in this
  suite the test data is hand-authored, because a reader's fixture should not come from
  the thing it reads.  A binary format cannot honour that at this level: authoring a
  CFASL dump by hand means encoding it with the same beliefs the reader decodes it
  with, so a wrong belief round-trips and the test passes.  The two halves of the check
  are therefore split — `vaelii.foreign.cfasl-test` states every encoding as literal
  bytes, independent of any Cyc artifact, and this namespace reads a dump nobody here
  wrote.

  `test/resources/cyc-tiny` is the smallest real one: 717 constants, 8,899 assertions,
  504 clause-strucs and 2 NARTs, published by the LarKC consortium under Apache-2.0
  (`licenses/THIRD-PARTY.md`).  Small enough to read in a unit test, and complete enough
  to exercise every path — facts, rules, negations, NARTs and the shapes with no vaelii
  reading.

  **The dump is its own oracle.**  Nothing frames a CFASL object but its own opcode, so
  a mis-sized payload does not fail where the mistake is — it desynchronizes the file
  and everything after decodes to plausible junk. Each dump states its own record
  counts, and reading exactly that many and then hitting end-of-stream cleanly is the
  check that the whole traversal stayed in step."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [vaelii.foreign.units :as units]))

(def ^:private dump-dir
  "The vendored cyc-tiny dump."
  (io/file "test/resources/cyc-tiny"))

(defn- stated-count
  "What the dump says it holds, read from its own count file."
  [name]
  (-> (io/file dump-dir (str name "-count.text")) slurp str/trim parse-long))

;;; ── flags ─────────────────────────────────────────────────────────────

(deftest one-integer-carries-gafhood-direction-and-truth
  ;; Bit 0 is gafhood, bits 1-2 the direction, bits 3-5 the truth value — and the truth
  ;; value carries Cyc's monotonic-vs-default marking, which is vaelii's assumption
  ;; strength under another name.
  (is (= {:gaf? true :direction :forward :truth :true :strength :monotonic}
         (units/decode-flags 3))
      "3 = gaf, forward, true-mon — the commonest assertion in the KB")
  (testing "gafhood is the low bit"
    (is (:gaf? (units/decode-flags 1)))
    (is (not (:gaf? (units/decode-flags 2)))))
  (testing "direction"
    (is (= :backward (:direction (units/decode-flags 2r000))))
    (is (= :forward (:direction (units/decode-flags 2r010))))
    (is (= :code (:direction (units/decode-flags 2r100)))))
  (testing "truth and strength come from one code"
    (is (= [:true :monotonic] ((juxt :truth :strength) (units/decode-flags 2r000000))))
    (is (= [:true :default] ((juxt :truth :strength) (units/decode-flags 2r001000))))
    (is (= [:unknown :default] ((juxt :truth :strength) (units/decode-flags 2r010000))))
    (is (= [:false :default] ((juxt :truth :strength) (units/decode-flags 2r011000))))
    (is (= [:false :monotonic] ((juxt :truth :strength) (units/decode-flags 2r100000))))))

;;; ── rebuilding a formula from a clause ────────────────────────────────

(deftest a-clause-with-both-polarities-is-an-implication
  ;; Negative literals are the antecedent and positive ones the conclusion. Keeping that
  ;; shape is what carries a rule's direction across — a bare disjunction says nothing
  ;; about which literal the author wrote as the conclusion.
  (is (= '(cyc/implies (cyc/isa ?x cyc/Dog) (cyc/noisy ?x))
         (units/cnf-formula '(((cyc/isa ?x cyc/Dog)) ((cyc/noisy ?x))) :true)))
  (testing "several antecedents conjoin"
    (is (= '(cyc/implies (cyc/and (cyc/a ?x) (cyc/b ?x)) (cyc/c ?x))
           (units/cnf-formula '(((cyc/a ?x) (cyc/b ?x)) ((cyc/c ?x))) :true))))
  (testing "several conclusions disjoin — a claim vaelii cannot state, but read as-is"
    (is (= '(cyc/implies (cyc/a ?x) (cyc/or (cyc/b ?x) (cyc/c ?x)))
           (units/cnf-formula '(((cyc/a ?x)) ((cyc/b ?x) (cyc/c ?x))) :true)))))

(deftest a-one-sided-clause-is-a-constraint-or-a-fact
  (testing "all-negative is an integrity constraint"
    (is (= '(cyc/not (cyc/and (cyc/a ?x) (cyc/b ?x)))
           (units/cnf-formula '(((cyc/a ?x) (cyc/b ?x)) ()) :true))))
  (testing "a single positive literal is the fact itself"
    (is (= '(cyc/p cyc/A) (units/cnf-formula '(() ((cyc/p cyc/A))) :true))))
  (testing "truth is consulted only for a single GROUND positive literal"
    (is (= '(cyc/not (cyc/p cyc/A))
           (units/cnf-formula '(() ((cyc/p cyc/A))) :false)))
    (is (= '(cyc/p ?x)
           (units/cnf-formula '(() ((cyc/p ?x))) :false))
        "a variable makes it non-ground, so falsity does not wrap it"))
  (testing "an empty clause has no formula"
    (is (nil? (units/cnf-formula '(() ()) :true)))))

(deftest a-gaf-may-be-stored-as-a-clause-too
  ;; The case that is easy to miss: the gaf bit says how to read `formula-data`, not
  ;; whether it is a reference. 83,322 of OpenCyc 4.0's assertions are a gaf holding a
  ;; clause-struc, and reading the bit without this leaves a bare reference where a fact
  ;; should be.
  (let [clause-strucs {7 '(() ((cyc/genls cyc/Dog cyc/Mammal)))}]
    (is (= '(cyc/genls cyc/Dog cyc/Mammal)
           (units/formula-of true '(:clause-struc 7) clause-strucs :true))
        "the single positive literal, not an implication")
    (is (= '(cyc/genls cyc/Dog cyc/Mammal)
           (units/formula-of false '(:clause-struc 7) clause-strucs :true))
        "as a rule it is the same literal, since there are no antecedents"))
  (testing "a gaf holding its formula outright"
    (is (= '(cyc/genls cyc/Dog cyc/Mammal)
           (units/formula-of true '(cyc/genls cyc/Dog cyc/Mammal) {} :true))))
  (testing "a reference the dump does not hold has no formula"
    (is (nil? (units/formula-of true '(:clause-struc 999) {} :true)))))

;;; ── NARTs ─────────────────────────────────────────────────────────────

(defn- time-bounded
  "Call `f` on another thread and return what it returned, or nil if it had not finished
  within `ms`.  A guard whose job is to make an expansion terminate cannot be checked by
  waiting for that expansion to terminate."
  ([f] (time-bounded f 30000))
  ([f ms]
   (let [fut (future (f))]
     (try (deref fut ms nil)
          (finally (future-cancel fut))))))

(deftest a-nart-reference-is-expanded-once-the-whole-table-is-in-hand
  ;; A NART's formula may name a NART defined later in the file, so resolving during the
  ;; read would render that forward reference unresolved and then propagate it into every
  ;; assertion mentioning the term.
  (let [narts {0 '(cyc/FruitFn cyc/AppleTree)
               1 '(cyc/JuiceFn (:nart 0))}]
    (is (= '(cyc/FruitFn cyc/AppleTree) (units/expand-narts '(:nart 0) narts)))
    (is (= '(cyc/isa (cyc/FruitFn cyc/AppleTree) cyc/Fruit)
           (units/expand-narts '(cyc/isa (:nart 0) cyc/Fruit) narts))
        "expanded in place, however deep in the formula")
    (is (= '(cyc/JuiceFn (cyc/FruitFn cyc/AppleTree))
           (units/expand-narts '(:nart 1) narts))
        "a NART naming a NART expands through"))

  (testing "a cycle keeps its marker instead of not terminating"
    (let [narts {0 '(cyc/F (:nart 1)) 1 '(cyc/G (:nart 0))}]
      (is (re-find #":nart" (pr-str (units/expand-narts '(:nart 0) narts))))))

  (testing "a reference the table does not hold keeps its marker, for the translation
            to drop under a reason"
    (is (= '(:nart 99) (units/expand-narts '(:nart 99) {}))))

  (testing "a NART naming itself twice is bounded by work done, not by nesting"
    ;; A depth limit alone does not bound this one.  Each hop doubles, so a formula that
    ;; mentions itself twice is 2^depth nodes — sixteen million at a depth limit of 24,
    ;; which is an OutOfMemoryError rather than a slow answer.  The node budget is what
    ;; makes the width case terminate the way the chain case already did.
    (let [narts {0 '(cyc/F (:nart 0) (:nart 0))}
          out   (time-bounded (fn [] (units/expand-narts '(:nart 0) narts)))]
      (is (some? out) "expansion did not return")
      (is (re-find #":nart" (pr-str out))
          "and what it could not finish keeps its marker, like any other cycle"))))

;;; ── the dump ──────────────────────────────────────────────────────────

(deftest the-name-table-is-plain-text
  ;; The dump writes its constant names twice, once as CFASL and once as text, and the
  ;; text one is complete — so the whole name table costs a line-by-line read.
  (let [names (units/read-constant-names dump-dir)]
    (is (= (stated-count "constant") (count names))
        "every constant the dump says it holds")
    (is (= "AbsoluteValueFn" (get names 0)))
    (is (every? string? (vals names)))
    (is (every? integer? (keys names)))))

(deftest the-tables-read-to-their-stated-counts
  (let [names (units/read-constant-names dump-dir)]
    (testing "NART formulas, whose file does not share a stem with its count file"
      (let [narts (units/read-nart-formulas dump-dir names)]
        (is (= (stated-count "nart") (count narts)))
        (is (= '(cyc/CollectionRuleTemplateFn cyc/ArgIsaPredicate) (get narts 0))
            "a NART's head is a function and its formula is resolved to names")))
    (testing "clause-strucs, keeping the CNF and discarding the assertions using it"
      (let [cs (units/read-clause-strucs dump-dir names)]
        (is (= (stated-count "clause-struc") (count cs)))
        (is (every? #(and (seq? %) (= 2 (count %))) (vals cs))
            "a CNF is (neg-lits pos-lits)")))))

(deftest every-assertion-in-the-dump-comes-back
  ;; The count is the oracle: reading exactly as many records as the dump states, and
  ;; then reaching end-of-stream cleanly, is what says the traversal never lost step.
  (units/with-assertions
    dump-dir
    (fn [as]
      (let [v (vec as)]
        (is (= (stated-count "assertion") (count v))
            "every assertion, none dropped and none invented")

        (testing "each carries the four fields the translation reads"
          (is (every? #(every? % [:formula :mt :strength :direction]) v)))

        (testing "strengths and directions are the dump's own vocabulary"
          (is (= #{:monotonic :default} (set (map :strength v))))
          (is (every? #{:forward :backward :code} (map :direction v))))

        (testing "constants resolve to cyc/ symbols, so nothing is left as a handle"
          (let [s (pr-str (take 2000 v))]
            (is (not (re-find #"\(:constant " s)))
            (is (not (re-find #"\(:unresolved-constant " s)))
            (is (not (re-find #"\(:nart " s)) "NARTs are expanded, not referenced")
            (is (not (re-find #"\(:clause-struc " s))
                "a clause reference is resolved into the formula it holds")))

        (testing "the shapes the translation is built to read are all present"
          (let [head #(when (seq? (:formula %)) (first (:formula %)))]
            (is (pos? (count (filter #(= 'cyc/genls (head %)) v))) "facts")
            (is (pos? (count (filter #(= 'cyc/implies (head %)) v))) "rules")
            (is (pos? (count (filter #(= 'cyc/not (head %)) v))) "negations")))

        (testing "a rule comes across reading the way its author wrote it"
          (let [rules (filter #(and (seq? (:formula %))
                                    (= 'cyc/implies (first (:formula %)))) v)]
            (is (some #(= '(cyc/implies (cyc/argIsa ?RELN 1 ?COL)
                                        (cyc/arg1Isa ?RELN ?COL))
                          (:formula %))
                      rules)
                "the plist's authored names, not the stored ?varN")
            (is (not-any? #(re-find #"\?var\d" (pr-str (:formula %))) rules)
                "every rule in this dump names its variables")))))))

(deftest a-short-read-is-an-error-and-not-a-smaller-kb
  ;; A desynchronized stream still yields records; what it stops yielding is the right
  ;; number of them. Truncating the count file is the cheapest way to prove the check
  ;; fires, since it is the comparison and not the read that is under test here.
  (let [tmp (io/file (System/getProperty "java.io.tmpdir")
                     (str "cyc-tiny-short-" (System/nanoTime)))]
    (try
      (.mkdirs tmp)
      (doseq [f (.listFiles dump-dir)]
        (io/copy f (io/file tmp (.getName f))))
      (spit (io/file tmp "assertion-count.text") "12345\n")
      (is (thrown? clojure.lang.ExceptionInfo
                   (units/with-assertions tmp (fn [as] (doall as)))))
      (is (= :units/count-mismatch
             (try (units/with-assertions tmp (fn [as] (doall as)))
                  (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))
          "and it says which check failed")
      (finally
        (run! #(.delete ^java.io.File %) (.listFiles tmp))
        (.delete tmp)))))

(deftest a-read-that-stops-early-is-not-a-short-read
  ;; The count check answers "did this traversal stay in step", which is a question only
  ;; a read that reached the end of the file can be asked.  A caller that stops early has
  ;; not lost its place, and raising at it makes `--limit` — the only cheap way to look
  ;; at a 780 MB dump — impossible on the format it exists for.
  (is (= 20 (count (units/with-assertions dump-dir (fn [as] (doall (take 20 as))))))
      "twenty of 8,899 read, and no count-mismatch")
  (is (= (stated-count "assertion")
         (count (units/with-assertions dump-dir (fn [as] (doall as)))))
      "and a full read still checks itself against the count the dump states"))

(deftest a-dump-directory-is-recognised-by-what-a-read-needs
  (is (units/dump-directory? dump-dir))
  (is (not (units/dump-directory? (io/file dump-dir "constant-shell.text")))
      "a file is not a dump directory")
  (is (not (units/dump-directory? (io/file "test/resources")))
      "nor is a directory without the name table and the assertions"))
