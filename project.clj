;; The major version of the JDK evaluating this file, which is the one leiningen
;; launches the project JVM on: `:jvm-opts` below names a flag 21 refuses.
(def jdk-major
  (Integer/parseInt
   (second (re-find #"^(?:1\.)?(\d+)" (System/getProperty "java.specification.version")))))

(defproject com.vaelii/vaelii-foreign "0.28.1-SNAPSHOT"
  :description "Foreign-format readers for vaelii — OpenCyc, RDF/OWL, WordNet, OBO and
                ATOMIC, each translated into one corpus format, discovered through
                vaelii's plugin extension point."
  :url "https://github.com/vaelii/vaelii-foreign"
  ;; The permissive layer around the SSPL core.  This artifact reads formats whose own
  ;; reference implementations Cycorp published under Apache-2.0, and a corpus converted
  ;; from one of them carries Apache-2.0 with it either way — licenses/THIRD-PARTY.md.
  :license {:name "Apache-2.0"
            :url "https://www.apache.org/licenses/LICENSE-2.0"}
  ;; Generated into the POM. A Clojars page without it offers a reader no route
  ;; from the artifact back to the source it was built from.
  :scm {:name "git" :url "https://github.com/vaelii/vaelii-foreign"}
  ;; Same as the engine's, and deliberately identical: credentials come from the
  ;; environment (CLOJARS_USERNAME plus a Clojars DEPLOY TOKEN as CLOJARS_PASSWORD),
  ;; never from this file, and `:sign-releases false` because the default is to
  ;; attempt a GPG signature Clojars does not require and fail without a key.
  ;;
  ;; This artifact deploys SECOND. It resolves against the published engine, so
  ;; publishing it first puts a coordinate on Clojars whose own dependency does
  ;; not resolve, and a Clojars release is immutable.
  :deploy-repositories [["clojars" {:url "https://repo.clojars.org/"
                                    :username :env/clojars_username
                                    :password :env/clojars_password
                                    :sign-releases false}]]
  ;; A released vaelii resolves from Clojars.  A snapshot one does not, and comes from
  ;; `lein install` in ../vaelii instead (README, "Setup").  The readers reach into
  ;; `vaelii.impl.*`, which vaelii is free to change — that cost is the plugin's to
  ;; carry, and is the reason a bridge lives out here rather than in the engine.
  :dependencies [[org.clojure/clojure "1.12.6"]
                 [com.vaelii/vaelii "0.28.0"]
                 ;; Arrives transitively through vaelii and is required directly by code
                 ;; here — trove carries the conversion's progress log — so naming it
                 ;; makes it a promise rather than an accident of somebody else's
                 ;; dependency graph.
                 [com.taoensso/trove "1.2.0"]]
  ;; The corpus converter's CLI: `lein convert convert|load|report|diff|formats`.
  :main ^:skip-aot vaelii.foreign.convert
  ;; `^:suite` marks the tests that convert a real cached ontology or walk a W3C suite,
  ;; so `lein test :suite` runs those alone and `lein test :offline` skips them.
  :test-selectors {:suite   :suite
                   :offline (complement :suite)}
  :target-path "target/%s"
  ;; Declaring `:jvm-opts` replaces leiningen's `^:displace` `:base` vector, which
  ;; drops the C1-only cap `:base` copies from a LEIN_JVM_OPTS naming "Tiered", and
  ;; restores `:base`'s `-XX:-OmitStackTraceInFastThrow` so a test failure names its
  ;; site. The two `--` flags silence the JDK 24+ warnings for JNA's native loads and
  ;; aircompressor's (nippy LZ4) `sun.misc.Unsafe` calls; the Unsafe flag exists from
  ;; 23, and 21 refuses to boot with it. Core's project.clj has the detail.
  :jvm-opts ~(cond-> ["-XX:-OmitStackTraceInFastThrow" "--enable-native-access=ALL-UNNAMED"]
               (<= 23 jdk-major) (conj "--sun-misc-unsafe-memory-access=allow"))
  ;; Same as the engine's: an unhinted interop call is a build-time failure rather than
  ;; a reflective lookup paid at runtime.
  :global-vars {*warn-on-reflection* true}
  ;; Static-analysis checks ported from the engine, trimmed to what applies out here
  ;; (the glossary / doc-drift / unused-publics checks are coupled to core's KB and
  ;; baselines and are not carried).
  ;;   lein lint    — versions + kondo + shellcheck + cljfmt + reflect + prose
  ;;                  via scripts/lint.sh, one ✓/✗ line per check, runs all (not
  ;;                  fail-fast).  VERBOSE=1 dumps each check's full output.
  ;;   lein fix     — the only auto-repair: cljfmt rewrites in place.  kondo and
  ;;                  reflect are check-only; a red run is manual.
  ;;   lein gate    — lint, then (only if green) the suite.
  ;; The lint-* sub-aliases stay for granular one-off runs.  Needs clj-kondo on PATH;
  ;; reflect shells out to lein's built-in `check` (AOT compile).
  :aliases {"lint-kondo"      ["shell" "clj-kondo" "--lint" "src" "test"]
            ;; Formatting diff (read-only) in the +cljfmt profile, so the plugin's deps
            ;; stay off the kondo / reflect subtasks.  `lein fix` is the rewrite half.
            "lint-cljfmt"     ["cljfmt" "check"]
            ;; Reflection / auto-boxing ratchet: AOT-compiles src under the top-level
            ;; *warn-on-reflection* and fails on any warning from our code.  Slowest of
            ;; the three (it compiles), so `lint` runs it last.
            "lint-reflect"    ["shell" "bash" "scripts/check-reflection.sh"]
            ;; the prose budget: metaphor and aphorism against scripts/prose-baseline.txt.
            ;; `lein lint-prose -- --update` lowers a stale budget; it never raises one
            "lint-prose"      ["shell" "python3" "scripts/check-prose.py"]
            "lint"            ["shell" "bash" "scripts/lint.sh"]
            "fix"             ["cljfmt" "fix"]
            "gate"            ["do" ["lint"] ["test"]]
            ;; cljfmt's :indents REPLACE the built-in defaults, so our macro
            ;; body-indents (cljfmt-indents.edn) go in :extra-indents.
            "cljfmt"          ["with-profile" "+cljfmt" "cljfmt"]
            ;; the converter, with room to hold a corpus (see the :convert profile)
            "convert"         ["with-profile" "+convert" "run"]}
  ;; `shell` task: lets the lint aliases run scripts/*.sh from lein.
  :plugins [[lein-shell "0.5.0"]]
  ;; The lein launcher exports CLASSPATH (its own jar), so every nested `lein` under a
  ;; `lein shell` alias prints "You have $CLASSPATH set"; nil drops it from the child.
  :shell {:env {"CLASSPATH" nil}}
  :profiles {:uberjar {:aot :all}
             :dev {:dependencies [[nrepl "1.7.0"]]}
             ;; cljfmt isolated in its own profile so the plugin's deps stay off the
             ;; normal repl/test/build classpath.  Macro body-indents come from
             ;; cljfmt-indents.edn (hand-maintained here — some of the macros it covers
             ;; are defined in core, not in this tree).
             :cljfmt {:plugins [[dev.weavejester/lein-cljfmt "0.16.5"]]
                      :cljfmt {:indentation?                    true
                               :indent-line-comments?           true
                               :remove-surrounding-whitespace?  true
                               :remove-trailing-whitespace?     true
                               :insert-missing-whitespace?      true
                               :remove-consecutive-blank-lines? true
                               :sort-ns-references?             true
                               :extra-indents ~(let [f (java.io.File. "cljfmt-indents.edn")]
                                                 (if (.exists f) (read-string (slurp f)) {}))}}
             ;; Reflection is flipped off here so the test tree doesn't spam `lein test`
             ;; (reflect lints src only, via `lein check`, which keeps the flag on).
             :test {:global-vars {*warn-on-reflection* false}}
             ;; A converted OpenCyc corpus is ~2M sentences (ATOMIC 1.3M, a Wikidata
             ;; dump far more) and the index is in RAM in every backend, so a full
             ;; `load-dir!` wants a heap and a disk KB.  Reflection is flipped off for
             ;; the same reason it is off for tests: this profile runs the CLI at a
             ;; person, and core's own warnings are not theirs to read.  `lein check`
             ;; keeps the flag on, so the reflect check still sees src.
             :convert {:jvm-opts ["-Xmx12g"]
                       :global-vars {*warn-on-reflection* false}}}
  :repl-options {:init-ns vaelii.foreign.convert})
