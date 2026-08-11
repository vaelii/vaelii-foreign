(ns vaelii.foreign.cyc-test
  "Convert a CycL assertion dump into a vaelii KB corpus, and load it.

  Two contracts.  The **translation** is a function of the dump alone: a constant's
  role decides its spelling, a Cyc predicate decides the sentence shape, and what has
  no vaelii reading is dropped with a counted reason rather than mangled.  The
  **corpus loads**: `convert!` followed by `load-dir!` puts believed content in a KB,
  which is the only claim that matters — a conversion nothing can read is a
  conversion that failed.

  The fixtures are hand-authored CycL, never a slice of the real OpenCyc dump: a
  reader is a capability, and its checked-in test data is invented (the same rule
  `vaelii.import-test` follows)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [vaelii.core :as v]
            [vaelii.foreign.corpus :as corpus]
            [vaelii.foreign.cyc :as cyc]
            [vaelii.foreign.cycl :as cycl]
            [vaelii.foreign.term :as term]
            [vaelii.foreign.test-util :as tu]
            [vaelii.impl.core-context :as core-context]
            [vaelii.impl.naming :as nm])
  (:import (java.io File StringReader)))

;;; ── fixtures ──────────────────────────────────────────────────────────

(def ^:private sample-dump
  "A miniature CycL KB, in the shape a text re-dump carries: a taxonomy,
  a microtheory topology, argument constraints, predicate metadata, a rule, and two
  assertions that carry no knowledge (a SubL hook and a bookkeeping term)."
  (str/join
   "\n"
   [";;; a comment line the reader skips"
    "(ke-assert '(#$isa #$Dog #$Collection) #$BaseKB :monotonic :forward)"
    "(ke-assert '(#$genls #$Dog #$Mammal) #$MammalBiologyMt :monotonic :forward)"
    "(ke-assert '(#$genls #$Mammal #$Animal) #$MammalBiologyMt :monotonic :forward)"
    "(ke-assert '(#$disjointWith #$Dog #$DomesticCat) #$MammalBiologyMt :monotonic :forward)"
    "(ke-assert '(#$isa #$Rover #$Dog) #$MammalBiologyMt :default :forward)"
    "(ke-assert '(#$genlMt #$MammalBiologyMt #$BaseKB) #$BaseKB :monotonic :forward)"
    "(ke-assert '(#$arg1Isa #$ownerOf #$Person-Legal) #$BaseKB :monotonic :forward)"
    "(ke-assert '(#$argIsa #$ownerOf 2 #$Dog) #$BaseKB :monotonic :forward)"
    "(ke-assert '(#$isa #$siblingOf #$SymmetricBinaryPredicate) #$BaseKB :monotonic :forward)"
    "(ke-assert '(#$comment #$Dog \"A domesticated canine,\ntwo lines long.\") #$BaseKB :monotonic :forward)"
    "(ke-assert '(#$ownerOf #$Alice-Person #$Rover) #$MammalBiologyMt :default :forward)"
    "(ke-assert '(#$implies (#$isa ?ANIMAL #$Dog) (#$isa ?ANIMAL #$Mammal))"
    "           #$MammalBiologyMt :default :forward)"
    "(ke-assert '(#$afterAdding #$ownerOf (#$SubLQuoteFn clear-owner-caches)) #$BaseKB :default :forward)"
    "(ke-assert '(#$termOfUnit (#$GroupFn #$Dog) (#$GroupFn #$Dog)) #$BaseKB :monotonic :forward)"
    "(ke-assert '(#$isa #$GroupFn #$ReifiableFunction) #$BaseKB :monotonic :forward)"]))

(defn- with-dump-file
  "Write `text` as a dump file in a scratch directory and call `(f dump-path dir)`."
  [text f]
  (tu/temp-dir "vaelii-cyc"
               (fn [^File dir]
                 (let [dump (io/file dir "dump.sexpr")]
                   (spit dump text)
                   (f (str dump) dir)))))

(defn- read-formulas
  "Every assertion in a dump string."
  [text]
  (doall (cycl/assertions (StringReader. text))))

(defn- converted
  "Convert `sample-dump` and call `(f dir report)` on the corpus directory."
  [f]
  (with-dump-file sample-dump
    (fn [dump ^File dir]
      (let [out (io/file dir "corpus")
            report (cyc/convert! dump (str out) {})]
        (f out report)))))

(defn- sentences-in
  "The sentences of one corpus file, or nil when the file is absent."
  [^File dir fname]
  (let [f (io/file dir "kb" fname)]
    (when (.exists f)
      (set (map read-string (remove str/blank? (str/split-lines (slurp f))))))))

;;; ── the reader ────────────────────────────────────────────────────────

(deftest reads-cycl-syntax
  (testing "a constant, a variable and a microtheory come back distinguishable"
    (let [[a] (read-formulas "(ke-assert '(#$genls #$Dog #$Mammal) #$BaseKB :monotonic :forward)")]
      (is (= '(cyc/genls cyc/Dog cyc/Mammal) (:formula a)))
      (is (= 'cyc/BaseKB (:mt a)))
      (is (= :monotonic (:strength a)))
      (is (= :forward (:direction a)))
      (is (every? cycl/constant? [(first (:formula a)) (:mt a)]))))

  (testing "a variable is already vaelii's spelling, and is not a Cyc constant"
    (let [[a] (read-formulas "(ke-assert '(#$isa ?X #$Dog) #$BaseKB :default :forward)")]
      (is (= '(cyc/isa ?X cyc/Dog) (:formula a)))
      (is (not (cycl/constant? (second (:formula a)))))))

  (testing "a string spans lines and keeps its escapes"
    (let [[a] (read-formulas "(ke-assert '(#$comment #$Dog \"one\ntwo \\\"quoted\\\"\") #$BaseKB :default :forward)")]
      (is (= "one\ntwo \"quoted\"" (nth (:formula a) 2)))))

  (testing "numbers: integers, a Lisp double, and a bignum past a long"
    (let [[a] (read-formulas "(ke-assert '(#$n 12 -3 1.5d0 18446744073709551615) #$BaseKB :default :forward)")]
      (is (= [12 -3 1.5] (take 3 (drop 1 (:formula a)))))
      (is (= (bigint "18446744073709551615") (last (:formula a))))))

  (testing "a bare SubL symbol is marked as code, and an unresolved marker survives"
    (let [[a b] (read-formulas
                 (str "(ke-assert '(#$afterAdding #$p (#$SubLQuoteFn do-thing)) #$BaseKB :default :forward)\n"
                      "(ke-assert '(#$isa (:nart 7) #$Dog) #$BaseKB :default :forward)"))]
      (is (cycl/subl? (last (last (:formula a)))))
      (is (= '(:nart 7) (second (:formula b))))))

  (testing "a non-ke-assert top-level form is not an assertion"
    (is (empty? (read-formulas "(in-package :clyc)\n")))))

;;; ── roles and spelling ────────────────────────────────────────────────

(deftest classifies-constants-by-role
  (let [role (->> (read-formulas sample-dump) cyc/classify cyc/roles)]
    (is (= :type       (role 'cyc/Dog))        "arg 2 of isa, arg 1 of genls")
    (is (= :type       (role 'cyc/Mammal))     "both sides of genls are collections")
    (is (= :type       (role 'cyc/Person-Legal)) "arg 2 of arg1Isa")
    (is (= :predicate  (role 'cyc/ownerOf))    "a formula head")
    (is (= :predicate  (role 'cyc/siblingOf))  "metadata metatype membership names a predicate")
    (is (= :context    (role 'cyc/BaseKB))     "the microtheory slot")
    (is (= :context    (role 'cyc/MammalBiologyMt)))
    (is (= :function   (role 'cyc/GroupFn))    "declared a ReifiableFunction")
    (is (= :individual (role 'cyc/Rover))      "no evidence: an individual by residue")))

(deftest spells-each-role-to-its-naming-invariant
  (is (= "domesticated_animal" (term/spell :type "DomesticatedAnimal")))
  (is (= "agent_partially_tangible" (term/spell :type "Agent-PartiallyTangible"))
      "punctuation vaelii's symbols do not allow becomes a word boundary")
  (is (= "owl_class" (term/spell :type "owl:Class")))
  (is (= "us_state" (term/spell :type "USState")) "an acronym is one word")
  (is (= "prettyStringCanonical" (term/spell :predicate "prettyString-Canonical")))
  (is (= "OhioState" (term/spell :individual "Ohio-State")))
  (is (= "EnglishContext" (term/spell :context "EnglishMt")) "the Mt suffix becomes Context")
  (is (= "BaseKBContext" (term/spell :context "BaseKB")))

  (testing "a name that would break its invariant is prefixed, not mangled"
    (is (= "t_2000_census" (term/spell :type "2000Census")))
    (is (str/ends-with? (term/spell :individual "SomeContext") "ContextTerm")
        "an individual must not read as a context"))

  (testing "distinct Cyc names never collide after spelling"
    (let [table (cyc/name-table '{cyc/Ohio-State :individual cyc/OhioState :individual} #{} #{})]
      (is (= 2 (count (set (map :term (vals table)))))))))

;;; ── the sentence mapping ──────────────────────────────────────────────

(defn- translate-all
  "Every assertion of the sample dump, translated, grouped by its Cyc predicate."
  []
  (let [assertions (read-formulas sample-dump)
        names      (cyc/name-table (cyc/roles (cyc/classify assertions)) #{} #{})]
    (->> assertions
         (map (fn [a] [(first (:formula a)) (cyc/translate a names {})]))
         (reduce (fn [m [pred r]] (update m pred (fnil conj []) r)) {}))))

(deftest translates-cyc-vocabulary-to-vaelii-vocabulary
  (let [t   (translate-all)
        one #(first (t %))
        of  #(set (mapcat :sentences (t %)))
        all (set (mapcat :sentences (mapcat val t)))]
    (is (= '#{(genl dog mammal) (genl mammal animal)} (of 'cyc/genls)))
    (is (= '#{(disjoint dog domestic_cat)} (of 'cyc/disjointWith)))
    (is (= '#{(argIsa ownerOf 1 person_legal)} (of 'cyc/arg1Isa))
        "the argNIsa family folds into one positional argIsa")
    (is (= '#{(argIsa ownerOf 2 dog)} (of 'cyc/argIsa)))
    (is (= '#{(ownerOf AlicePerson Rover)} (of 'cyc/ownerOf)))

    (testing "a type membership is a unary predicate application"
      (is (contains? (of 'cyc/isa) '(dog Rover))))

    (testing "a metadata metatype is a declaration as well as a membership"
      (is (contains? all '(symmetric siblingOf)))
      (is (contains? all '(reifiableFunction GroupFn))
          "Cyc states the NAT declaration as metatype membership; vaelii reads a predicate"))

    (testing "a comment keeps its string"
      (is (= '#{(comment dog "A domesticated canine,\ntwo lines long.")}
             (of 'cyc/comment))))

    (testing "the context topology is separated from any one context"
      (is (= :topology (:context (one 'cyc/genlMt))))
      (is (= '#{(genlContext MammalBiologyContext BaseKBContext)} (of 'cyc/genlMt))))

    (testing "a rule's body is rewritten with the same mapping as a fact"
      (is (= '#{(set/forwardRule (set/defaultRule (implies (dog ?ANIMAL) (mammal ?ANIMAL))))}
             (of 'cyc/implies))
          "an antecedent that kept Cyc's isa could never match the stored (dog Rover)"))

    (testing "what carries no knowledge is dropped under the reason it is dropped for"
      ;; not one shared `:excluded`: SubL a trigger runs and a NART definition this
      ;; reader writes itself are refused for unlike reasons, and a report that called
      ;; them the same thing could not be acted on
      (is (= :trigger-code (:dropped (one 'cyc/afterAdding))))
      (is (= :nart-definition (:dropped (one 'cyc/termOfUnit)))))

    (testing "strength rides through as vaelii's assumption strength"
      (is (= :monotonic (:strength (one 'cyc/genls))))
      (is (= :default (:strength (one 'cyc/ownerOf)))))))

(deftest a-limit-samples-the-binary-dump-instead-of-refusing-it
  ;; The one place in this namespace that reads the vendored dump rather than authored
  ;; CycL, because this is the path `--limit` is for and the path it broke on: the record
  ;; count a dump states is checked against what was read, and a read the caller stopped
  ;; early fails that check by construction.  On a 780 MB distribution `--limit` is the
  ;; only cheap way to see whether a conversion is working at all.
  (tu/temp-dir
   "vaelii-cyc-limit"
   (fn [^File dir]
     (let [dump    "test/resources/cyc-tiny"
           limited (cyc/convert! dump (str (io/file dir "some")) {:limit 20})
           full    (cyc/convert! dump (str (io/file dir "all")) {})]
       (is (= 20 (:assertions limited)) "the limit is in assertions, and it is honoured")
       (is (= 8899 (:assertions full)) "and a full read still reads every one the dump states")
       (is (< (:sentences limited) (:sentences full)))))))

(deftest a-predicate-given-the-wrong-number-of-arguments-is-dropped
  ;; `genls`, `genlPreds`, `genlMt` and `disjointWith` are all binary, and a dump that
  ;; states one with a single argument used to translate to a sentence with a literal
  ;; `nil` in the missing slot — `(genl a nil)` written into the corpus, and for `genlMt`
  ;; into `Topology.txt`, where it is the context wiring the whole load reads first.  A
  ;; shape the reader cannot read is a counted drop, not a hole in a sentence.
  (let [names (cyc/name-table '{cyc/A :type cyc/B :type cyc/M :context
                                cyc/p :predicate cyc/BaseKB :context}
                              #{} #{})
        one   (fn [text] (cyc/translate (first (read-formulas text)) names {}))]
    (doseq [[pred arg] [["genls" "#$A"] ["genlPreds" "#$p"]
                        ["genlMt" "#$M"] ["disjointWith" "#$A"]]]
      (testing pred
        (let [r (one (str "(ke-assert '(#$" pred " " arg ") #$BaseKB :monotonic :forward)"))]
          (is (= :untranslatable (:dropped r)))
          (is (empty? (:sentences r))
              "and nothing reaches the corpus with a nil in it"))))

    (testing "while the well-formed two-argument shape is unchanged"
      (is (= '[(genl a b)]
             (vec (:sentences (one "(ke-assert '(#$genls #$A #$B) #$BaseKB :monotonic :forward)")))))
      (is (= '[(genlContext MContext BaseKBContext)]
             (vec (:sentences (one "(ke-assert '(#$genlMt #$M #$BaseKB) #$BaseKB :monotonic :forward)"))))))))

(deftest drops-what-has-no-vaelii-reading
  (let [names (cyc/name-table '{cyc/p :predicate cyc/BaseKB :context cyc/Dog :type} #{} #{})
        drop  (fn [text] (:dropped (cyc/translate (first (read-formulas text)) names {})))]
    (is (= :subl-code
           (drop "(ke-assert '(#$p (#$SubLQuoteFn code)) #$BaseKB :default :forward)")))
    (is (= :unresolved
           (drop "(ke-assert '(#$p (:nart 7)) #$BaseKB :default :forward)")))
    (is (= :unresolved-mt
           (drop "(ke-assert '(#$p #$Dog) (#$MtSpace #$Dog) :default :forward)"))
        "a microtheory with no name in the table has no context to assert into")
    (is (= :non-ground
           (drop "(ke-assert '(#$p ?X) #$BaseKB :default :forward)"))
        "vaelii refuses a non-ground fact: it would match every goal of its shape")
    (is (= :code-rule
           (drop "(ke-assert '(#$implies (#$p #$Dog) (#$p #$Dog)) #$BaseKB :monotonic :code)"))
        "a :code rule is implemented in SubL, not stated")))

;;; ── an assertion read as the clause it is ─────────────────────────────
;;
;; Cyc holds an assertion as a CNF clause — positive literals and negative ones — and
;; that split is vaelii's own polarity, so the shapes a clause can take are the cases
;; worth pinning down.  Three of them have a vaelii reading and two do not, and the two
;; are refused for *different* reasons: a real disjunction is a claim we cannot state,
;; an all-negative clause is an integrity constraint we have no form for.

(def ^:private clause-names
  (cyc/name-table '{cyc/p :predicate cyc/q :predicate cyc/r :predicate
                    cyc/BaseKB :context cyc/OtherMt :context
                    cyc/Dog :type cyc/Rover :individual}
                  #{} #{}))

(defn- clause
  "Translate one hand-written assertion under `clause-names`."
  [text]
  (cyc/translate (first (read-formulas text)) clause-names {}))

(deftest a-negative-unit-clause-is-a-fact-at-the-other-polarity
  (testing "vaelii stores (not S) as a first-class sentex, so there is nothing to lose"
    (is (= '[(not (p Rover))]
           (:sentences (clause "(ke-assert '(#$not (#$p #$Rover)) #$BaseKB :default :forward)")))))

  (testing "the negated literal goes through the same predicate mapping as a positive one"
    (is (= '[(not (dog Rover))]
           (:sentences (clause "(ke-assert '(#$not (#$isa #$Rover #$Dog)) #$BaseKB :default :forward)")))
        "an (isa I C) under a not is still vaelii's unary (c I)"))

  (testing "and is held to the same groundness a positive fact is"
    (is (= :non-ground
           (:dropped (clause "(ke-assert '(#$not (#$p ?X)) #$BaseKB :default :forward)"))))))

(deftest a-horn-disjunction-is-a-rule
  (testing "one positive literal and n negative ones is exactly an implication"
    (is (= '[(set/forwardRule (set/defaultRule (implies (p ?X) (q ?X))))]
           (:sentences (clause (str "(ke-assert '(#$or (#$not (#$p ?X)) (#$q ?X)) "
                                    "#$BaseKB :default :forward)"))))))

  (testing "several negative literals join as a conjunctive antecedent"
    (is (= '[(implies (and (p ?X) (q ?X)) (r ?X))]
           (:sentences (clause (str "(ke-assert '(#$or (#$not (#$p ?X)) (#$not (#$q ?X)) (#$r ?X)) "
                                    "#$BaseKB :monotonic nil)"))))
        "a monotonic assertion with no direction wraps in nothing"))

  (testing "a positive unit clause is just the fact"
    (is (= '[(p Rover)]
           (:sentences (clause "(ke-assert '(#$or (#$p #$Rover)) #$BaseKB :default :forward)")))))

  (testing "a non-ground positive unit is refused as a fact, not stored as a universal"
    (is (= :non-ground
           (:dropped (clause "(ke-assert '(#$or (#$p ?X)) #$BaseKB :default :forward)"))))))

(deftest a-clause-with-no-vaelii-reading-says-which-kind-it-was
  (testing "several positive literals is a real disjunction"
    (is (= :disjunction
           (:dropped (clause "(ke-assert '(#$or (#$p #$Rover) (#$q #$Rover)) #$BaseKB :default :forward)")))))

  (testing "no positive literal is an integrity constraint"
    (is (= :all-negative-clause
           (:dropped (clause (str "(ke-assert '(#$or (#$not (#$p ?X)) (#$not (#$q ?X))) "
                                  "#$BaseKB :default :forward)")))))
    (is (= :all-negative-clause
           (:dropped (clause (str "(ke-assert '(#$not (#$and (#$p #$Rover) (#$q #$Rover))) "
                                  "#$BaseKB :default :forward)"))))
        "a negated conjunction is the same clause written the other way")))

(deftest a-conjunction-is-n-assertions
  (testing "each conjunct translates on its own, and one may state two things"
    (is (= '[(p Rover) (q Rover)]
           (:sentences (clause (str "(ke-assert '(#$and (#$p #$Rover) (#$q #$Rover)) "
                                    "#$BaseKB :default :forward)")))))
    (is (= '[(dog Rover) (p Rover)]
           (:sentences (clause (str "(ke-assert '(#$and (#$isa #$Rover #$Dog) (#$p #$Rover)) "
                                    "#$BaseKB :default :forward)"))))))

  (testing "a conjunct that belongs to another context splits the assertion, so it is refused"
    (is (= :mixed-context-conjunction
           (:dropped (clause (str "(ke-assert '(#$and (#$genlMt #$OtherMt #$BaseKB) (#$p #$Rover)) "
                                  "#$BaseKB :default :forward)"))))
        "genlMt is routed to the topology; the rest of the conjunction is not"))

  (testing "a conjunct with no reading takes the whole conjunction with it"
    (is (= :non-ground
           (:dropped (clause (str "(ke-assert '(#$and (#$p #$Rover) (#$q ?X)) "
                                  "#$BaseKB :default :forward)")))))))

(deftest the-clause-shapes-load-into-a-kb
  (testing "what the translation produces is what the engine actually accepts"
    (with-dump-file
      (str/join
       "\n"
       ["(ke-assert '(#$isa #$Dog #$Collection) #$BaseKB :monotonic :forward)"
        "(ke-assert '(#$isa #$Rover #$Dog) #$BaseKB :default :forward)"
        "(ke-assert '(#$not (#$barksAt #$Rover #$Rover)) #$BaseKB :default :forward)"
        "(ke-assert '(#$and (#$isa #$Muffet #$Dog) (#$barksAt #$Muffet #$Rover)) #$BaseKB :default :forward)"
        "(ke-assert '(#$or (#$not (#$isa ?X #$Dog)) (#$noisy ?X)) #$BaseKB :default :forward)"])
      (fn [dump ^File dir]
        (let [out (io/file dir "corpus")]
          (cyc/convert! dump (str out) {})
          (tu/with-cleared-kb [kb tu/fresh]
            (core-context/load-into kb)
            (let [loaded (cyc/load-dir! kb (str out) {:chain? true})]
              (is (zero? (:refused loaded))
                  (str "every clause shape the translation emits must assert: "
                       (pr-str (:refusals loaded))))
              (is (seq (v/sentexes-matching kb '(not (barksAt Rover Rover)) 'BaseKBContext))
                  "the negative unit clause is believed as a negative fact")
              (is (seq (v/sentexes-matching kb '(barksAt Muffet Rover) 'BaseKBContext))
                  "both halves of the conjunction landed")
              (is (seq (v/sentexes-matching kb '(noisy Rover) 'BaseKBContext))
                  "the Horn disjunction fired as the rule it is"))))))))

(deftest mints-a-context-for-a-computed-microtheory
  (let [text "(ke-assert '(#$genls #$Magic #$SacredPractice) (#$MtOfBeliefSystemFn #$Wicca) :monotonic :forward)"
        assertions (read-formulas text)
        evidence   (cyc/classify assertions)
        names      (cyc/name-table (cyc/roles (dissoc evidence :nat-context :nat-type))
                                   (:nat-context evidence) (:nat-type evidence))
        r          (cyc/translate (first assertions) names {})]
    (is (contains? (:nat-context evidence) '(cyc/MtOfBeliefSystemFn cyc/Wicca)))
    (is (= 'MtOfBeliefSystemFnWiccaContext (:context r))
        "vaelii names a context with a symbol, so a computed one is reified")
    (is (= '[(genl magic sacred_practice)] (:sentences r))))

  (testing "and the topology names it by the same symbol"
    (let [text (str "(ke-assert '(#$genlMt (#$MtOfBeliefSystemFn #$Wicca) #$BaseKB)"
                    " #$BaseKB :monotonic :forward)")
          assertions (read-formulas text)
          evidence   (cyc/classify assertions)
          names      (cyc/name-table (cyc/roles (dissoc evidence :nat-context :nat-type))
                                     (:nat-context evidence) (:nat-type evidence))
          r          (cyc/translate (first assertions) names {})]
      (is (= '[(genlContext MtOfBeliefSystemFnWiccaContext BaseKBContext)] (:sentences r))
          "a computed microtheory on either side of genlMt is the same context as in the
           microtheory slot; renaming it part-wise would wire up a context nothing asserts into"))))

(deftest a-minted-context-name-survives-collision-and-keeps-its-numbers
  (testing "two computed microtheories that spell alike still get context names"
    (let [table (cyc/name-table {} '#{(cyc/MtSpace cyc/A) (cyc/Mt cyc/SpaceA)} #{})
          terms (map :term (vals table))]
      (is (= 2 (count (set terms))) "the collision is resolved")
      (is (every? nm/context? terms)
          "a suffix appended after Context would stop the name being a context")))

  (testing "a date-sliced microtheory is told apart by its numbers"
    (let [table (cyc/name-table {} '#{(cyc/MtSpace cyc/M (cyc/YearFn 1980))
                                      (cyc/MtSpace cyc/M (cyc/YearFn 1981))} #{})]
      (is (= '#{MtSpaceMYearFn1980Context MtSpaceMYearFn1981Context}
             (set (map :term (vals table))))
          "dropping the numbers would collapse thousands of Cyc's contexts onto one name"))))

;;; ── the corpus ────────────────────────────────────────────────────────

(deftest writes-a-corpus-partitioned-by-context-and-strength
  (converted
   (fn [dir report]
     (is (= 15 (:assertions report)))
     (is (pos? (:sentences report)))
     (is (= {:trigger-code 1 :nart-definition 1}
            (select-keys (:drop-reasons report) [:trigger-code :nart-definition])))
     (testing "and neither is counted against the reader"
       (is (zero? (:unread report)))
       (is (= {:filtered {:trigger-code 1} :restated {:nart-definition 1}}
              (:drops report))))

     (testing "one file per context, and the strengths kept apart"
       (is (contains? (sentences-in dir "MammalBiologyContext.txt") '(dog Rover))
           "a :default assertion")
       (is (contains? (sentences-in dir "MammalBiologyContext.monotonic.txt") '(genl dog mammal))
           "a :monotonic one, so the loader can restore Cyc's own strength"))

     (testing "the topology names every context and roots them under the vocabulary"
       (let [topology (sentences-in dir "Topology.txt")]
         (is (contains? topology '(genlContext BaseKBContext CoreContext)))
         (is (contains? topology '(genlContext MammalBiologyContext BaseKBContext)))))

     (testing "the meta records a load order that puts a supercontext first"
       (let [order (:context-order (read-string (slurp (io/file dir "meta.edn"))))]
         (is (< (.indexOf ^java.util.List order 'BaseKBContext)
                (.indexOf ^java.util.List order 'MammalBiologyContext)))))

     (testing "the name table is the record of every rename"
       (let [names (read-string (slurp (io/file dir "names.edn")))]
         (is (= '{:term dog :role :type} (names 'cyc/Dog))))))))

(deftest a-converted-corpus-loads-and-answers
  (converted
   (fn [dir _report]
     (tu/with-cleared-kb [kb tu/fresh]
       (core-context/load-into kb)
       (let [loaded (cyc/load-dir! kb (str dir) {:chain? true})]
         (is (pos? (:asserted loaded)))

         (testing "the taxonomy came across"
           (is (v/genl? kb 'dog 'mammal))
           (is (contains? (v/genls kb 'dog) 'animal) "transitively, through Cyc's genls chain")
           (is (v/isa? kb 'Rover 'animal) "a type membership plus the genl closure"))

         (testing "the context topology came across"
           (is (v/sees? kb 'MammalBiologyContext 'BaseKBContext)))

         (testing "a fact is believed where Cyc stated it"
           (is (seq (v/sentexes-matching kb '(ownerOf AlicePerson Rover) 'MammalBiologyContext))))

         (testing "disjointness constrains, and metadata is read as metadata"
           (is (v/disjoint? kb 'dog 'domestic_cat))
           (is (contains? (v/props kb :symmetric) 'siblingOf)))

         (testing "a converted rule fires"
           (is (seq (v/sentexes-matching kb '(mammal Rover) 'MammalBiologyContext))
               "the rule's antecedent matched the fact the same translation stored")))))))

;;; ── what the load order costs ─────────────────────────────────────────
;;
;; `argIsa` is open-world about an argument with no type at all and closed about one
;; that has any: an argument the KB knows *a* type for, but not the required one, is a
;; violation.  That makes a bulk load order-sensitive in what it keeps — a relational
;; fact checked before its argument's other memberships have arrived is refused on a
;; partial answer, and nothing ever revisits it.  The loader answers by loading every
;; membership before any other fact; these two tests are the before and the after.

(def ^:private late-membership-dump
  "The pathological shape, and it is the ordinary one: a relational fact whose argument
  constraint is satisfied by a membership stated **later in the same file** than the
  fact — which is where Cyc puts it, since a corpus is grouped by term and not by the
  order somebody else's checks want to read it in."
  (str/join
   "\n"
   [;; the types, placed in the hierarchy — an argument outside it is exempt outright,
    ;; and it is the argument with *some* type that the check convicts
    "(ke-assert '(#$genls #$Dog #$Thing) #$BaseKB :monotonic :forward)"
    "(ke-assert '(#$genls #$LegalAgent #$Thing) #$BaseKB :monotonic :forward)"
    "(ke-assert '(#$genls #$Musician #$Thing) #$BaseKB :monotonic :forward)"
    "(ke-assert '(#$arg1Isa #$ownerOf #$LegalAgent) #$BaseKB :monotonic :forward)"
    ;; Alice is known to be a musician first — a type, but not the one the slot wants
    "(ke-assert '(#$isa #$Alice-Person #$Musician) #$BaseKB :default :forward)"
    "(ke-assert '(#$ownerOf #$Alice-Person #$Rover) #$BaseKB :default :forward)"
    ;; …and only afterwards that she is a legal agent, which is what satisfies it
    "(ke-assert '(#$isa #$Alice-Person #$LegalAgent) #$BaseKB :default :forward)"]))

(deftest a-membership-stated-after-the-fact-it-licenses-still-counts
  (with-dump-file late-membership-dump
    (fn [dump ^File dir]
      (let [out (io/file dir "corpus")]
        (cyc/convert! dump (str out) {})
        (tu/with-cleared-kb [kb tu/fresh]
          (core-context/load-into kb)
          (let [loaded (cyc/load-dir! kb (str out) {:chain? false})]
            (is (zero? (:refused loaded))
                (str "nothing here contradicts anything: " (pr-str (:refusals loaded))))
            (is (seq (v/sentexes-matching kb '(ownerOf AlicePerson Rover) 'BaseKBContext))
                "the fact is kept, because every membership was loaded before it")))))))

(deftest the-membership-layer-is-what-keeps-it
  (testing "with memberships interleaved, the same corpus loses the fact — which is why
            the layer exists, and what a future reordering would silently undo"
    (with-dump-file late-membership-dump
      (fn [dump ^File dir]
        (let [out (io/file dir "corpus")]
          (cyc/convert! dump (str out) {})
          (tu/with-cleared-kb [kb tu/fresh]
            (core-context/load-into kb)
            ;; every membership demoted to an ordinary fact, so file order decides
            (with-redefs [corpus/type-membership? (constantly false)]
              (let [loaded (cyc/load-dir! kb (str out) {:chain? false})]
                (is (= {:arg-type 1} (:refusals loaded))
                    "the relational fact is refused on Alice's partial type set")
                (is (empty? (v/sentexes-matching kb '(ownerOf AlicePerson Rover) 'BaseKBContext))
                    "and no later membership brings it back — a refusal is not revisited")))))))))

(deftest a-reified-nat-gets-its-result-types-however-late-they-are-stated
  ;; A NART materializes its `resultIsa` types and `resultGenl` edges **at mint time**,
  ;; so a membership mentioning a NAT that is loaded before those declarations mints a
  ;; term with no types and no place in the hierarchy — and nothing revisits it.  The
  ;; corpus has half a million memberships whose argument is a NAT, so this is the rule
  ;; and not the corner.  `resultIsa` / `resultGenl` are therefore schema, like `argIsa`:
  ;; a declaration the engine reads while storing something else.
  (with-dump-file
    (str/join
     "\n"
     ["(ke-assert '(#$genls #$Fruit #$Thing) #$BaseKB :monotonic :forward)"
      "(ke-assert '(#$isa #$FruitFn #$ReifiableFunction) #$BaseKB :monotonic :forward)"
      ;; the membership comes first, and its argument is the NAT
      "(ke-assert '(#$isa (#$FruitFn #$AppleTree) #$Fruit) #$BaseKB :default :forward)"
      ;; the function's result declarations come last, as an ordinary fact would
      "(ke-assert '(#$resultGenl #$FruitFn #$Fruit) #$BaseKB :monotonic :forward)"])
    (fn [dump ^File dir]
      (let [out (io/file dir "corpus")]
        (cyc/convert! dump (str out) {})
        (tu/with-cleared-kb [kb tu/fresh]
          (core-context/load-into kb)
          (cyc/load-dir! kb (str out) {:chain? false})
          (let [narts (filter #(= "nat" (namespace %)) (v/types kb))]
            (is (seq narts)
                "the NAT is reified, and the NART reaches the genl hierarchy")
            (is (every? #(contains? (v/genls kb %) 'fruit) narts)
                "with the resultGenl edge its function declared")))))))

(def ^:private filtered-dump
  "One assertion per `:filtered` reason this reader has, so each flag can be shown to
  reverse exactly its own and nothing else's."
  (str/join
   "\n"
   ["(ke-assert '(#$genls #$Dog #$Mammal) #$BaseKB :monotonic :forward)"
    "(ke-assert '(#$sharedNotes #$Dog \"a note between curators\") #$BaseKB :default :forward)"
    "(ke-assert '(#$implies (#$isa ?X #$Dog) (#$isa ?X #$Mammal)) #$BaseKB :default :code)"
    "(ke-assert '(#$afterAdding #$ownerOf (#$SubLQuoteFn clear-owner-caches)) #$BaseKB :default :forward)"]))

(defn- filtered-drops
  "`{reason count}` for `filtered-dump` converted under `opts`."
  [opts]
  (with-dump-file filtered-dump
    (fn [dump ^File dir]
      (:drop-reasons (cyc/convert! dump (str (io/file dir "corpus")) opts)))))

(deftest a-filtered-drop-comes-back-when-its-flag-asks-for-it
  ;; A `:filtered` drop is this reader's policy, not a claim about what can be read, so
  ;; the person converting the KB gets to overrule it.  `plugin-test` checks that every
  ;; filtered reason names a flag; this checks the flags do what they say.
  (testing "by default all four are dropped, each under its own reason"
    (is (= {:editorial 1 :code-rule 1 :trigger-code 1} (filtered-drops {}))))

  (testing "--editorial keeps the curators' notes and nothing else"
    (is (= {:code-rule 1 :trigger-code 1} (filtered-drops {:editorial? true}))))

  (testing "--code-rules keeps a rule Cyc states in full and implements in SubL"
    (is (= {:editorial 1 :trigger-code 1} (filtered-drops {:code-rules? true}))))

  (testing "and a SubL trigger stays dropped under both, because there is no flag"
    ;; `drop-flags` carries prose rather than an option for this one, and that is the
    ;; claim: vaelii has no form for executable code, so there is nothing to import it as
    (is (string? (get cyc/drop-flags :trigger-code)))
    (is (= 1 (:trigger-code (filtered-drops {:editorial? true :code-rules? true}))))))

(deftest an-imported-code-rule-is-an-ordinary-rule
  (with-dump-file filtered-dump
    (fn [dump ^File dir]
      (let [out (io/file dir "corpus")]
        (cyc/convert! dump (str out) {:editorial? true :code-rules? true})
        (tu/with-cleared-kb [kb tu/fresh]
          (core-context/load-into kb)
          (cyc/load-dir! kb (str out) {:chain? true})
          (v/assert kb '(dog Rover) 'BaseKBContext)
          (is (seq (v/sentexes-matching kb '(mammal Rover) 'BaseKBContext))
              "the rule Cyc said it implements in code is one vaelii can run")
          (is (seq (v/sentexes-matching kb '(sharedNotes dog "a note between curators")
                                        'BaseKBContext))
              "and the editorial note is an ordinary fact"))))))

(def ^:private computed-collection-dump
  "Cyc computes collections as well as naming them.  `(CityInCountryFn Canada)` denotes
  the collection of Canadian cities, and the KB states both that Erickson belongs to it
  and where it sits in the taxonomy — 4,218 assertions in OpenCyc are of this shape and
  every one of them used to be dropped, because vaelii writes a membership as `(type
  Individual)` and a functor has to be a name."
  (str/join
   "\n"
   ["(ke-assert '(#$isa #$CityInCountryFn #$ReifiableFunction) #$BaseKB :monotonic :forward)"
    "(ke-assert '(#$genls #$City #$Thing) #$BaseKB :monotonic :forward)"
    ;; the membership, and the taxonomy edge for the same computed collection
    "(ke-assert '(#$isa #$CityOfEricksonCanada (#$CityInCountryFn #$Canada)) #$BaseKB :default :forward)"
    "(ke-assert '(#$genls (#$CityInCountryFn #$Canada) #$City) #$BaseKB :monotonic :forward)"
    ;; and one this reader cannot name: a nested function application, whose inner NAT a
    ;; `termOfUnit` would quote and every other position would reify
    "(ke-assert '(#$isa #$X (#$CollectionIntersection2Fn (#$GroupFn #$A) #$B)) #$BaseKB :default :forward)"]))

(deftest a-computed-collection-is-minted-a-type-name
  (with-dump-file computed-collection-dump
    (fn [dump ^File dir]
      (let [out    (io/file dir "corpus")
            report (cyc/convert! dump (str out) {})
            named  (read-string (slurp (io/file out "names.edn")))]

        (testing "the NART is named as a type, keyed by its whole form"
          (is (= '{:term city_in_country_fn_canada :role :type}
                 (named '(cyc/CityInCountryFn cyc/Canada)))))

        (testing "and its definition is written, rather than left to the engine to mint"
          ;; an engine-minted `nat/g19374` is unreadable and different on every run,
          ;; which is exactly what a diff between two converted corpora cannot see through
          (is (contains? (sentences-in out "BaseKBContext.monotonic.txt")
                         '(termOfUnit city_in_country_fn_canada (CityInCountryFn Canada)))))

        (testing "what is still unnameable says so under its own reason"
          (is (= 1 (get-in report [:drops :unread :unnameable-type])))
          (is (= 1 (:unread report))))

        (testing "the corpus loads, and the membership and the taxonomy edge are one term"
          (tu/with-cleared-kb [kb tu/fresh]
            (core-context/load-into kb)
            (cyc/load-dir! kb (str out) {:chain? true})
            (is (v/isa? kb 'CityOfEricksonCanada 'city_in_country_fn_canada))
            (is (v/genl? kb 'city_in_country_fn_canada 'city))
            (is (v/isa? kb 'CityOfEricksonCanada 'city)
                "the membership joins the taxonomy, which is the whole point of naming it")
            (is (empty? (filter #(= "nat" (namespace %)) (v/types kb)))
                "and no second, opaque constant was minted for the same expression")))))))

(deftest a-profile-selects-a-subset
  (converted
   (fn [dir _report]
     (tu/with-cleared-kb [kb tu/fresh]
       (core-context/load-into kb)
       (cyc/load-dir! kb (str dir) {:keep-contexts #{'BaseKBContext} :chain? false})
       (is (empty? (v/sentexes-matching kb '(ownerOf AlicePerson Rover) 'MammalBiologyContext))
           "a context left out of the profile contributes nothing")
       (is (seq (v/sentexes-matching kb '(comment dog ?c) 'BaseKBContext))
           "and the one kept still loads")))))
