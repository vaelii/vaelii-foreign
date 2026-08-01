(ns vaelii.foreign.convert-test
  "The command line's own tables, and the half of the option vocabulary nothing else
  checks.

  `plugin-test` holds the `:filtered` contract from both ends: every filtered drop names
  a flag or a justification, and every flag a reader names is an option the CLI can set.
  To do that it has to allow for the options that are **not** about drops — the ones that
  change a translation without reversing one — and it did that by naming this namespace
  before this namespace existed.  Three of the seven it waved through (`:functional?`,
  `:word-forms?`, `:limit`) had no test anywhere at all.

  So the point here is narrow: a flag that parses to nothing, or parses to an option no
  reader reads, is a flag that silently does nothing, and the whole argument for handing
  a filtered drop back to the person converting the ontology rests on the flags actually
  working."
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [vaelii.foreign.convert :as convert]
            [vaelii.foreign.obo :as obo]
            [vaelii.foreign.test-util :as tu]
            [vaelii.foreign.wordnet :as wordnet])
  (:import (java.io File)))

(def ^:private parse #'convert/flag-options)

;;; ── the tables ────────────────────────────────────────────────────────

(deftest every-flag-the-tables-declare-parses-to-what-it-declares
  (testing "a boolean flag sets its option, and only when it is present"
    (doseq [[flag [k v]] convert/boolean-flags]
      (testing flag
        (is (= v (get (parse [flag]) k))
            "the flag is present and the option did not come out as the table says")
        (is (not (contains? (parse []) k))
            "and an absent flag leaves the option unset rather than defaulting here"))))

  (testing "a value flag takes the next argument"
    (is (= 'SomeContext (:context (parse ["--context" "SomeContext"]))))
    (is (= #{"en" "fr"} (:languages (parse ["--languages" "en,fr"]))))
    (is (= 5 (:limit (parse ["--limit" "5"])))))

  (testing "a value flag with no value is ignored rather than crashing"
    ;; A trailing `--limit` is a typo, and losing the whole conversion to it would be a
    ;; worse answer than reading the rest of the command line.
    (is (not (contains? (parse ["--limit"]) :limit)))))

(deftest a-context-name-from-the-command-line-is-spelled-like-any-other
  ;; The one context name a person supplies rather than a reader mints.  It becomes a
  ;; file name inside the corpus, so it goes through `term/spell` like every minted one
  ;; and cannot bring a path along with it.
  (is (= 'ZooContext (:context (parse ["--context" "Zoo"]))))
  (is (= 'ZooContext (:context (parse ["--context" "zoo"]))))
  (is (= 'ZooContext (:context (parse ["--context" "ZooContext"])))
      "an already-spelled name keeps its spelling rather than gaining a second ending")
  (is (= 'EscapeContext (:context (parse ["--context" "../../escape"])))
      "and a name that would climb out of the corpus does not survive the spelling"))

(deftest the-options-set-is-exactly-what-the-two-tables-name
  ;; `plugin-test` checks each reader's `:drop-flags` against this set, so a key missing
  ;; from it reads there as a flag nobody can set.
  (is (= convert/options
         (into (set (map first (vals convert/boolean-flags)))
               (map first (vals convert/value-flags)))))
  (is (empty? (set/intersection (set (keys convert/boolean-flags))
                                (set (keys convert/value-flags))))
      "a flag that is both boolean and value-taking would parse two ways"))

;;; ── the three nothing else exercised ──────────────────────────────────

(deftest a-limit-reads-only-that-many
  ;; `:limit` has to be honoured in **both** passes or the second reads records the
  ;; first never named, which is the failure mode a one-pass test cannot see.
  (tu/temp-dir
   "vaelii-convert-limit"
   (fn [^File dir]
     (let [full    (wordnet/convert! "test/resources/wordnet/dict"
                                     (str (File. dir "all")) {})
           limited (wordnet/convert! "test/resources/wordnet/dict"
                                     (str (File. dir "some")) {:limit 2})]
       (is (= 2 (:synsets limited)))
       (is (< (:synsets limited) (:synsets full)) "the fixture is too small to tell")
       (is (< (:sentences limited) (:sentences full)))))))

(deftest a-word-form-is-a-third-of-wordnet-and-can-be-left-out
  (tu/temp-dir
   "vaelii-convert-wordforms"
   (fn [^File dir]
     (let [with    (File. dir "with")
           without (File. dir "without")]
       (wordnet/convert! "test/resources/wordnet/dict" (str with) {})
       (wordnet/convert! "test/resources/wordnet/dict" (str without) {:word-forms? false})
       (is (some #(= 'wordForm (first %)) (tu/corpus-sentences with)))
       (is (not-any? #(= 'wordForm (first %)) (tu/corpus-sentences without))
           "--no-word-forms is the flag, and it is read")))))

;;; ── failing legibly ───────────────────────────────────────────────────

(defn- dying
  "Call `f` with `die` throwing instead of exiting, and return the message it was given
  — or `::lived` if it was never called.

  Redefined rather than called: `die` ends the JVM, and `-main` follows every command
  with `shutdown-agents`, so the command line's failure paths are reachable in-process
  only one layer down.  What is checked here is what a person would read."
  [f]
  (with-redefs-fn {#'convert/die (fn [& msg] (throw (ex-info (apply str msg) {::died true})))}
    (fn []
      (try (f) ::lived
           (catch clojure.lang.ExceptionInfo e
             (if (::died (ex-data e)) (ex-message e) (throw e)))))))

(deftest a-path-that-is-not-a-corpus-is-named-rather-than-thrown-at
  ;; Every one of these used to answer with a stack trace and a `Full report at:
  ;; /var/folders/…` line, for the mistakes a tool run by hand, once, collects most of.
  (tu/temp-dir
   "vaelii-convert-legible"
   (fn [^File dir]
     (testing "a source that is not there"
       (is (re-find #"does not exist"
                    (dying #(@#'convert/existing "source" (str (File. dir "nope")))))))

     (testing "a directory with no meta.edn is not a corpus"
       (.mkdirs (File. dir "empty"))
       (is (re-find #"not a corpus directory"
                    (dying #(@#'convert/corpus-dir (str (File. dir "empty")))))))

     (testing "a corpus whose write did not finish"
       (let [half (File. dir "half")]
         (.mkdirs half)
         (spit (File. half "meta.edn") (pr-str {:format :vaelii-obo-corpus/v1}))
         (is (re-find #"no kb/ in it"
                      (dying #(@#'convert/corpus-dir (str half))))
             "`diff` reaches listFiles on this one, so the check is the CLI's")
         (is (re-find #"has no report\.edn"
                      (dying #(@#'convert/corpus-file half "report.edn"))))))

     (testing "a corpus format this build does not read"
       ;; Named rather than guessed at: every converter writes its format into meta.edn,
       ;; so a directory that says something else is one this build cannot open.
       (let [alien (File. dir "alien")]
         (.mkdirs alien)
         (spit (File. alien "meta.edn") (pr-str {:format :some-other-tool/v9}))
         (let [msg (dying #(@#'convert/corpus-reader alien))]
           (is (re-find #"unknown corpus format" msg))
           (is (re-find #"vaelii-obo-corpus" msg)
               "the refusal names what this build does read"))))

     (testing "and a corpus it does read names the reader that wrote it"
       (let [real (File. dir "real")]
         (obo/convert! "test/resources/obo/tiny.obo" (str real) {})
         (is (= "obo" (first (@#'convert/corpus-reader real)))))))))

(deftest a-functional-declaration-arrives-only-when-it-is-asked-for
  ;; Off by default for the reason `cyc/metadata-metatypes` gives: vaelii's `functional`
  ;; merges a second value into the first through the equality closure, which is not what
  ;; a source declaring a functional property said.  Importing it anyway is the flag.
  (let [ss  (vec (obo/stanzas ["[Typedef]" "id: has_boss" "name: has boss"
                               "is_functional: true"]))
        nm  (obo/name-table (obo/classify ss {}))
        for* (fn [opts] (set (map second (:sentences (obo/translate (second ss) nm
                                                                    'TestContext opts)))))]
    (is (= 'hasBoss (:term (nm "has_boss"))) "the fixture names what the test is about")
    (is (not-any? #(= 'functional (first %)) (for* {})))
    (is (contains? (for* {:functional? true}) '(functional hasBoss)))))
