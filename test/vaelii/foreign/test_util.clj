(ns vaelii.foreign.test-util
  "KB scaffolding for this repo's tests.

  vaelii ships its engine, not its test tree, so a dependent artifact cannot reach the
  suite's own helpers — this is the small part of that scaffolding the reader tests use,
  and it keeps the same meanings so a test reads the same in either repo.

  **The space block.**  A test KB is named by a *pair* of space numbers rather than by a
  path: the in-memory backend keys its process-global registry by number, and the disk
  backend derives its directory from the pair.  The engine's suite owns the block topped
  at 15, so this one takes the block topped at **11** — the two can run at once, disk
  included, without either clearing the other's store out from under it.
  `VAELII_TEST_SPACE` moves it.

  A block is four numbers and `scratch-space` uses the top pair; the two below it are
  reserved rather than used, which is what lets a run pick a block without checking what
  else in it is taken.

  `VAELII_TEST_BACKEND` names the storage, spelled `<records>-<index>` (`memory`,
  `disk`, `memory-columnar`, …) exactly as it is for the engine, so the readers can be
  run against a durable store — which for an importer is the interesting half."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [is]]
            [vaelii.core :as v]
            [vaelii.foreign.corpus :as corpus]
            [vaelii.impl.kb :as kb]
            [vaelii.impl.protocols :as p]))

(def ^:private block-top
  (if-let [s (System/getenv "VAELII_TEST_SPACE")]
    (let [n (try (Long/parseLong (str/trim s))
                 (catch NumberFormatException _
                   (throw (ex-info (str "VAELII_TEST_SPACE must be a space number, got " (pr-str s))
                                   {:value s}))))]
      ;; the block is [n-3, n], and 0/1 are the default KB's space numbers
      (when-not (<= 5 n 15)
        (throw (ex-info (str "VAELII_TEST_SPACE must be between 5 and 15 — it names the top of a "
                             "four-space block, and the block must clear space numbers 0 and 1 "
                             "(the default KB).  Got " n ".")
                        {:value n})))
      n)
    11))

(def ^:private storage
  (if-let [b (some-> (System/getenv "VAELII_TEST_BACKEND") str/trim str/lower-case not-empty)]
    {:backend (keyword b)}
    {:backend :memory}))

(defn- space-opts
  "The KB opts for a space pair.  `:recover? false` — these KBs are built over spaces a
  previous run may have left populated and are cleared right after construction, so the
  unrecovered-store warning would be noise on every build."
  [rs is]
  (merge {:record-space rs :index-space is :recover? false} storage))

(def scratch-space (space-opts block-top (- block-top 1)))

(def raw-map-index?
  "True when the run's index keeps its entries as a directly `=`-comparable key→value map
  of plain Long sets — the shape the `:memory` and `:disk` *index* axes both have.  The
  dense ones do not, so a test that compares the raw index map rather than its answers
  only applies to the raw shape.  Read off `kb/backend-axes` rather than off the
  `:backend` name, so a sugar name cannot leave this saying the wrong thing."
  (contains? #{:memory :disk} (:index (kb/backend-axes storage))))

(defn test-kb "A KB on this repo's scratch pair." [] (v/open-kb scratch-space))

(defn clear-kb! [kb]
  (p/clear-records! (:records kb))
  (p/clear-index!   (:index kb)))

(defn fresh "An empty, cleared KB on the scratch pair." [] (doto (test-kb) (clear-kb!)))

;;; ── corpus scaffolding ────────────────────────────────────────────────
;;
;; Every reader in this repo writes the same corpus, so every reader's test wants the
;; same two things: somewhere to write one, and a way to read it back as data rather
;; than as text.

(defn temp-dir
  "A fresh scratch directory, deleted with its contents when `f` returns."
  [prefix f]
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory
                      prefix (into-array java.nio.file.attribute.FileAttribute [])))]
    (try (f dir)
         (finally
           (doseq [^java.io.File x (reverse (file-seq dir))] (.delete x))))))

(defn corpus-file
  "Every sentence in one file of a corpus's `kb/` directory, or nil when there is no
  such file — which is itself an answer a test may want (\"nothing landed in that
  context\")."
  [dir fname]
  (let [f (io/file dir "kb" fname)]
    (when (.exists f) (corpus/read-file-sentences f))))

(defn corpus-sentences
  "Every sentence a corpus holds, across every context file.  Order is not meaningful —
  a test asserts membership, not layout, since which file a sentence lands in is the
  translation's business and is asserted separately where it matters."
  [dir]
  (into []
        (comp (filter #(and (.isFile ^java.io.File %)
                            (str/ends-with? (.getName ^java.io.File %) ".txt")))
              (mapcat corpus/read-file-sentences))
        (file-seq (io/file dir "kb"))))

(defn corpus-report
  "A written corpus's `report.edn`."
  [dir]
  (edn/read-string (slurp (io/file dir "report.edn"))))

(defn corpus-meta
  "A written corpus's `meta.edn`."
  [dir]
  (edn/read-string (slurp (io/file dir "meta.edn"))))

(defn sentex-ids        [kb] (set (p/sentex-ids        (:records kb))))
(defn justification-ids [kb] (set (p/justification-ids (:records kb))))
(defn premise-ids       [kb] (set (p/premise-ids       (:records kb))))

(defn content-count [kb]
  {:sentexes       (count (p/sentex-ids        (:records kb)))
   :justifications (count (p/justification-ids (:records kb)))})

(defn cleared-kb*
  "Functional core of `with-cleared-kb`."
  [build-fn body]
  (let [kb (build-fn)]
    (try (body kb)
         (finally
           (clear-kb! kb)
           (is (= {:sentexes 0 :justifications 0} (content-count kb))
               "store not empty after clear teardown")))))

(defmacro with-cleared-kb
  "For a test that intentionally mutates the store and then hands it to a second KB over
  the same spaces — which is what importing a dump is: build, run the body, then FLUSH and
  assert the store is empty.  Flushing is the honest teardown when the store itself is
  under test.

    (with-cleared-kb [kb tu/fresh] …)"
  [[sym build-fn] & body]
  `(cleared-kb* ~build-fn (fn [~sym] ~@body)))
