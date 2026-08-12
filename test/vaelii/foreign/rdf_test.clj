(ns vaelii.foreign.rdf-test
  "Translate an RDF/OWL graph into a vaelii corpus, and load it.

  Two contracts.  The **projection** is a function of the graph alone: the Horn fragment
  of OWL comes across as rules and declarations, and everything else is dropped under a
  counted reason — a translation that quietly weakened `C ⊑ ∃P.D` into something vaelii
  can store would be worse than one that dropped it, because nothing downstream could
  tell.  The **corpus loads**: what a conversion nothing can read is worth is nothing.

  The fixtures are hand-authored OWL, never a slice of a real ontology: a reader is a
  capability, and its checked-in test data is invented."
  (:require [clojure.test :refer [deftest is testing]]
            [vaelii.core :as v]
            [vaelii.foreign.rdf :as rdf]
            [vaelii.foreign.suite :as suite]
            [vaelii.foreign.test-util :as tu]
            [vaelii.impl.core-context :as core-context])
  (:import (java.io File)))

(def ^:private ttl-fixture "test/resources/rdf/tiny.ttl")
(def ^:private nq-fixture  "test/resources/rdf/tiny.nq")

(defn- converted
  "Convert `source` and call `(f corpus-dir report)`."
  ([f] (converted ttl-fixture {} f))
  ([source opts f]
   (tu/temp-dir "vaelii-rdf"
                (fn [^File dir]
                  (let [out (File. dir "corpus")]
                    (f out (rdf/convert! source (str out) opts)))))))

;;; ── the projection ────────────────────────────────────────────────────

(deftest the-context-a-graph-lands-in-can-be-named
  ;; A graph that declares no `owl:Ontology` is named after its file, which is a guess
  ;; about somebody else's data.  `--context` is how the person converting it overrules
  ;; that, and it is the only option here that names something rather than filtering it.
  (converted ttl-fixture {:context 'CxZoo}
             (fn [dir _]
               (is (seq (tu/corpus-file dir "CxZoo.txt"))
                   "the graph's own sentences land in the context that was named")
               ;; The corpus *root* is a level above that and stays derived: it is where
               ;; the whole corpus hangs off vaelii's vocabulary, and one graph's fallback
               ;; context is content sitting under it rather than the hook itself.
               (is (= 'CxRdfZoo (:root-context (tu/corpus-meta dir)))))))

(deftest the-rdfs-backbone-comes-across
  (converted
   (fn [dir _]
     (let [ss (set (tu/corpus-sentences dir))]
       (testing "subclass is subsumption, and a role decides a term's spelling"
         (is (contains? ss '(genl dog mammal)))
         (is (contains? ss '(genl mammal animal))))
       (testing "rdf:type is vaelii's unary membership, not a binary isa"
         (is (contains? ss '(dog Rover)))
         (is (not-any? #(= 'type (first %)) ss)
             "no `(type Rover Dog)` — a type is the predicate"))
       (testing "domain and range are positional argument constraints"
         (is (contains? ss '(argIsa hasPart 1 animal)))
         (is (contains? ss '(argIsa hasPart 2 limb))))
       (testing "subproperty is the same genl closure as subclass"
         (is (contains? ss '(genl hasParent hasAncestor))))
       (testing "disjointness and the property metatypes"
         (is (contains? ss '(disjoint dog cat)))
         (is (contains? ss '(transitive hasPart)))
         (is (contains? ss '(inverse partOf hasPart))))))))

(deftest a-property-chain-becomes-a-rule
  (converted
   (fn [dir _]
     (is (contains? (set (tu/corpus-sentences dir))
                    '(implies (and (hasParent ?x0 ?x1) (hasParent ?x1 ?x2))
                              (hasGrandparent ?x0 ?x2)))))))

(deftest the-horn-fragment-of-owl-restrictions
  (converted
   (fn [dir report]
     (let [ss (set (tu/corpus-sentences dir))]
       (testing "C ⊑ ∀P.D is a rule: a universal in the conclusion is exactly one"
         (is (contains? ss '(implies (and (dog ?x) (hasPart ?x ?y)) (limb ?y)))))
       (testing "C ≡ ∃P.D keeps its sufficient-condition half — the direction that is Horn"
         (is (contains? ss '(implies (and (hasPart ?x ?y) (limb ?y)) (bodied ?x)))))
       (testing "C ≡ D1 ⊓ D2 gives both edges and the rule"
         (is (contains? ss '(genl puppy dog)))
         (is (contains? ss '(genl puppy young)))
         (is (contains? ss '(implies (and (dog ?x) (young ?x)) (puppy ?x)))))
       (testing "C ⊑ ∃P.D has no Horn reading and says so by name"
         (is (= 1 (get-in report [:drop-reasons :existential-superclass]))
             "the existential superclass is dropped, and counted as itself")
         (is (not-any? #(= 'hand (second %)) (filter #(= 'genl (first %)) ss))
             "and nothing weaker was invented in its place"))
       (testing "a cardinality restriction is refused as the restriction it is"
         (is (= 1 (get-in report [:drop-reasons :unsupported-restriction]))))))))

(deftest an-owl-axiom-is-monotonic-and-a-triple-is-not
  ;; RDF draws no line between a definition and an observation, so this one is ours —
  ;; and it decides whether a later contradiction can retract a triple or is refused
  ;; against it.
  (converted
   (fn [dir _]
     (let [axioms (set (tu/corpus-file dir "CxZoo.monotonic.txt"))
           facts  (set (tu/corpus-file dir "CxZoo.txt"))]
       (is (contains? axioms '(genl dog mammal)))
       (is (contains? facts '(dog Rover)))
       (is (not (contains? axioms '(dog Rover))))))))

(deftest annotations-are-filtered-by-language
  (converted
   (fn [dir report]
     (let [ss (set (tu/corpus-sentences dir))]
       (is (contains? ss '(label dog "dog")) "the English label is kept")
       (is (not (contains? ss '(label dog "chien"))))
       (is (= 1 (get-in report [:drop-reasons :other-language])))
       (is (contains? ss '(comment dog "a domesticated canine"))))))
  (testing "and the filter is a knob, not a policy"
    (converted ttl-fixture {:languages #{"en" "fr"}}
               (fn [dir _]
                 (is (contains? (set (tu/corpus-sentences dir)) '(label dog "chien")))))))

(deftest the-consumed-owl-vocabulary-is-never-named
  ;; Every reading of an OWL term is a vaelii sentence chosen by hand, so minting
  ;; `subClassOf` as a predicate would put a name in `names.edn` that no sentence uses.
  (converted
   (fn [dir _]
     (let [names (vals (read-string (slurp (java.io.File. ^File dir "names.edn"))))
           terms (set (map :term names))]
       (is (not (contains? terms 'subClassOf)))
       (is (not (contains? terms 'Class)))
       (is (contains? terms 'hasPart) "an ordinary property still gets one")))))

;;; ── blank nodes ───────────────────────────────────────────────────────

(def ^:private nary-fixture "test/resources/rdf/nary.ttl")

(defn- n-ary
  ([f] (n-ary {} f))
  ([opts f] (converted nary-fixture opts f)))

(deftest a-uniform-blank-node-becomes-one-fact-of-higher-arity
  ;; RDF triples are binary, so an n-ary relation has to be written as a node with an
  ;; edge per argument.  vaelii predicates have no arity limit, so it need not stay one.
  (n-ary
   (fn [dir report]
     (let [ss (set (tu/corpus-sentences dir))]
       (is (contains? ss '(hasJob Bob Acme Engineer 2020)))
       (is (contains? ss '(hasJob Ann Beta Manager 2019)))
       (testing "a two-slot structured value is a tuple too, at arity 3"
         (is (contains? ss '(address Bob "Austin" "78701"))))
       (testing "the arity is declared, so a later fact at the wrong one is a refusal
                 rather than a sentence that quietly matches nothing"
         (is (contains? ss '(arity hasJob 4)))
         (is (contains? ss '(arity address 3))))
       (testing "and the argument order — which is arbitrary — is written down"
         (is (= '[employer role since] (get-in report [:n-ary 'hasJob]))))))))

(deftest a-non-uniform-blank-node-is-named-rather-than-misaligned
  ;; The hazard flattening has to avoid: Ann's award has no year, so positionally her
  ;; name would land in the slot a rule reads as a year.
  (n-ary
   (fn [dir report]
     (let [ss (set (tu/corpus-sentences dir))]
       (is (contains? ss '(award Bob BobAward)))
       (is (contains? ss '(awardName BobAward "Prize")))
       (is (contains? ss '(year BobAward 2001)))
       (is (contains? ss '(award Ann AnnAward)))
       (is (contains? ss '(awardName AnnAward "Medal")))
       (is (nil? (get-in report [:n-ary 'award])) "and `award` is not flattened at all")
       (is (not (contains? ss '(arity award 3)))))))
  (testing "a name says where it came from, so a corpus can be read back to the source"
    (n-ary (fn [dir _]
             (is (contains? (set (map :term (vals (read-string
                                                   (slurp (java.io.File. ^File dir "names.edn"))))))
                            'BobAward))))))

(deftest a-typed-or-shared-node-keeps-an-identity-of-its-own
  (n-ary
   (fn [dir report]
     (let [ss (set (tu/corpus-sentences dir))]
       (testing "typed: flattening would take away the term the membership hangs on"
         (is (contains? ss '(membership BobMembership)) "the rdf:type membership survives")
         (is (contains? ss '(org BobMembership Guild)))
         (is (nil? (get-in report [:n-ary 'membership]))))
       (testing "shared: flattening would copy the node rather than move it, and a node
                 reached from several places cannot be named after any one of them — so
                 it falls back to its own label"
         (is (contains? ss '(knows Bob NodeChris)))
         (is (contains? ss '(knows Ann NodeChris)))
         (is (contains? ss '(nickname NodeChris "Chris"))))))))

(deftest a-predicate-with-a-plain-object-anywhere-cannot-flatten
  ;; Half its facts would come out at the tuple's arity and half at 2, and no one rule
  ;; would match both — so it backs off entirely rather than flattening what it can.
  (n-ary
   (fn [dir report]
     (let [ss (set (tu/corpus-sentences dir))]
       (is (nil? (get-in report [:n-ary 'homepage])))
       (is (contains? ss '(homepage Ann "http://ann.example")))
       (is (contains? ss '(url BobHomepage "http://bob.example")))
       (is (contains? ss '(homepage Bob BobHomepage)))))))

(deftest an-owl-construct-is-still-an-axiom-and-not-a-tuple
  ;; The one blank node that must be left alone: the axiom that owns it has already said
  ;; what it means.
  (n-ary
   (fn [dir report]
     (let [ss (set (tu/corpus-sentences dir))]
       (is (contains? ss '(implies (and (dog ?x) (hasPart ?x ?y)) (limb ?y))))
       (is (empty? (filter #(and (= 'arity (first %)) (= 'hasPart (second %))) ss)))
       (is (pos? (get-in report [:drop-reasons :structural]))
           "and its interior is counted as consumed, not as lost")))))

(deftest turning-flattening-off-skolemizes-everything
  ;; The reading to take if a rule set was written against the joined form.
  (n-ary {:n-ary? false}
         (fn [dir report]
           (let [ss (set (tu/corpus-sentences dir))]
             (is (empty? (:n-ary report)))
             (is (contains? ss '(hasJob Bob BobHasJob)))
             (is (contains? ss '(employer BobHasJob Acme)))
             (is (not-any? #(= 'arity (first %)) ss))))))

(deftest a-flattened-tuple-loads-and-queries-at-its-arity
  ;; The claim the whole thing rests on: vaelii has no arity ceiling, so a 4-ary fact is
  ;; an ordinary fact — it stores, it matches a pattern, and the `(arity …)` beside it
  ;; does not refuse it.
  (n-ary
   (fn [dir _]
     (tu/with-cleared-kb [kb tu/fresh]
       (core-context/load-into kb)
       (let [loaded (rdf/load-dir! kb (str dir) {:chain? false})]
         (is (zero? (:refused loaded))
             (str "the arity declaration agrees with the tuples it was written for: "
                  (pr-str (:refusals loaded)))))
       (is (v/ask? kb '(hasJob Bob Acme Engineer 2020) 'CxNaryTtlGraph))
       (testing "and the tuple answers a pattern over any of its positions"
         (is (seq (v/sentexes-matching kb '(hasJob ?who ?emp Engineer ?yr)
                                       'CxNaryTtlGraph))
             "which is the point of flattening: no join through a node to ask this"))
       (testing "a skolemized node is queryable the other way, by joining"
         (is (seq (v/sentexes-matching kb '(award Bob ?a) 'CxNaryTtlGraph)))
         (is (v/ask? kb '(awardName BobAward "Prize") 'CxNaryTtlGraph)))))))

(deftest a-wrong-arity-fact-is-refused-once-the-arity-is-declared
  ;; What the declaration buys.  Without it vaelii is open-world about arity and a
  ;; misaligned tuple would store happily and match nothing — which is the failure mode
  ;; that is hard to find, so it is worth turning into a loud one.
  (n-ary
   (fn [dir _]
     (tu/with-cleared-kb [kb tu/fresh]
       (core-context/load-into kb)
       (rdf/load-dir! kb (str dir) {:chain? false})
       (let [e (is (thrown? clojure.lang.ExceptionInfo
                            (v/assert kb '(hasJob Cid Acme) 'CxNaryTtlGraph)))]
         (is (= :arity (:type (ex-data e)))))))))

(deftest a-blank-node-is-not-a-drop
  ;; A blank node is how RDF writes an n-ary relation, so losing one loses a fact rather
  ;; than a syntax detail.  `bnode-plan` is what makes that unnecessary: every blank node
  ;; is either folded into the tuple it qualifies or given a name, and the two reasons
  ;; asserted absent here are the ones that would mean neither happened.
  (n-ary
   (fn [_ report]
     (is (nil? (get-in report [:drop-reasons :blank-node-object])))
     (is (nil? (get-in report [:drop-reasons :blank-node-subject])))
     (is (pos? (:skolemized report)))
     (testing "what is left is counted as consumed by something, under its own name"
       (is (pos? (get-in report [:drop-reasons :n-ary-member])))))))

;;; ── contexts ──────────────────────────────────────────────────────────

(deftest an-ontology-is-a-context-and-an-import-is-an-edge
  (converted
   (fn [dir _]
     (is (contains? (set (tu/corpus-file dir "Topology.txt"))
                    '(genlCx CxZoo CxUpper))
         "owl:imports is the context hierarchy it always was")
     (is (= 'CxZoo (first (filter #(= 'CxZoo %) (:context-order (tu/corpus-meta dir)))))
         "and the ontology the file declares is where its statements land"))))

(deftest a-named-graph-is-a-context
  ;; That is what N-Quads' fourth term is for, and it is the only place in RDF where
  ;; somebody has already said which statements belong together.
  (converted nq-fixture {}
             (fn [dir _]
               (is (= #{'(dog Rex) '(age Rex 3)} (set (tu/corpus-file dir "CxShelter.txt"))))
               (is (= #{'(genl dog mammal)}
                      (set (tu/corpus-file dir "CxTaxonomy.monotonic.txt")))))))

;;; ── the corpus loads ──────────────────────────────────────────────────

(deftest the-corpus-loads-and-what-it-says-is-derivable
  (converted
   (fn [dir _]
     (tu/with-cleared-kb [kb tu/fresh]
       (core-context/load-into kb)
       (let [loaded (rdf/load-dir! kb (str dir) {:chain? false})]
         (is (pos? (:asserted loaded)))
         (is (zero? (:refused loaded))
             (str "nothing here contradicts anything: " (pr-str (:refusals loaded))))
         (v/forward-chain kb {})
         (testing "the asserted membership is there"
           (is (v/ask? kb '(dog Rover) 'CxZoo)))
         (testing "and the taxonomy carries it upward"
           (is (v/ask? kb '(mammal Rover) 'CxZoo)
               "Rover is a Mammal through (genl dog mammal)")
           (is (v/ask? kb '(animal Rover) 'CxZoo)
               "and an Animal through the closure"))
         (testing "the universal restriction fired"
           (is (v/ask? kb '(limb RoverLeg) 'CxZoo)))
         (testing "the sufficient condition fired"
           (is (v/ask? kb '(bodied Rover) 'CxZoo)
               "Rover has a Limb part, so Rover is Bodied")))))))

(deftest a-profile-selects-a-subset
  (converted
   (fn [dir _]
     (tu/with-cleared-kb [kb tu/fresh]
       (core-context/load-into kb)
       (rdf/load-dir! kb (str dir) {:profile :schema :chain? false})
       (is (v/ask? kb '(genl dog mammal) 'CxZoo) "the axioms loaded")
       (is (not (v/ask? kb '(dog Rover) 'CxZoo))
           "and the instance data did not — which is what :schema is for")))))

;;; ── RDF/XML is the same graph in another syntax ───────────────────────

(def ^:private owl-fixture "test/resources/rdf/tiny.owl")

(deftest the-syntax-is-decided-by-looking-not-by-the-extension
  (testing "each fixture is recognised for what it is"
    (is (= :rdf-xml (rdf/syntax owl-fixture)))
    (is (= :turtle (rdf/syntax ttl-fixture)))
    (is (= :turtle (rdf/syntax nq-fixture))
        "N-Triples also begins with `<`, and is not a start tag"))
  (testing "why an extension table would not do"
    ;; `.owl` names both syntaxes in the wild — the OBO Foundry publishes Turtle as
    ;; .owl, OpenCyc and DOLCE publish RDF/XML as .owl — so the only reliable
    ;; discriminator is the first few hundred bytes.
    (is (= :rdf-xml (rdf/syntax owl-fixture))
        "this one is .owl and is XML")))

(deftest one-graph-two-syntaxes-one-corpus
  (testing "tiny.ttl and tiny.owl state the same graph"
    ;; This is the strongest test of the RDF/XML lexer there is, and it costs one
    ;; fixture: the two files were written to say the same thing, so any difference
    ;; between the triples they yield is a lexer bug by definition — no expected-output
    ;; file to get wrong, and no judgement call about what the right answer is.
    (let [graph #(rdf/with-triples % (fn [ts] (into #{} (map (fn [t] (dissoc t :g))) ts)) {})]
      (is (suite/isomorphic? (graph ttl-fixture) (graph owl-fixture))
          "up to a renaming of blank nodes, which is all RDF ever promises")))

  (testing "and therefore convert to the same sentences"
    (let [sentences (fn [src]
                      (converted src {}
                                 (fn [dir _] (set (tu/corpus-sentences dir)))))]
      (is (= (sentences ttl-fixture) (sentences owl-fixture))
          "the projection sits above the lexer and cannot tell them apart"))))

(deftest an-rdf-xml-ontology-loads-and-reasons
  (converted
   owl-fixture {}
   (fn [dir _]
     (tu/with-cleared-kb [kb tu/fresh]
       (core-context/load-into kb)
       (rdf/load-dir! kb (str dir) {:chain? false})
       (v/forward-chain kb {})
       (testing "everything the Turtle fixture proves, the RDF/XML one proves too"
         (is (v/ask? kb '(animal Rover) 'CxZoo) "the taxonomy closure")
         (is (v/ask? kb '(limb RoverLeg) 'CxZoo) "the universal restriction")
         (is (v/ask? kb '(bodied Rover) 'CxZoo) "the sufficient condition"))))))
