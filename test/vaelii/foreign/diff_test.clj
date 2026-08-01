(ns vaelii.foreign.diff-test
  "Comparing two corpora.

  The interesting use is large and lives outside the suite — OpenCyc's binary dump
  against its own OWL export, two readers that share no code below `corpus/write!`.
  What is tested here is the property that makes such a comparison mean anything: a
  corpus differs from itself in nothing, and two corpora of the same graph differ in
  nothing either.  A diff that reported spurious differences would make the large run
  unreadable, since the whole skill being asked for there is telling a real difference
  from an artefact of the comparison."
  (:require [clojure.test :refer [deftest is testing]]
            [vaelii.foreign.diff :as diff]
            [vaelii.foreign.obo :as obo]
            [vaelii.foreign.rdf :as rdf]
            [vaelii.foreign.test-util :as tu])
  (:import (java.io File)))

(defn- corpora
  "Convert each of `sources` with `convert!` and call `(f dirs)` on the results."
  [convert! sources f]
  (tu/temp-dir "vaelii-diff"
               (fn [^File dir]
                 (f (doall (map-indexed
                            (fn [i src]
                              (let [out (File. dir (str "c" i))]
                                (convert! src (str out) {})
                                out))
                            sources))))))

(deftest a-corpus-differs-from-itself-in-nothing
  (corpora rdf/convert! ["test/resources/rdf/tiny.ttl"]
           (fn [[d]]
             (let [r (diff/compare-corpora d d)]
               (is (zero? (get-in r [:only-in-a :terms])))
               (is (zero? (get-in r [:only-in-b :genl])))
               (is (= "100.0%" (get-in r [:shared :genl-agreement])))))))

(deftest the-same-graph-in-two-syntaxes-diffs-to-nothing
  ;; The end-to-end statement of what the RDF/XML lexer is for: Turtle in, RDF/XML in,
  ;; and the two corpora are indistinguishable to something that only reads corpora.
  (corpora rdf/convert! ["test/resources/rdf/tiny.ttl" "test/resources/rdf/tiny.owl"]
           (fn [[a b]]
             (let [r (diff/compare-corpora a b)]
               (is (= "100.0%" (get-in r [:shared :genl-agreement])))
               (is (zero? (get-in r [:only-in-a :terms])))
               (is (zero? (get-in r [:only-in-b :terms])))
               (is (empty? (get-in r [:predicates :only-in-a])))
               (is (empty? (get-in r [:predicates :only-in-b])))))))

(deftest two-different-ontologies-diff-to-something
  ;; The other half: a diff that reported nothing whatever it was given would pass the
  ;; tests above and be useless.
  (corpora (fn [src out opts] (if (re-find #"\.obo$" src)
                                (obo/convert! src out opts)
                                (rdf/convert! src out opts)))
           ["test/resources/rdf/tiny.ttl" "test/resources/obo/tiny.obo"]
           (fn [[a b]]
             (let [r (diff/compare-corpora a b)]
               (testing "two unrelated ontologies share no taxonomy"
                 (is (pos? (get-in r [:only-in-a :terms])))
                 (is (pos? (get-in r [:only-in-b :terms])))
                 (is (zero? (get-in r [:shared :genl]))))
               (testing "and the report names what is on each side"
                 (is (seq (get-in r [:examples :terms-only-in-a])))
                 (is (seq (get-in r [:examples :terms-only-in-b]))))))))

(deftest a-corpus-with-non-atomic-terms-still-compares
  ;; A Cyc corpus has `(genl (AbnormalFn cell) …)` in it — a collection named by applying
  ;; a function — and a list is not Comparable.  Sorting the examples by `compare` threw
  ;; on the first real corpus this was pointed at, so the ordering is by `str`.
  (corpora rdf/convert! ["test/resources/rdf/nary.ttl"]
           (fn [[d]]
             (is (map? (diff/compare-corpora d d))
                 "comparing a corpus must not depend on its terms being atomic"))))
