(ns vaelii.foreign.obo-test
  "Translate an OBO ontology into a vaelii corpus, and load it.

  The OBO stanza format is simple enough that the interesting claims are not about
  parsing but about **reading**: which tag becomes which vaelii sentence, what a
  `relationship:` is taken to mean, and that an obsolete term does not quietly land in
  the hierarchy under the name its replacement wants.

  The fixture is hand-authored, never a slice of the Gene Ontology or any other real
  ontology: a reader is a capability, and its checked-in test data is invented."
  (:require [clojure.test :refer [deftest is testing]]
            [vaelii.core :as v]
            [vaelii.foreign.obo :as obo]
            [vaelii.foreign.test-util :as tu]
            [vaelii.impl.core-context :as core-context])
  (:import (java.io File)))

(def ^:private fixture "test/resources/obo/tiny.obo")

(defn- converted
  ([f] (converted {} f))
  ([opts f]
   (tu/temp-dir "vaelii-obo"
                (fn [^File dir]
                  (let [out (File. dir "corpus")]
                    (f out (obo/convert! fixture (str out) opts)))))))

;;; ── the lexer ─────────────────────────────────────────────────────────

(deftest a-trailing-comment-is-not-part-of-a-value
  ;; `is_a: TT:0000001 ! cellular process` — the `!` and everything after it is a
  ;; courtesy to the human reader, and taking it as content would make every parent id
  ;; unresolvable.
  (converted
   (fn [dir _]
     (is (contains? (set (tu/corpus-sentences dir)) '(genl mitotic_cell_cycle cellular_process))))))

(deftest a-multi-word-value-keeps-its-words
  (converted
   (fn [dir _]
     (let [ss (set (tu/corpus-sentences dir))]
       (is (contains? ss '(label mitotic_cell_cycle "mitotic cell cycle"))
           "a name read up to the first space would be `mitotic`")
       (is (contains? ss '(comment cellular_process "A process at the level of a cell.")))
       (is (contains? ss '(synonym mitotic_cell_cycle "mitosis"))
           "a synonym's text is the quoted head, not the scope qualifier after it")))))

;;; ── the reading ───────────────────────────────────────────────────────

(deftest a-term-is-a-type-and-a-typedef-is-a-predicate
  (converted
   (fn [dir _]
     (let [names (vals (read-string (slurp (File. ^File dir "names.edn"))))
           roles (into {} (map (juxt :term :role)) names)]
       (is (= :type (roles 'mitotic_cell_cycle)) "spelled from `name:`, snake_case")
       (is (= :predicate (roles 'partOf)) "spelled from `id:`, camelCase")
       (is (= :individual (roles 'TheObservedDivision)))))))

(deftest the-hierarchy-and-the-relation-declarations
  (converted
   (fn [dir _]
     (let [ss (set (tu/corpus-sentences dir))]
       (is (contains? ss '(genl cell_division cellular_process)))
       (is (contains? ss '(disjoint cell_division membrane)))
       (is (contains? ss '(transitive partOf)))
       (is (contains? ss '(inverse partOf hasPart)))
       (is (contains? ss '(arg partOf 1 cellular_process)))
       (is (contains? ss '(arg partOf 2 cellular_process)))))))

(deftest a-relationship-is-read-at-the-class-level
  ;; Deliberately weaker than the OWL mapping, which makes it an existential restriction
  ;; on every instance.  Recorded here so nobody has to rediscover it from the output.
  (converted
   (fn [dir _]
     (is (contains? (set (tu/corpus-sentences dir))
                    '(partOf mitotic_cell_cycle cell_division))))))

(deftest an-intersection-is-a-definition-and-both-halves-are-horn
  (converted
   (fn [dir _]
     (let [ss (set (tu/corpus-sentences dir))]
       (is (contains? ss '(genl young_mitotic_cycle mitotic_cell_cycle)))
       (is (contains? ss '(genl young_mitotic_cycle young_thing)))
       (is (contains? ss '(implies (and (mitotic_cell_cycle ?x) (young_thing ?x))
                                   (young_mitotic_cycle ?x))))))))

(deftest a-chain-tag-becomes-a-rule
  (converted
   (fn [dir _]
     (is (contains? (set (tu/corpus-sentences dir))
                    '(implies (and (partOf ?x ?y) (occursIn ?y ?z)) (occursIn ?x ?z)))))))

(deftest an-instance-stanza-states-a-membership
  (converted
   (fn [dir _]
     (let [ss (set (tu/corpus-sentences dir))]
       (is (contains? ss '(cell_division TheObservedDivision)))
       (is (contains? ss '(partOf TheObservedDivision mitotic_cell_cycle))
           "a property_value whose value is a declared id resolves to the term")))))

(deftest an-obsolete-term-is-dropped-and-counted
  ;; OBO never deletes: a retired term keeps its id, gains `is_obsolete: true`, and loses
  ;; its edges — so importing one gives a term with no place in the hierarchy.
  (converted
   (fn [dir report]
     (is (= 1 (get-in report [:drop-reasons :obsolete])))
     (is (not-any? #(= "obsolete thing" (last %))
                   (filter #(= 'label (first %)) (tu/corpus-sentences dir))))))
  (testing "and keeping it is a knob"
    (converted {:obsolete? true}
               (fn [dir _]
                 (is (some #(= "obsolete thing" (last %))
                           (filter #(= 'label (first %)) (tu/corpus-sentences dir))))))))

;;; ── contexts ──────────────────────────────────────────────────────────

(deftest a-namespace-is-a-context
  (converted
   (fn [dir _]
     (is (contains? (set (tu/corpus-file dir "CxCellularComponent.txt"))
                    '(label membrane "membrane")))
     (is (contains? (set (tu/corpus-file dir "CxBiologicalProcess.monotonic.txt"))
                    '(genl cell_division cellular_process)))
     (testing "and a stanza with no namespace lands in the ontology's own context"
       (is (contains? (set (tu/corpus-file dir "CxTiny.monotonic.txt"))
                      '(transitive partOf)))))))

;;; ── the corpus loads ──────────────────────────────────────────────────

(deftest the-corpus-loads-and-the-taxonomy-carries
  (converted
   (fn [dir _]
     (tu/with-cleared-kb [kb tu/fresh]
       (core-context/load-into kb)
       (let [loaded (obo/load-dir! kb (str dir) {:chain? false})]
         (is (pos? (:asserted loaded)))
         (is (zero? (:refused loaded))
             (str "nothing here contradicts anything: " (pr-str (:refusals loaded)))))
       (v/forward-chain kb {})
       (is (v/ask? kb '(genl mitotic_cell_cycle cellular_process) 'CxBiologicalProcess))
       (is (v/ask? kb '(cell_division TheObservedDivision) 'CxTiny))
       (is (v/ask? kb '(cellular_process TheObservedDivision) 'CxTiny)
           "and the membership rises through the hierarchy")))))

(deftest the-taxonomy-profile-drops-the-lexical-layer
  (converted
   (fn [dir _]
     (tu/with-cleared-kb [kb tu/fresh]
       (core-context/load-into kb)
       (obo/load-dir! kb (str dir) {:profile :taxonomy :chain? false})
       (is (v/ask? kb '(genl cell_division cellular_process) 'CxBiologicalProcess))
       (is (empty? (v/sentexes-matching kb '(label cell_division "cell division")
                                        'CxBiologicalProcess))
           "the labels are what :taxonomy is for dropping")))))
