(ns vaelii.foreign.conformance-test
  "The W3C RDF 1.1 syntax suites, run against both lexers.

  These tests **skip** when the corpus they need is absent, which is the normal state of
  a fresh checkout: it is third-party, large, and fetched by `scripts/fetch-suites.sh`
  rather than vendored.  Everything else in this repo's test tree runs offline, and that
  is deliberate — this namespace is the one place a network ever enters.

  **The W3C half does not wait for somebody to remember it.**  `test.yml`'s
  `conformance` job fetches `rdf-tests` at the revision pinned in the fetch script,
  checks the four manifests really landed — a half-fetched cache would skip here and
  pass, which is the failure that job is shaped around — and runs `lein test :suite`.
  Which copy of the tree runs it automatically is the tier rule in that file's header.
  The floors below are therefore a gate rather than a reading taken by hand, and the pin
  is what makes them one: a corpus free to move under a ratchet turns the W3C's next
  test into our red build.  The OBO half stays a local run for the same reason inverted
  — those are PURLs to unversioned ontologies, and there is no revision to pin.

  **What is asserted, and why it is two different things.**  A reader has two jobs and
  this repo needs them held to different standards:

  - *Reading what is valid* is the contract.  Every eval and positive-syntax test must
    pass, at 100%, or an ontology somebody publishes will not open.  There is no
    tolerance to spend here and the floor is exact.
  - *Refusing what is invalid* is a quality, and it trades against a property this
    reader deliberately has: `turtle/triples` recovers from a malformed statement by
    skipping it, because a hundred-million-triple dump has a few bad lines and losing
    the file to one of them is worse.  The suites are run with `:strict? true`, which
    turns recovery off, and the floors below are ratchets — raise them when a fix lands,
    never lower them to make a change pass.

  The gap that remains is mostly one design decision: this is *one grammar for three
  syntaxes*, so a fourth term reads as an N-Quads graph label rather than as the error
  Turtle alone would call it.  `vaelii.foreign.turtle`'s docstring argues for that, and
  a handful of `turtle-syntax-bad-struct-*` tests are what it costs."
  (:require [clojure.test :refer [deftest is testing]]
            [vaelii.foreign.obo :as obo]
            [vaelii.foreign.rdfxml :as rdfxml]
            [vaelii.foreign.suite :as suite]
            [vaelii.foreign.test-util :as tu]
            [vaelii.foreign.turtle :as ttl]))

(def ^:private floors
  "`suite -> {test-type minimum-pass-rate}`.  100 means every one, and is a contract;
  anything less is a ratchet.  Measured, not guessed — the numbers a run produces today
  are in the commit that set them."
  {"rdf-turtle"    {"TestTurtleEval" 100, "TestTurtlePositiveSyntax" 100,
                    "TestTurtleNegativeSyntax" 73}
   "rdf-n-triples" {"TestNTriplesPositiveSyntax" 100, "TestNTriplesNegativeSyntax" 48}
   "rdf-n-quads"   {"TestNQuadsPositiveSyntax" 100, "TestNQuadsNegativeSyntax" 44}
   "rdf-xml"       {"TestXMLEval" 100, "TestXMLNegativeSyntax" 97}})

(defn- run-suite
  "Run the suite in `dir` with `parse`, returning `{test-type [results]}`."
  [dir parse]
  (group-by :type (map #(suite/run % parse) (suite/manifest dir))))

(defn- check
  "Assert every floor for one suite, and report the rate whether or not it holds — a
  number that moved is the useful output even when nothing failed."
  [name* dir parse]
  (let [by-type (run-suite dir parse)]
    (doseq [[ty floor] (sort (floors name*))]
      (let [rs (by-type ty)
            {:keys [pass total]} (suite/summarize rs)
            rate (if (pos? (long total)) (/ (* 100.0 (long (or pass 0))) (long total)) 0.0)]
        (println (format "  %-14s %-26s %3d/%-3d  %5.1f%%  (floor %d%%)"
                         name* ty (long (or pass 0)) (long total) rate (long floor)))
        (is (>= rate (double floor))
            (str name* " " ty " fell to " (format "%.1f%%" rate)
                 " — failures: "
                 (pr-str (mapv :name (remove #(= :pass (:outcome %)) rs)))))))))

(deftest ^:suite the-turtle-family-against-the-w3c-suites
  (testing "Turtle, N-Triples and N-Quads — one reader, three syntaxes"
    (if-let [root (suite/cached "rdf-tests" "rdf" "rdf11")]
      (let [parse (fn [r opts] (ttl/triples r opts))]
        (doseq [d ["rdf-turtle" "rdf-n-triples" "rdf-n-quads"]]
          (check d (java.io.File. root ^String d) parse)))
      (println (suite/missing-note "the W3C RDF syntax suites")))))

(deftest ^:suite rdf-xml-against-the-w3c-suite
  (testing "RDF/XML — the syntax an OWL ontology is actually published in"
    (if-let [root (suite/cached "rdf-tests" "rdf" "rdf11")]
      (check "rdf-xml" (java.io.File. root "rdf-xml") (fn [r opts] (rdfxml/triples r opts)))
      (println (suite/missing-note "the W3C RDF/XML suite")))))

;;; ── OBO has no conformance suite, so real ontologies are the test ─────

(def ^:private obo-ontologies
  "The three small Foundry ontologies `scripts/fetch-suites.sh` caches, and what each is
  here to catch.  Chosen for their *relations* layer, which is the part a hand-authored
  fixture is least able to stress and the part every real drop turned out to be about."
  [{:file "uo.obo"   :what "units — no [Typedef] stanzas at all, and 80 uses of an imported relation"}
   {:file "ro.obo"   :what "the Relations Ontology itself — qualifier blocks on relationship lines"}
   {:file "pato.obo" :what "qualities — a long obsolescence tail"}])

(deftest ^:suite obo-converts-real-foundry-ontologies
  (testing "no OBO conformance suite exists, so the standard is: nothing goes unexplained"
    ;; The OBO Foundry publishes no pass/fail suite the way the W3C does, so this asserts
    ;; the next best thing and the one that actually found bugs — that every dropped
    ;; stanza is dropped for a reason the reader can *name*.  A `:malformed-…` or
    ;; `:unknown-…` count on a published ontology is far likelier to be our bug than
    ;; theirs, and both times it was: `{all_only="true"}` qualifiers read as a third
    ;; word, and relations referenced across an ontology boundary without a local
    ;; declaration.
    ;; Each ontology is looked up on its own rather than gated on one of them: an
    ;; interrupted fetch leaves some of the three, and a half-filled cache should skip
    ;; what it is missing rather than hand the converter an empty path.
    (let [present (filter :src (map #(assoc % :src (suite/cached "obo" (:file %)))
                                    obo-ontologies))]
      (if (seq present)
        (tu/temp-dir "vaelii-obo"
                     (fn [^java.io.File dir]
                       (doseq [{:keys [file what src]} present]
                         (let [out (java.io.File. dir ^String file)
                               report (obo/convert! (str src) (str out) {})
                               reasons (:drop-reasons report)
                               suspect (select-keys reasons
                                                    (filter #(re-find #"^(unknown|malformed)" (name %))
                                                            (keys reasons)))]
                           (println (format "  %-10s %6d sentences  %5d dropped  %s"
                                            file (:sentences report) (:dropped report) what))
                           (is (pos? (:sentences report)) (str file " converted nothing"))
                           (is (empty? suspect)
                               (str file " has drops the reader could not explain: " (pr-str suspect)
                                    " — on a published Foundry ontology that is our bug, not theirs"))))))
        (println (suite/missing-note "the OBO Foundry ontologies"))))))

(deftest ^:suite both-lexers-agree-about-a-literal
  (testing "the same literal, written in either syntax, is the same Clojure value"
    ;; Not a W3C test — this is the invariant that lets `vaelii.foreign.rdf` be written
    ;; once.  Two lexers that normalized differently would give one ontology two readings
    ;; depending on which file it shipped as, and nothing downstream could tell.
    (let [ttl-graph (with-open [r (java.io.StringReader.
                                   (str "<http://e/s> <http://e/p> \"10\"^^"
                                        "<http://www.w3.org/2001/XMLSchema#integer> ."
                                        "<http://e/s> <http://e/q> \"dog\"@en ."))]
                      (into #{} (ttl/triples r {})))
          xml-graph (with-open [r (java.io.StringReader.
                                   (str "<rdf:RDF xmlns:rdf='http://www.w3.org/1999/02/22-rdf-syntax-ns#'"
                                        " xmlns:e='http://e/'>"
                                        "<rdf:Description rdf:about='http://e/s'>"
                                        "<e:p rdf:datatype='http://www.w3.org/2001/XMLSchema#integer'>10</e:p>"
                                        "<e:q xml:lang='en'>dog</e:q>"
                                        "</rdf:Description></rdf:RDF>"))]
                      (into #{} (rdfxml/triples r {})))]
      (is (= ttl-graph xml-graph))
      (is (= 10 (:o (first (filter #(= "http://e/p" (ttl/iri-str (:p %))) ttl-graph))))
          "a native XSD integer is a Clojure long, not a tagged map")
      (is (= {:lex "dog" :lang "en"}
             (:o (first (filter #(= "http://e/q" (ttl/iri-str (:p %))) xml-graph))))
          "a language tag survives, because it is the only thing telling translations apart"))))
