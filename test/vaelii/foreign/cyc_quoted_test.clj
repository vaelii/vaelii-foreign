;; SPDX-License-Identifier: Apache-2.0
;; Copyright © 2026 Vaelii LLC and the Vaelii contributors.
(ns vaelii.foreign.cyc-quoted-test
  "`quotedIsa X C` — Cyc's *mention* typing — becomes vaelii's `(c (Quote X))`: the term
  `X`, named as syntax, is typed a `C`.  `Quote` reifies `(Quote X)` to a mention constant
  the engine holds opaque to an identity merge of `X`'s referent (docs/nat.md, the plan)."
  (:require [clojure.test :refer [deftest is testing]]
            [vaelii.foreign.cyc :as cyc]
            [vaelii.foreign.cycl :as cycl])
  (:import [java.io StringReader]))

(defn- read-formulas [text] (doall (cycl/assertions (StringReader. text))))

(defn- translate-dump
  "Every assertion of `text`, translated, keyed by its Cyc predicate."
  [text]
  (let [assertions (read-formulas text)
        names      (cyc/name-table (cyc/roles (cyc/classify assertions)) #{} #{})]
    (into {} (map (fn [a] [(first (:formula a)) (cyc/translate a names {})])) assertions)))

(deftest quotedisa-becomes-a-quote-mention-typed-as-syntax
  (let [t (translate-dump
           (str "(ke-assert '(#$quotedIsa #$Dog #$CycLConstant) #$BaseKB :monotonic :forward)\n"
                "(ke-assert '(#$genls #$Dog #$Mammal) #$BaseKB :monotonic :forward)\n"))]
    (testing "quotedIsa X C -> (c (Quote X)), the CycL collection mapped to cycl_*"
      (is (= '#{(cycl_constant (Quote dog))}
             (set (:sentences (t 'cyc/quotedIsa))))))
    (testing "the mentioned term keeps its used spelling (dog, a type via genls)"
      (is (= '#{(genl dog mammal)} (set (:sentences (t 'cyc/genls))))
          "so (Quote dog) mentions the same term (dog Rover) uses"))))

(deftest a-quoted-only-term-is-not-classified-a-used-individual
  ;; the classification guard: a term appearing only inside a quotedIsa mention is not
  ;; walked into `:seen` and made an individual by residue, which would spell it apart from
  ;; any used occurrence.
  (let [roles (cyc/roles (cyc/classify
                          (read-formulas
                           "(ke-assert '(#$quotedIsa #$Foo #$CycLConstant) #$BaseKB :monotonic :forward)")))]
    (is (not= :individual (roles 'cyc/Foo)))))

(deftest argquotedisa-becomes-quotedarg-with-a-syntactic-type
  ;; `argQuotedIsa` types an argument AS A TERM — vaelii's `quotedArg`, with the Cyc
  ;; quoted-type collection mapped to a syntactic type (CharacterString -> string).
  (let [t (translate-dump
           (str "(ke-assert '(#$argQuotedIsa #$nameString 2 #$CharacterString) #$BaseKB :monotonic :forward)\n"
                "(ke-assert '(#$arg1QuotedIsa #$labelPred #$SubLSymbol) #$BaseKB :monotonic :forward)\n"))]
    (testing "argQuotedIsa X n C -> (quotedArg X n <syntactic type>)"
      (is (= '#{(quotedArg nameString 2 string)}
             (set (:sentences (t 'cyc/argQuotedIsa))))))
    (testing "the argNQuotedIsa family folds into one positional quotedArg"
      (is (= '#{(quotedArg labelPred 1 symbol)}
             (set (:sentences (t 'cyc/arg1QuotedIsa))))))))

(deftest the-preamble-declares-quote-and-the-cycl-hierarchy
  ;; the fixed vocabulary a quotedIsa corpus rides on: Quote reifiable + quoting, and the
  ;; cycl_* hierarchy the mapped collections live in.
  (let [vocab (set cyc/quote-vocabulary)]
    (is (contains? vocab '(reifiableFunction Quote)))
    (is (contains? vocab '(quotingFunction Quote)))
    (is (contains? vocab '(result Quote cycl_expression)))
    (is (contains? vocab '(genl cycl_constant cycl_reifiable_denotational_term)))
    (is (= 'cycl_constant (cyc/cycl-collection-names 'cyc/CycLConstant)))))
