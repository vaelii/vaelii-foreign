(ns vaelii.foreign.turtle-test
  "Read RDF as data — the lexer half, with nothing here knowing what a triple means.

  Three syntaxes go through one reader, so the contract is that they **agree**: the same
  graph written as N-Triples and as Turtle has to come back as the same triples, or the
  translation on top of this is reading a different graph depending on which file it was
  handed.  That equivalence is the first test and the reason for the rest.

  The fixtures are hand-authored, never a slice of a real dump."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [vaelii.foreign.turtle :as ttl])
  (:import (java.io StringReader)))

(defn- read-all [s] (doall (ttl/triples (StringReader. s))))

(def ^:private ex "http://example.org/")
(defn- e [local] (ttl/iri (str ex local)))

;;; ── the three syntaxes agree ──────────────────────────────────────────

(deftest ntriples-and-turtle-read-the-same-graph
  (let [nt (read-all (str "<http://example.org/Dog> <http://example.org/sub> <http://example.org/Mammal> .\n"
                          "<http://example.org/Dog> <http://example.org/name> \"dog\" .\n"))
        tt (read-all (str "@prefix ex: <http://example.org/> .\n"
                          "ex:Dog ex:sub ex:Mammal ; ex:name \"dog\" .\n"))]
    (is (= [{:s (e "Dog") :p (e "sub")  :o (e "Mammal")}
            {:s (e "Dog") :p (e "name") :o "dog"}]
           nt))
    (is (= nt tt) "the same graph, written twice, reads once")))

(deftest sparql-keyword-directives-read-like-the-at-forms
  (is (= (read-all "@prefix ex: <http://example.org/> .\nex:a ex:b ex:c .\n")
         (read-all "PREFIX ex: <http://example.org/>\nex:a ex:b ex:c .\n"))))

(deftest a-prefixed-name-starting-with-p-or-b-is-not-a-directive
  ;; `PREFIX` / `BASE` are bare tokens and so is `bfo:0000001`, so the reader has to read
  ;; one and look — and hand it back unread when it was a subject after all.
  (is (= [{:s (e "prefixish") :p (e "p") :o (e "o")}]
         (read-all "@prefix ex: <http://example.org/> .\nex:prefixish ex:p ex:o .\n"))))

;;; ── quads ─────────────────────────────────────────────────────────────

(deftest a-fourth-term-is-the-graph-not-a-predicate
  ;; Turtle requires `;` between predicate-object pairs, which is exactly what makes a
  ;; bare fourth term unambiguous.
  (is (= [{:s (e "a") :p (e "p") :o (e "b") :g (e "G")}]
         (read-all "<http://example.org/a> <http://example.org/p> <http://example.org/b> <http://example.org/G> .\n")))
  (testing "every triple of a quad statement carries the graph"
    (is (= [(e "G") (e "G")]
           (map :g (read-all (str "@prefix ex: <http://example.org/> .\n"
                                  "ex:a ex:p ex:b , ex:c ex:G .\n")))))))

;;; ── literals ──────────────────────────────────────────────────────────

(deftest a-literal-is-a-plain-value-until-it-has-something-to-say
  (let [o #(:o (first (read-all (str "@prefix ex: <http://example.org/> .\n"
                                     "@prefix xsd: <http://www.w3.org/2001/XMLSchema#> .\n"
                                     "ex:s ex:p " % " .\n"))))]
    (is (= "text" (o "\"text\"")))
    (is (= 42 (o "42")))
    (is (= 1.5 (o "1.5")))
    (is (= true (o "true")))
    (testing "a datatype vaelii reads natively becomes the value, not a tagged map"
      (is (= 42 (o "\"42\"^^xsd:integer")))
      (is (= true (o "\"true\"^^xsd:boolean")))
      (is (= "text" (o "\"text\"^^xsd:string"))))
    (testing "a language tag always survives — it is the only thing telling two
              translations of one string apart"
      (is (= {:lex "dog" :lang "en"} (o "\"dog\"@en"))))
    (testing "a datatype with no native reading keeps its lexical form and its tag"
      (is (= {:lex "2020-01-01"
              :datatype (ttl/iri "http://www.w3.org/2001/XMLSchema#date")}
             (o "\"2020-01-01\"^^xsd:date"))))))

(deftest a-triple-quoted-literal-spans-lines
  (is (= "a long\ncomment"
         (:o (first (read-all (str "@prefix ex: <http://example.org/> .\n"
                                   "ex:s ex:p \"\"\"a long\ncomment\"\"\" .\n")))))))

(deftest escapes-are-resolved
  (is (= "a\tb\"c" (:o (first (read-all "<http://x/s> <http://x/p> \"a\\tb\\\"c\" ."))))))

;;; ── blank nodes are expanded ──────────────────────────────────────────

(deftest a-property-list-becomes-triples-about-a-fresh-node
  (let [ts (read-all (str "@prefix ex: <http://example.org/> .\n"
                          "ex:s ex:p [ ex:q ex:v ] .\n"))
        b  (:o (last ts))]
    (is (ttl/bnode? b) "the outer triple's object is the node")
    (is (= [{:s b :p (e "q") :o (e "v")}] (butlast ts))
        "and the inner triples are emitted before it")))

(deftest a-collection-becomes-a-first-rest-chain
  (let [ts (read-all (str "@prefix ex: <http://example.org/> .\n"
                          "ex:s ex:p ( ex:a ex:b ) .\n"))
        rdf #(ttl/iri (str ttl/rdf-ns %))]
    (is (= 5 (count ts)) "two cells, two links each, plus the statement itself")
    (is (= #{(e "a") (e "b")}
           (set (keep (fn [{:keys [p o]}] (when (= p (rdf "first")) o)) ts))))
    (is (some (fn [{:keys [p o]}] (and (= p (rdf "rest")) (= o (rdf "nil")))) ts)
        "the chain terminates in rdf:nil")))

(deftest an-empty-collection-is-rdf-nil
  (is (= (ttl/iri (str ttl/rdf-ns "nil"))
         (:o (first (read-all "@prefix ex: <http://example.org/> .\nex:s ex:p ( ) .\n"))))))

;;; ── base ──────────────────────────────────────────────────────────────

(deftest a-relative-iri-resolves-against-the-base
  (let [{:keys [s p o]} (first (read-all (str "@base <http://example.org/base/> .\n"
                                              "<rel> <#frag> <http://abs.org/x> .\n")))]
    (is (= (ttl/iri "http://example.org/base/rel") s))
    (is (= (ttl/iri "http://example.org/base/#frag") p) "a fragment hangs off the base")
    (is (= (ttl/iri "http://abs.org/x") o) "an absolute reference stands")))

;;; ── recovery ──────────────────────────────────────────────────────────

(deftest a-malformed-statement-costs-that-statement-and-no-more
  ;; A dump of a hundred million triples has a few bad lines in it, and losing the file
  ;; to one of them is worse than losing the statement.
  (let [ts (read-all (str "@prefix ex: <http://example.org/> .\n"
                          "ex:a ex:b nosuch:c .\n"          ; an undeclared prefix
                          "ex:good ex:p ex:q .\n"))]
    (is (= [{:s (e "good") :p (e "p") :o (e "q")}] ts)))
  (testing "and a half-read statement states nothing at all"
    (is (empty? (read-all (str "@prefix ex: <http://example.org/> .\n"
                               "ex:a ex:b ex:c , nosuch:d .\n"))))))

(deftest comments-and-blank-lines-are-skipped
  (is (= 1 (count (read-all (str "# a comment\n\n"
                                 "@prefix ex: <http://example.org/> .\n"
                                 "# another\n"
                                 "ex:a ex:b ex:c .\n"))))))

;;; ── streaming ─────────────────────────────────────────────────────────

(deftest the-seq-is-lazy
  ;; A dump does not fit in memory, so the reader has to be a seq over a stream rather
  ;; than a parse of a document.  Taking one triple must not read the rest — so the tail
  ;; is a statement that throws under `:strict? true`, and reaching it is the failure
  ;; this asserts against.  The third assertion is what makes the second one mean
  ;; something: a tail that never throws would prove nothing about the take.
  (let [big (str (str/join (repeat 20000 "<http://x/s> <http://x/p> <http://x/o> .\n"))
                 "<http://x/s> <http://x/p> <http://x/o> <http://x/g> <http://x/extra> .\n")
        ts  (ttl/triples (StringReader. big) {:strict? true})]
    (is (instance? clojure.lang.LazySeq ts))
    (is (= 1 (count (take 1 ts))))
    (is (thrown? clojure.lang.ExceptionInfo (dorun ts)))))

(deftest recovery-skips-over-a-single-quoted-literal
  ;; `skip-to-dot!` steps over both quote kinds: a `.` inside a single-quoted literal
  ;; is part of the literal, not the statement's end.  With the malformed token before
  ;; the literal, recovery walks past `'x. y'` to the real terminating `.` rather than
  ;; stopping at the dot inside it and splitting the tail into spurious triples.
  (let [ts (read-all (str "@prefix ex: <http://example.org/> .\n"
                          "ex:a nosuch:b 'x. y' .\n"       ; malformed: undeclared prefix, then a '…' with a dot
                          "ex:good ex:p ex:q .\n"))]
    (is (= [{:s (e "good") :p (e "p") :o (e "q")}] ts)
        "the dot inside the single-quoted literal did not end recovery early")))
