(ns vaelii.foreign.wordnet-test
  "Translate a WordNet database into a vaelii corpus, and load it.

  WordNet is a lexical database that happens to contain a taxonomy, so the claims worth
  testing are about which half is which: `@` is subsumption and comes across as `genl`,
  `@i` is membership and comes across as a unary application, `~` is the stored inverse
  of `@` and comes across as nothing at all.

  The fixture is hand-authored WNDB, never a slice of Princeton WordNet: a reader is a
  capability, and its checked-in test data is invented."
  (:require [clojure.test :refer [deftest is testing]]
            [vaelii.core :as v]
            [vaelii.foreign.test-util :as tu]
            [vaelii.foreign.wordnet :as wn]
            [vaelii.impl.core-context :as core-context])
  (:import (java.io File)))

(def ^:private fixture "test/resources/wordnet/dict")

(defn- converted
  ([f] (converted {} f))
  ([opts f]
   (tu/temp-dir "vaelii-wn"
                (fn [^File dir]
                  (let [out (File. dir "corpus")]
                    (f out (wn/convert! fixture (str out) opts)))))))

;;; ── the format ────────────────────────────────────────────────────────

(deftest a-record-parses-and-a-header-line-does-not
  (is (nil? (wn/parse-line "  1 This software and database is being provided to you")))
  (let [r (wn/parse-line "00000003 05 n 02 dog 0 domestic_dog 0 001 @ 00000002 n 0000 | a canine  ")]
    (is (= "00000003" (:offset r)))
    (is (= ["dog" "domestic dog"] (:words r)) "w_cnt is hex, and `_` is a space")
    (is (= [["@" ["noun" "00000002"]]] (:pointers r))
        "a pointer's target is keyed by file, so it resolves across parts of speech")
    (is (= "a canine" (:gloss r))))
  (testing "a malformed record states nothing and does not cost the file"
    (is (nil? (wn/parse-line "00000003 05 n zz")))))

;;; ── the reading ───────────────────────────────────────────────────────

(deftest a-hypernym-is-subsumption
  (converted
   (fn [dir _]
     (let [ss (set (tu/corpus-sentences dir))]
       (is (contains? ss '(genl dog_n animal_n)))
       (is (contains? ss '(genl animal_n entity_n)))
       (is (contains? ss '(genl poodle_n dog_n)))))))

(deftest an-instance-hypernym-is-a-membership-not-an-edge
  ;; WordNet marks Bach as an instance of composer rather than a kind of one, and that
  ;; distinction is exactly vaelii's between `(genl a b)` and `(b A)`.
  (converted
   (fn [dir _]
     (let [ss (set (tu/corpus-sentences dir))]
       (is (contains? ss '(composer_n Bach)))
       (is (not-any? #(= 'Bach (second %)) (filter #(= 'genl (first %)) ss)))))))

(deftest an-instance-is-named-without-a-part-of-speech
  (converted
   (fn [dir _]
     (let [terms (set (map :term (vals (read-string (slurp (File. ^File dir "names.edn"))))))]
       (is (contains? terms 'Bach) "`BachN` would be a worse name and nothing collides with it")
       (is (contains? terms 'dog_n) "a type still carries one, because `dog_v` exists")))))

(deftest a-stored-inverse-is-not-imported-twice
  (converted
   (fn [dir report]
     (is (pos? (get-in report [:drop-reasons :inverse-pointer]))
         "`~` and the holonyms are the exact inverses of pointers already taken")
     (is (not-any? #(contains? '#{partHolonym memberHolonym substanceHolonym} (first %))
                   (tu/corpus-sentences dir))))))

(deftest a-word-sense-collision-is-resolved-by-suffix
  ;; A word with several senses is what a collision *is* here, so the naming has to
  ;; survive it rather than treat it as an error.
  (converted
   (fn [dir _]
     (let [ss (set (tu/corpus-sentences dir))]
       (is (contains? ss '(wordForm dog_n "dog")))
       (is (contains? ss '(wordForm dog_n_2 "dog")))
       (is (contains? ss '(wnOffset dog_n "n00000003"))
           "and the offset is written for every synset, as the identifier to join on")
       (is (contains? ss '(wnOffset dog_n_2 "n00000008")))))))

(deftest entailment-becomes-a-defeasible-rule
  ;; The one relation in WordNet with a genuine inferential reading — and it generalizes
  ;; about usage rather than defining anything, so it is defeasible.
  (converted
   (fn [dir _]
     (is (contains? (set (tu/corpus-sentences dir))
                    '(set/defaultRule (implies (snore_v ?e) (sleep_v ?e)))))))
  (testing "and taking it as a plain fact instead is a knob"
    (converted {:entailment-rules? false}
               (fn [dir _]
                 (is (contains? (set (tu/corpus-sentences dir)) '(entails snore_v sleep_v)))))))

(deftest the-lexical-relations-come-across-as-facts
  (converted
   (fn [dir _]
     (let [ss (set (tu/corpus-sentences dir))]
       (is (contains? ss '(partMeronym dog_n tail_n)))
       (is (contains? ss '(antonym wet_a dry_a)))
       (is (contains? ss '(genl damp_a wet_a)) "an adjective satellite is a kind of its head")
       (is (contains? ss '(comment dog_n "a domesticated canine")))))))

;;; ── contexts ──────────────────────────────────────────────────────────

(deftest each-part-of-speech-is-its-own-context
  (converted
   (fn [dir _]
     (is (contains? (set (tu/corpus-file dir "WordNetNounContext.monotonic.txt"))
                    '(genl dog_n animal_n)))
     (is (contains? (set (tu/corpus-file dir "WordNetVerbContext.txt"))
                    '(wordForm snore_v "snore")))
     (is (contains? (set (tu/corpus-file dir "Topology.txt"))
                    '(genlContext WordNetNounContext WordNetContext))))))

;;; ── the corpus loads ──────────────────────────────────────────────────

(deftest the-corpus-loads-and-the-taxonomy-carries
  (converted
   (fn [dir _]
     (tu/with-cleared-kb [kb tu/fresh]
       (core-context/load-into kb)
       (let [loaded (wn/load-dir! kb (str dir) {:chain? false})]
         (is (pos? (:asserted loaded)))
         (is (zero? (:refused loaded))
             (str "nothing here contradicts anything: " (pr-str (:refusals loaded)))))
       (v/forward-chain kb {})
       (is (v/ask? kb '(composer_n Bach) 'WordNetNounContext))
       (is (v/ask? kb '(animal_n Bach) 'WordNetNounContext)
           "Bach is an animal through composer, which is what the hierarchy is for")))))

(deftest the-nouns-profile-keeps-one-part-of-speech
  (converted
   (fn [dir _]
     (tu/with-cleared-kb [kb tu/fresh]
       (core-context/load-into kb)
       (wn/load-dir! kb (str dir) {:profile :nouns :chain? false})
       (is (v/ask? kb '(genl dog_n animal_n) 'WordNetNounContext))
       (is (empty? (v/sentexes-matching kb '(wordForm snore_v "snore") 'WordNetVerbContext)))))))
