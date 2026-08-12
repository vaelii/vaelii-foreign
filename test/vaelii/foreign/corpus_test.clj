(ns vaelii.foreign.corpus-test
  "The corpus — the format every reader here converts *to*, and the one thing that loads
  it.

  Five converters agreeing about the shape of the answer is a claim, not a coincidence,
  and it is worth a test of its own: a directory any of them wrote must load through any
  of their `load-dir!`s, because the loader never asks where the sentences came from.
  That is also what lets vaelii's catalog open one without being told.

  The layering — hierarchy, schema, memberships, facts — is asserted here as *order*;
  that the order is load-bearing is `cyc-test/the-membership-layer-is-what-keeps-it`,
  which shows the same corpus losing a fact without it."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [vaelii.core :as v]
            [vaelii.foreign.atomic :as atomic]
            [vaelii.foreign.corpus :as corpus]
            [vaelii.foreign.obo :as obo]
            [vaelii.foreign.test-util :as tu]
            [vaelii.impl.core-context :as core-context])
  (:import (java.io File)))

;;; ── the order contexts load in ────────────────────────────────────────

(deftest general-first-is-topological
  (let [edges '[[b a] [c b] [d a]]
        order (corpus/general-first edges '[a b c d])]
    (is (= 'a (first order)) "the most general context comes first")
    (is (< (.indexOf ^java.util.List order 'b) (.indexOf ^java.util.List order 'c))
        "a context's supercontexts are in place before its own sentences arrive")))

(deftest a-cycle-is-broken-by-name-rather-than-hanging
  ;; A foreign hierarchy is not guaranteed acyclic, and a converter that looped on one
  ;; would be a converter that hangs on somebody's ontology rather than reporting on it.
  (let [order (corpus/general-first '[[a b] [b a]] '[a b])]
    (is (= 2 (count order)))
    (is (= #{'a 'b} (set order)))))

;;; ── what a written corpus is ──────────────────────────────────────────

(defn- written
  "Write a corpus with `emit-fn` and call `(f dir report)`."
  [spec emit-fn f]
  (tu/temp-dir "vaelii-corpus"
               (fn [^File dir]
                 (let [out (File. dir "corpus")]
                   (f out (corpus/write! (str out) spec emit-fn))))))

(deftest the-layout-is-the-documented-one
  (written
   {:format :test/v1 :source "nowhere" :names '{a {:term a :role :type}}
    :root-context 'CxTestRoot}
   (fn [emit!]
     (emit! 'CxOne :monotonic ['(genl a b)])
     (emit! 'CxOne :default   ['(a X)])
     {:mine 1})
   (fn [dir report]
     (testing "a context's two strengths are two files"
       (is (= ['(genl a b)] (tu/corpus-file dir "CxOne.monotonic.txt")))
       (is (= ['(a X)] (tu/corpus-file dir "CxOne.txt"))))
     (testing "the report carries the writer's counts and the reader's own"
       (is (= 2 (:sentences report)))
       (is (= 1 (:mine report)) "whatever emit-fn returned is merged in"))
     (testing "meta.edn says what it is and what order to read it in"
       (let [m (tu/corpus-meta dir)]
         (is (= :test/v1 (:format m)))
         (is (= 'CxTestRoot (:root-context m)))
         (is (= ['CxTestRoot 'CxOne] (:context-order m))))))))

(deftest every-context-is-wired-under-the-root-and-the-root-under-vaelii
  ;; No context arrives orphaned, however incomplete the source's own hierarchy was —
  ;; a sentence in a context nothing places is a sentence whose checks read nothing.
  (written
   {:format :test/v1 :root-context 'CxTestRoot}
   (fn [emit!]
     (emit! 'CxPlaced :default ['(a X)])
     (emit! 'CxOrphan :default ['(b Y)])
     (emit! :topology :monotonic ['(genlCx CxPlaced CxOther)])
     {})
   (fn [dir _]
     (let [topology (set (tu/corpus-file dir "Topology.txt"))]
       (is (contains? topology '(genlCx CxTestRoot CxCore))
           "the corpus hangs off vaelii's own vocabulary at one point")
       (is (contains? topology '(genlCx CxOrphan CxTestRoot)))
       (is (contains? topology '(genlCx CxPlaced CxOther))
           "a context the source placed keeps its own parent")
       (is (not (contains? topology '(genlCx CxPlaced CxTestRoot)))
           "and is not also given the root's")))))

(deftest every-corpus-carries-a-notice
  ;; The obligation travels with the corpus and not with this code, so it has to be
  ;; written into the directory — a corpus is data on a filesystem, and the notice is
  ;; what keeps it attributable once somebody has copied it somewhere else.
  (written
   {:format :test/v1 :source "somebody-elses-ontology.ttl" :root-context 'CxTestRoot
    :notice "Licensed under the Example Public License.\n"}
   (fn [emit!] (emit! 'CxOne :default ['(a X)]) {})
   (fn [dir _]
     (let [notice (slurp (File. ^File dir "NOTICE"))]
       (is (str/includes? notice "somebody-elses-ontology.ttl") "it names what it came from")
       (is (str/includes? notice "Example Public License") "and the reader's own terms"))))
  (testing "and a reader that supplies none still gets one that says so"
    (written {:format :test/v1 :source "unknown.ttl" :root-context 'CxTestRoot}
             (fn [emit!] (emit! 'CxOne :default ['(a X)]) {})
             (fn [dir _]
               (is (str/includes? (slurp (File. ^File dir "NOTICE"))
                                  "does not know this source's terms"))))))

(deftest a-file-is-truncated-rather-than-appended-to
  ;; The writer pool evicts and reopens in append mode, so a re-conversion into a
  ;; directory that already holds a corpus would otherwise double it.
  (tu/temp-dir "vaelii-corpus-twice"
               (fn [^File dir]
                 (let [out (str (File. dir "corpus"))
                       spec {:format :test/v1 :root-context 'CxTestRoot}
                       write! #(corpus/write! out spec (fn [emit!]
                                                         (emit! 'CxOne :default ['(a X)])
                                                         {}))]
                   (write!)
                   (let [report (write!)]
                     (is (= 1 (:sentences report)))
                     (is (= ['(a X)] (tu/corpus-file (File. out) "CxOne.txt"))))))))

;;; ── one format, five converters ───────────────────────────────────────

(deftest a-corpus-loads-through-any-readers-loader
  ;; `load-dir!` never asks where the sentences came from — what a format decides is only
  ;; which `profiles` a `:profile` name is looked up in.  This is the claim that lets
  ;; vaelii's catalog open a directory any converter here produced.
  (tu/temp-dir "vaelii-crossload"
               (fn [^File dir]
                 (let [out (str (File. dir "corpus"))]
                   (obo/convert! "test/resources/obo/tiny.obo" out {})
                   (tu/with-cleared-kb [kb tu/fresh]
                     (core-context/load-into kb)
                     (let [loaded (atomic/load-dir! kb out {:chain? false})]
                       (is (pos? (:asserted loaded))
                           "an OBO corpus loaded through the ATOMIC reader's load-dir!")
                       (is (zero? (:refused loaded)))
                       (is (v/ask? kb '(genl cell_division cellular_process)
                                   'CxBiologicalProcess))))))))

;;; ── layering ──────────────────────────────────────────────────────────

(deftest a-sentence-lands-in-the-layer-its-shape-says
  (is (= :hierarchy (corpus/layer-of '(genl a b))))
  (is (= :hierarchy (corpus/layer-of '(genlCx CxA CxB))))
  (is (= :schema    (corpus/layer-of '(argIsa p 1 c))))
  (is (= :schema    (corpus/layer-of '(transitive p))))
  (is (= :memberships (corpus/layer-of '(dog Rover))))
  (is (= :facts     (corpus/layer-of '(ownerOf Alice Rover))))
  (testing "a negated membership withdraws one rather than stating it, so it is a fact"
    (is (= :facts (corpus/layer-of '(not (dog Rover))))))
  (testing "what decides a non-atomic term's identity outranks even the hierarchy"
    ;; `(reifiableFunction F)` is `schema?` too, and a `genl` mentioning `(F a)`
    ;; structurally is `:hierarchy` — so either arriving first would store the NAT as a
    ;; compound no later declaration goes back for
    (is (= :terms (corpus/layer-of '(termOfUnit city_in_country_fn_canada (CityInCountryFn Canada)))))
    (is (= :terms (corpus/layer-of '(reifiableFunction CityInCountryFn))))
    (is (= :terms (corpus/layer-of '(resultIsa FruitFn fruit))))))

(deftest a-drop-reason-is-worth-nothing-without-what-kind-of-drop-it-is
  ;; The four are not degrees of the same thing: `:restated` and `:filtered` cost the
  ;; corpus nothing, `:weakened` costs it a claim's strength, and only `:unread` is a
  ;; claim the reader could not carry.  Summing them is how a report ends up alarming in
  ;; the wrong direction — OpenCyc's OWL export drops 115,744 triples and loses 1,784.
  (let [r (corpus/drop-summary {:vocabulary-declaration 94808 :structural 19152
                                :other-language 12 :datatype-range 1266}
                               '{:vocabulary-declaration :restated
                                 :structural             :restated
                                 :other-language         :filtered})]
    (is (= 1266 (:unread r)) "the only number that counts against the reader")
    (is (= '{:restated {:structural 19152 :vocabulary-declaration 94808}
             :filtered {:other-language 12}
             :unread   {:datatype-range 1266}}
           (:drops r))))

  (testing "a reason nobody classified is guilty until its author says otherwise"
    (is (= {:drops {:unread {:something-new 3}} :unread 3}
           (corpus/drop-summary {:something-new 3} {}))))

  (testing "and a kind with nothing in it is left out, so a clean report is short"
    (is (= {:drops {:filtered {:obsolete 919}} :unread 0}
           (corpus/drop-summary {:obsolete 919 :existential-intersect 0}
                                '{:obsolete :filtered :existential-intersect :weakened})))))

(deftest keep-layers-loads-a-vocabulary-without-its-instance-data
  (tu/temp-dir "vaelii-layers"
               (fn [^File dir]
                 (let [out (str (File. dir "corpus"))]
                   (obo/convert! "test/resources/obo/tiny.obo" out {})
                   (tu/with-cleared-kb [kb tu/fresh]
                     (core-context/load-into kb)
                     (corpus/load-dir! kb out {} {:keep-layers #{:hierarchy} :chain? false})
                     (is (v/ask? kb '(genl cell_division cellular_process)
                                 'CxBiologicalProcess))
                     (is (empty? (v/sentexes-matching kb '(cell_division TheObservedDivision)
                                                      'CxTiny))
                         "the memberships layer was never read"))))))
