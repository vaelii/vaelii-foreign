(ns vaelii.foreign.plugin-test
  "This artifact **is** a plugin, and that is a claim two repos have to agree on.

  The engine's own suite tests the other side of it — a build with no plugin refuses a
  foreign dump by name — which is exactly what it cannot test with a reader present.  So
  the positive half lives here: with this jar on the classpath, `vaelii.impl.foreign`
  finds every format the manifest declares, and each reader offers the keys the engine's
  call sites actually reach for.  The manifest is walked rather than spelled out, so this
  keeps holding when a format is added — or, as with `:engine-dump`, moved to a sibling.

  Nothing in this namespace requires a reader.  Everything goes through the seam, which
  is how the test also proves the manifest is what does the wiring — a typo in
  `resources/vaelii/foreign.edn` reads here as a format nobody shipped."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [vaelii.foreign.convert :as convert]
            [vaelii.impl.foreign :as foreign]))

(def ^:private manifest
  (delay (edn/read-string (slurp (io/resource foreign/manifest-resource)))))

(deftest the-manifest-is-on-the-classpath-and-well-formed
  (is (some? (io/resource foreign/manifest-resource))
      (str foreign/manifest-resource " is not on the classpath — the engine discovers "
           "formats by reading it, so without it this artifact plugs into nothing"))
  (is (map? @manifest))
  (doseq [[kind sym] @manifest]
    (testing (str kind)
      (is (keyword? kind))
      (is (qualified-symbol? sym))
      (is (str/starts-with? (namespace sym) "vaelii.foreign.")
          "a manifest here declares this artifact's own namespaces"))))

(deftest the-seam-discovers-every-declared-format
  (foreign/rescan)
  (let [found (foreign/formats)]
    (doseq [[kind sym] @manifest]
      (testing (str kind)
        (is (= sym (get found kind))
            "the seam's scan did not pick this up from the manifest")
        (is (foreign/available? kind))
        (is (map? (foreign/reader kind)))
        (is (string? (:name (foreign/reader kind))) "a reader says what it reads")))))

(deftest each-reader-offers-what-the-engine-asks-it-for
  ;; The integration contract between the two repos, stated as the keys the engine's call
  ;; sites reach for: `vaelii.impl.catalog` asks a corpus to load a directory.  A reader
  ;; map carries capability rather than implementing a protocol, so a missing key is only
  ;; ever found by asking — here, or in production.
  (doseq [[kind _] @manifest]
    (testing (str kind)
      (let [r (foreign/reader! kind)]
        (is (ifn? (:load-dir! r)))
        (is (ifn? (:convert! r)))
        (is (map? (:profiles r)) "the catalog offers a corpus by profile")
        (is (contains? (:profiles r) :full)
            "every reader has a profile that loads everything, since that is the default")))))

(deftest every-filtered-drop-is-either-reversible-or-explained
  ;; `:filtered` is the kind that says "this reader decided not to import something that
  ;; was perfectly readable".  That is a decision made on the converting party's behalf,
  ;; so it owes them either a flag or a sentence — and the two live in different files,
  ;; which is exactly the arrangement that rots.  Walking the manifest rather than a list
  ;; means a reader added later is held to it without anybody remembering to.
  (doseq [[kind _] @manifest]
    (testing (str kind)
      (let [r     (foreign/reader! kind)
            kinds (:drop-kinds r)
            flags (:drop-flags r)]
        (is (map? kinds) "a reader says what kind each of its drops is")
        (is (map? flags) "and what reverses the ones it chose to filter")
        (doseq [[reason k] kinds :when (= :filtered k)]
          (testing (str reason)
            (is (contains? flags reason)
                "a filtered drop with no entry is one nobody has justified or reversed")
            (let [v (get flags reason)]
              (is (or (keyword? v) (string? v))
                  "an entry is an option that keeps it, or prose saying why none can")
              (when (keyword? v)
                (is (contains? convert/options v)
                    (str v " is not an option any command line can set, so the flag it "
                         "names does not exist"))))))
        (testing "and nothing claims to reverse a drop of another kind"
          (doseq [[reason _] flags]
            (is (= :filtered (get kinds reason))
                "a flag for a drop that is restated or unread reverses nothing")))))))

(deftest every-flag-the-command-line-offers-reaches-a-reader
  ;; The other direction, and the one that would otherwise go unnoticed: a flag nobody
  ;; reads is a flag that silently does nothing, which is worse than no flag at all.
  (let [read-by-a-reader
        (into #{} (comp (map (fn [[kind _]] (foreign/reader! kind)))
                        (mapcat (comp vals :drop-flags))
                        (filter keyword?))
              @manifest)
        ;; The options that are not about drops at all: each changes a translation
        ;; without reversing one, so no reader's `:drop-flags` names it.  Each is
        ;; exercised where it applies — `:functional?`, `:word-forms?` and `:limit` in
        ;; `convert-test`, `:node-text?` in `atomic-test`, `:n-ary?` in `rdf-test`,
        ;; `:entailment-rules?` in `wordnet-test`, and `:context` in both `convert-test`
        ;; (how the name is spelled) and `rdf-test` (what it names).
        other '#{:functional? :word-forms? :node-text? :n-ary? :entailment-rules?
                 :context :limit}]
    (doseq [opt convert/options]
      (testing (str opt)
        (is (or (contains? read-by-a-reader opt) (contains? other opt))
            "this option is in neither a reader's :drop-flags nor the list of options
             that do something else — so setting it does nothing")))))

(deftest the-command-line-and-the-manifest-name-the-same-readers
  ;; Two tables, two audiences — the manifest is keyed by the kind an engine call site
  ;; asks for, the CLI's registry by what a person types — and nothing but this keeps
  ;; them from drifting into naming different sets of readers.
  (let [seam (set (map (fn [[kind _]] (foreign/reader! kind)) @manifest))
        cli  (set (map (comp :reader val) convert/formats))]
    (is (= seam cli))))

(deftest a-format-nobody-declares-is-still-refused
  ;; The plugin adds formats; it does not make the engine credulous about the rest.
  (is (nil? (foreign/reader :no-such-format)))
  (is (false? (foreign/available? :no-such-format)))
  (let [e (is (thrown? clojure.lang.ExceptionInfo (foreign/reader! :no-such-format)))]
    (is (= :no-foreign-reader (:type (ex-data e))))
    (is (contains? (:available (ex-data e)) :cyc-corpus)
        "the refusal names what this build does read, which is what makes it useful")))
