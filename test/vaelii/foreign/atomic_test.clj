(ns vaelii.foreign.atomic-test
  "Translate an ATOMIC-2020 release into a vaelii corpus, and load it.

  ATOMIC is the corpus whose content is already the shape vaelii's assumption strengths
  were built for: every tuple is a defeasible generalization about what usually follows,
  and none of it is a definition.  So the claims here are about the graph — that two
  rows naming the same phrase name the same individual — and about strength: nothing in
  this corpus may land `:monotonic`.

  The fixture is hand-authored, never a slice of a real release: a reader is a
  capability, and its checked-in test data is invented."
  (:require [clojure.test :refer [deftest is testing]]
            [vaelii.core :as v]
            [vaelii.foreign.atomic :as atomic]
            [vaelii.foreign.test-util :as tu]
            [vaelii.impl.core-context :as core-context])
  (:import (java.io File)))

(def ^:private fixture "test/resources/atomic")

(defn- converted
  ([f] (converted {} f))
  ([opts f]
   (tu/temp-dir "vaelii-atomic"
                (fn [^File dir]
                  (let [out (File. dir "corpus")]
                    (f out (atomic/convert! fixture (str out) opts)))))))

;;; ── the format ────────────────────────────────────────────────────────

(deftest a-row-parses-and-a-blank-line-does-not
  (is (= {:head "PersonX runs" :relation "xIntent" :tail "to exercise"}
         (atomic/parse-line "PersonX runs\txIntent\tto exercise")))
  (is (nil? (atomic/parse-line "")))
  (is (nil? (atomic/parse-line "two\tfields")))
  (testing "the ___ blank is content — it is what makes an event a template"
    (is (= "PersonX abandons ___ altogether"
           (:head (atomic/parse-line "PersonX abandons ___ altogether\txIntent\tto be selfish"))))))

(deftest every-tsv-in-a-directory-is-read
  (converted
   (fn [_ report]
     (is (= 13 (:rows report)) "train.tsv and dev.tsv both, and neither twice"))))

;;; ── the reading ───────────────────────────────────────────────────────

(deftest a-relation-becomes-a-predicate-and-a-phrase-an-individual
  (converted
   (fn [dir _]
     (let [ss (set (tu/corpus-sentences dir))]
       (is (contains? ss '(xIntent PersonXAbandonsAltogether ToBeSelfish)))
       (is (contains? ss '(objectUse BaseballBat HitABall))
           "a relation spelled CapitalCamelCase in the source is still a predicate here")))))

(deftest the-same-phrase-is-the-same-node
  ;; What makes the result a graph rather than a list: `to find a new home` reached as
  ;; PersonX's want and reached again as a head is one individual.
  (converted
   (fn [dir _]
     (let [ss (set (tu/corpus-sentences dir))]
       (is (contains? ss '(xWant PersonXAbandonsAltogether ToFindANewHome)))
       (is (contains? ss '(xAttr ToFindANewHome Responsible)))))))

(deftest a-node-keeps-its-english
  (converted
   (fn [dir _]
     (is (contains? (set (tu/corpus-sentences dir))
                    '(nodeText PersonXAbandonsAltogether "PersonX abandons ___ altogether"))
         "the only way back to the source")))
  (testing "and dropping it is a knob, since it is a third of the sentences"
    (converted {:node-text? false}
               (fn [dir _]
                 (is (not-any? #(= 'nodeText (first %)) (tu/corpus-sentences dir)))))))

(deftest a-none-tail-is-an-annotation-and-not-a-fact
  (converted
   (fn [dir report]
     (is (= 1 (get-in report [:drop-reasons :no-tail])))
     (is (= {:filtered {:no-tail 1}} (:drops report))
         "a policy, not a row this reader could not read")
     (is (zero? (:unread report)))
     (is (not-any? #(= 'None (last %)) (tu/corpus-sentences dir))
         "importing it would state, of a great many events, that they cause a thing called None")))

  (testing "but the objection is a judgement, so --empty-tails overrules it"
    ;; The annotators looked and found nothing, which is information somebody may want
    ;; even at the cost of the reading above.  Deciding that is not this reader's job.
    (converted {:empty-tails? true}
               (fn [dir report]
                 (is (nil? (get-in report [:drop-reasons :no-tail])))
                 (is (some #(= 'None (last %)) (tu/corpus-sentences dir))
                     "the tail is named and the row is written, both passes agreeing")))))

(deftest an-unrecognised-relation-is-imported-rather-than-lost
  ;; A release that adds a relation should arrive, not vanish — the family table decides
  ;; the context, not whether the row is read.
  (converted
   (fn [dir _]
     (is (contains? (set (tu/corpus-file dir "AtomicEventContext.txt"))
                    '(newRelation PersonXPlaysTheGuitar SomethingUnrecognised))))))

;;; ── strength and contexts ─────────────────────────────────────────────

(deftest nothing-in-this-corpus-is-monotonic
  (converted
   (fn [dir _]
     (is (nil? (tu/corpus-file dir "AtomicSocialContext.monotonic.txt")))
     (is (nil? (tu/corpus-file dir "AtomicPhysicalContext.monotonic.txt")))
     (is (nil? (tu/corpus-file dir "AtomicContext.monotonic.txt"))
         "every tuple is what people said usually follows, and no other strength is honest"))))

(deftest each-relation-family-is-its-own-context
  (converted
   (fn [dir _]
     (is (contains? (set (tu/corpus-file dir "AtomicSocialContext.txt"))
                    '(oReact PersonXAbandonsAltogether Abandoned)))
     (is (contains? (set (tu/corpus-file dir "AtomicPhysicalContext.txt"))
                    '(madeUpOf BaseballBat Wood)))
     (is (contains? (set (tu/corpus-file dir "AtomicEventContext.txt"))
                    '(isAfter PersonXAbandonsAltogether PersonXGetsTiredOfIt))))))

;;; ── the corpus loads ──────────────────────────────────────────────────

(deftest the-corpus-loads-and-is-queryable
  (converted
   (fn [dir _]
     (tu/with-cleared-kb [kb tu/fresh]
       (core-context/load-into kb)
       (let [loaded (atomic/load-dir! kb (str dir) {:chain? false})]
         (is (pos? (:asserted loaded)))
         (is (zero? (:refused loaded))
             (str "nothing here contradicts anything: " (pr-str (:refusals loaded)))))
       (is (v/ask? kb '(xIntent PersonXAbandonsAltogether ToBeSelfish) 'AtomicSocialContext))
       (is (seq (v/sentexes-matching kb '(madeUpOf BaseballBat ?x) 'AtomicPhysicalContext)))))))

(deftest a-family-profile-loads-one-half
  (converted
   (fn [dir _]
     (tu/with-cleared-kb [kb tu/fresh]
       (core-context/load-into kb)
       (atomic/load-dir! kb (str dir) {:profile :physical :chain? false})
       (is (v/ask? kb '(madeUpOf BaseballBat Wood) 'AtomicPhysicalContext))
       (is (empty? (v/sentexes-matching kb '(xIntent PersonXAbandonsAltogether ToBeSelfish)
                                        'AtomicSocialContext))
           "the social half and the physical half answer different questions")))))
