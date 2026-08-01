(ns vaelii.foreign.wordnet
  "Read a WordNet database — Princeton's own `dict/` directory — and translate it into a
  vaelii corpus.

  WordNet is the odd one out here: it is a **lexical** database, organized around which
  words mean the same thing, and only incidentally an ontology.  What makes it worth
  importing anyway is that its central relation is subsumption.  A synset is a set of
  synonymous words, `@` points from a synset to a more general one, and 82,000 noun
  synsets wired that way are a usable upper taxonomy that nobody has to build.

  ## The format

  `data.noun`, `data.verb`, `data.adj` and `data.adv` — the WNDB format, which has not
  changed since 1993 and which Open English WordNet still publishes alongside its XML:

      02084071 05 n 01 dog 0 019 @ 02083346 n 0000 ~ 02084732 n 0000 … | a member of the genus Canis

      offset  lexfile  pos  w_cnt(hex)  word lex_id…  p_cnt  pointers…  | gloss

  A pointer is `symbol offset pos source/target`.  This reader takes the data files
  alone: they are self-contained, and the index files exist to answer \"which senses does
  this word have\", which is a lookup rather than a fact.

  ## What comes across

  | WordNet                        | vaelii                                             |
  |--------------------------------|----------------------------------------------------|
  | a synset                       | a **type** — `dog_n`, spelled from its first word  |
  | `@`  hypernym                  | `(genl dog_n canine_n)`                            |
  | `@i` instance hypernym         | the synset is an **individual**: `(composer_n Bach)` |
  | `&`  similar to (satellite)    | `(genl damp_a wet_a)`                              |
  | `*`  entailment                | a **defeasible rule** — see below                  |
  | `%m` `%s` `%p` meronyms        | `(memberMeronym …)`, `(substanceMeronym …)`, `(partMeronym …)` |
  | `!` `=` `>` `^` `$` `<` `\\\\` `+` | `(antonym …)`, `(attribute …)`, `(causes …)`, …    |
  | `;c` `;r` `;u` domains         | `(topicDomain …)`, `(regionDomain …)`, `(usageDomain …)` |
  | each word of a synset          | `(wordForm dog_n \"dog\")`                           |
  | the gloss                      | `(comment dog_n \"a member of the genus Canis\")`    |

  **`~` and the holonyms are not dropped for being uninteresting** — they are the exact
  inverses of `@` and the meronyms, stored in both directions because WordNet is a
  lookup structure.  Importing both would double the corpus to say each thing twice.

  **Entailment becomes a rule.**  `snore *> sleep` says that snoring entails sleeping,
  and a verb synset is a type of event, so this reads as `(implies (snore_v ?x)
  (sleep_v ?x))` over events — the one relation in WordNet with a genuine inferential
  reading, and it is why importing this into a rule engine is worth doing at all.  It
  lands **defeasible** (`set/defaultRule`), because it is a generalization about usage
  rather than a definition, and it fires on nothing WordNet itself contains: it is
  vocabulary for somebody else's events.  `:entailment-rules? false` writes plain
  `(entails a b)` facts instead.

  ## Naming and contexts

  A synset is named from its first word plus its part of speech — `dog_n`, `run_v` —
  which collides constantly, because that is what a word having several senses *is*.
  Collisions take a numeric suffix in offset order, so `dog_n` and `dog_n_2` are stable
  across runs but are **not** WordNet's own sense numbers, which live in the index files
  this reader does not read.  `(wnOffset dog_n \"n02084071\")` is written for every
  synset, and is the identifier to join on.

  Each part of speech is its own context under `WordNetContext`, so a profile can load
  the noun taxonomy without the other three."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [taoensso.trove :as trove]
            [vaelii.foreign.corpus :as corpus]
            [vaelii.foreign.term :as term]))

;;; ── the format ────────────────────────────────────────────────────────

(def pos-files
  "The four data files, and the part-of-speech letter each holds.  An adjective
  satellite (`s`) lives in `data.adj` and is pointed at as an `a`, so both letters key
  the same file — which is what makes a pointer's target resolvable at all."
  {"noun" "n" "verb" "v" "adj" "a" "adv" "r"})

(def ^:private pos-file
  {"n" "noun" "v" "verb" "a" "adj" "s" "adj" "r" "adv"})

(def ^:private pos-contexts
  {"noun" 'WordNetNounContext "verb" 'WordNetVerbContext
   "adj"  'WordNetAdjectiveContext "adv" 'WordNetAdverbContext})

(def pointer-readings
  "The pointer symbols this reader has a reading for, and the vaelii predicate each
  becomes.  `:genl` and `:instance` are handled structurally rather than as predicates;
  `:inverse` names a pointer that is the stored inverse of one already taken."
  {"@"  :genl        "@i" :instance
   "~"  :inverse     "~i" :inverse
   "&"  :genl                            ; an adjective satellite is a kind of its head
   "*"  :entails     ">"  'causes
   "%m" 'memberMeronym  "%s" 'substanceMeronym  "%p" 'partMeronym
   "#m" :inverse        "#s" :inverse           "#p" :inverse
   "!"  'antonym     "="  'attribute
   "^"  'alsoSee     "$"  'verbGroup
   "<"  'participleOf "\\" 'pertainsTo
   "+"  'derivationallyRelated
   ";c" 'topicDomain  ";r" 'regionDomain  ";u" 'usageDomain
   "-c" :inverse      "-r" :inverse       "-u" :inverse})

(defn- header-line?
  "A WNDB data file opens with a licence header, every line of which starts with two
  spaces — the one thing that is never true of a record."
  [^String line]
  (or (str/blank? line) (str/starts-with? line "  ")))

(defn- clean-word
  "A WordNet word as text: underscores are spaces, and a trailing syntactic marker —
  `(a)` for a predicate-position adjective and its siblings — is notation about where
  the word may stand, not part of it."
  ^String [^String w]
  (-> w (str/replace #"\([a-z]+\)$" "") (str/replace "_" " ")))

(defn parse-line
  "One `data.<pos>` record as `{:offset :pos :words :pointers :gloss}`.  `:pointers` are
  `[symbol target-key]` pairs, where a target key is `[file offset]` — the same key a
  synset is stored under, so a pointer resolves without knowing which file it crossed
  into.

  nil for a header line, and nil for a record whose fields do not line up — a `w_cnt`
  that overruns them, a count that is not a number.  `with-synsets` is what tells those
  two nils apart and counts the second, so a record this could not read is a drop rather
  than a synset that quietly never existed."
  [^String line]
  (when-not (header-line? line)
    (let [[body gloss] (str/split line #"\s*\|\s*" 2)
          f (str/split (str/trim body) #"\s+")]
      (when (>= (count f) 4)
        (try
          (let [offset (nth f 0)
                pos    (nth f 2)
                w-cnt  (Integer/parseInt (nth f 3) 16)
                ;; each word is a `word lex_id` pair, so the words end at 4 + 2*w_cnt
                words  (mapv #(clean-word (nth f (+ 4 (* 2 %)))) (range w-cnt))
                pi     (+ 4 (* 2 w-cnt))
                p-cnt  (Integer/parseInt (nth f pi))
                ptrs   (mapv (fn [i]
                               (let [b (+ pi 1 (* 4 i))]
                                 [(nth f b)
                                  [(pos-file (nth f (+ b 2))) (nth f (+ b 1))]]))
                             (range p-cnt))]
            {:offset offset :pos pos :words words :pointers ptrs
             :gloss (some-> gloss str/trim)})
          ;; the two ways a record's own counts can be wrong: `w_cnt` or `p_cnt` running
          ;; off the end of the fields, and either of them not being a number at all.
          ;; A record that states nothing must not cost the file — and must not go
          ;; unmentioned either, which is `with-synsets`' half
          (catch IndexOutOfBoundsException _ nil)
          (catch NumberFormatException _ nil))))))

(defn with-synsets
  "Call `(f synsets)` on a lazy seq of every synset in the WordNet directory (or single
  data file) at `path`, each carrying its `:file`.  The seq is only valid inside `f`.

  A line that is neither the licence header nor a record `parse-line` could read travels
  as `{:malformed line}` rather than being dropped here: a refusal is counted, not
  swallowed, and pass 2 is where the count can be kept.

  Files are opened one at a time and closed after: the seq is lazy, so the callback is
  invoked once per file and the results concatenated."
  [path f]
  (let [^java.io.File p (io/file path)
        files (if (.isDirectory p)
                (keep (fn [[fname _]]
                        (let [x (io/file p (str "data." fname))]
                          (when (.exists x) [fname x])))
                      pos-files)
                [[(or (some (fn [[fname _]]
                              (when (str/ends-with? (.getName p) (str "data." fname)) fname))
                            pos-files)
                      "noun")
                  p]])]
    (f (mapcat (fn [[fname ^java.io.File file]]
                 (with-open [r (io/reader file)]
                   ;; forced per file: the reader closes when this file is done, and a
                   ;; lazy seq escaping it would read from a closed stream
                   (into [] (comp (keep (fn [line]
                                          (or (parse-line line)
                                              (when-not (header-line? line)
                                                {:malformed line}))))
                                  (map #(assoc % :file fname)))
                         (line-seq r))))
               files))))

;;; ── pass 1: names ─────────────────────────────────────────────────────

(defn classify
  "Pass 1.  `{:entries [[key role base] …]}` — one entry per synset, in file and offset
  order.

  A synset with an **instance hypernym** is an individual, not a type: WordNet marks
  Bach as an instance of composer rather than a kind of one, and that distinction is
  exactly vaelii's between `(genl a b)` and `(b A)`.  Everything else is a type."
  [synsets]
  {:entries
   (vec (for [{:keys [file offset words pointers malformed]} synsets
              ;; a record `parse-line` refused names nothing; `convert!` counts it
              :when (not malformed)]
          (let [instance? (some (fn [[sym _]] (= "@i" sym)) pointers)
                word      (or (first words) offset)]
            [[file offset]
             (if instance? :individual :type)
             ;; the part of speech is what tells `dog_n` from `dog_v`, and an instance
             ;; has no sibling in another part of speech to be told from — `Bach` is a
             ;; better name than `BachN` and nothing collides with it
             (if instance? word (str word " " (pos-files file)))])))})

(defn name-table
  "The synset -> vaelii term map.  A type is spelled `dog_n` and an individual
  `JohannSebastianBach`, and a collision — which is what a word with several senses is —
  takes a numeric suffix in the file-and-offset order the entries arrive in, so the same
  database always produces the same names."
  [{:keys [entries]}]
  (term/name-table (for [[k role base] entries] [k role (term/abbreviate base)])))

;;; ── pass 2: translate ─────────────────────────────────────────────────

(defn translate
  "The sentences one synset becomes: `{:context C :sentences [[strength sentence] …]
  :drops {reason n}}`.

  Each sentence carries its own strength: the taxonomy is definitional and lands `:monotonic`,
  while a gloss, a word form and the lexical relations are observations about usage and
  land `:default`."
  [{:keys [file offset words pointers gloss] :as _synset} names opts]
  (let [self (:term (names [file offset]))
        out  (volatile! [])
        drops (volatile! {})
        add!  (fn [strength s] (vswap! out conj [strength s]))
        drop! (fn [why] (vswap! drops update why (fnil inc 0)))
        individual? (= :individual (:role (names [file offset])))]
    (when self
      (add! :default (list 'wnOffset self (str (pos-files file) offset)))
      (when gloss (add! :default (list 'comment self gloss)))
      (when (:word-forms? opts true)
        (doseq [w words] (add! :default (list 'wordForm self w))))

      (doseq [[sym target] pointers]
        (let [x (:term (names target))
              reading (pointer-readings sym)]
          (cond
            (nil? reading)  (drop! :unknown-pointer)
            (= :inverse reading) (drop! :inverse-pointer)
            (nil? x)        (drop! :unresolved-pointer)

            ;; a hypernym is subsumption — unless this synset is an instance, in which
            ;; case the same pointer is a type membership
            (= :genl reading)
            (if individual?
              (add! :default (list x self))
              (add! :monotonic (list 'genl self x)))

            (= :instance reading) (add! :default (list x self))

            (= :entails reading)
            (if (:entailment-rules? opts true)
              ;; over events: a verb synset is a type of event, and entailment says
              ;; every event of this kind is also one of that kind.  Defeasible, because
              ;; it generalizes about usage rather than defining anything.
              (add! :default (list 'set/defaultRule
                                   (list 'implies (list self '?e) (list x '?e))))
              (add! :default (list 'entails self x)))

            :else (add! :default (list reading self x))))))
    {:context (pos-contexts file 'WordNetContext)
     :sentences @out :drops @drops}))

;;; ── converting ────────────────────────────────────────────────────────

(def drop-kinds
  "What each of this reader's drop reasons **is** — see `corpus/drop-kinds`.  A reason
  missing here counts as `:unread`.

  `:inverse-pointer` is by far the largest and is `:restated` outright: WordNet is a
  lookup structure and stores `~` beside `@`, so importing both would double the corpus
  to say each thing twice.  It is a drop only in the sense that a line of the file
  produced no sentence.

  `:malformed-record` is deliberately not in here: a record whose own counts do not line
  up is content this reader could not carry, and `:unread` is what that is."
  '{:inverse-pointer :restated})

(def drop-flags
  "The convert option that keeps each `:filtered` drop — see `cyc/drop-flags` for the
  contract and `plugin-test` for what enforces it.

  Empty, and it should be: this reader's one drop reason is `:restated`, not
  `:filtered`.  `~` is `@` stored backwards and importing both would double the corpus
  to say each thing twice, which is not a policy anybody would want reversed."
  {})

(defn convert!
  "Convert the WordNet database at `path` — a `dict/` directory, or one `data.<pos>`
  file — into a vaelii corpus under `out-dir`.  Two passes: the first names every
  synset, the second writes the sentences.  Returns the report map.

  Options: `:entailment-rules?` writes verb entailment as a defeasible rule (on — see
  the namespace docstring); `:word-forms?` writes a `wordForm` fact per word of a synset
  (on); `:limit` reads only the first n synsets."
  ([path out-dir] (convert! path out-dir {}))
  ([path out-dir opts]
   (let [limit (:limit opts)
         cap   (fn [xs] (if limit (take limit xs) xs))]
     (trove/log! {:level :info :id ::classify :msg "pass 1: naming synsets"})
     (let [evidence (with-synsets path #(classify (cap %)))
           names    (name-table evidence)]
       (trove/log! {:level :info :id ::translate
                    :msg (str "pass 2: translating (" (count names) " synsets)")})
       (let [report
             (corpus/write!
              out-dir
              {:format       :vaelii-wordnet-corpus/v1
               :source       path
               :options      opts
               :names        names
               :root-context 'WordNetContext
               :notice
               (str "WordNet(R) is a lexical database developed at Princeton University.\n"
                    "Princeton WordNet 3.0 is distributed under the WordNet License, a\n"
                    "permissive BSD-style licence requiring that its copyright notice and\n"
                    "this paragraph appear in all copies -- including, by its own terms,\n"
                    "supporting documentation.  Open English WordNet, which ships the same\n"
                    "WNDB layout, is CC-BY 4.0.  Check which one this corpus came from.\n"
                    "\nWordNet 3.0 Copyright 2006 by Princeton University.  All rights\n"
                    "reserved.  WordNet is a registered trademark of Princeton University.\n")}
              (fn [emit!]
                (let [counts  (atom {:synsets 0})
                      dropped (atom {})]
                  (with-synsets
                    path
                    (fn [ss]
                      (doseq [record (cap ss)]
                        (if (:malformed record)
                          ;; a record whose fields do not line up: counted here rather
                          ;; than lost in the reader, and `:unread` for want of an entry
                          ;; in `drop-kinds`, which is what it is
                          (swap! dropped update :malformed-record (fnil inc 0))
                          (do
                            (swap! counts update :synsets inc)
                            (let [{:keys [context sentences drops]}
                                  (translate record names opts)]
                              (swap! dropped #(merge-with + % drops))
                              (doseq [[strength group] (group-by first sentences)]
                                (emit! context strength (mapv second group)))))))))
                  {:source       (str path)
                   :synsets      (:synsets @counts)
                   :dropped      (reduce + (vals @dropped))
                   :drop-reasons (into (sorted-map) @dropped)
                   :drop-kinds   drop-kinds
                   :terms        (frequencies (map :role (vals names)))})))]
         (trove/log! {:level :info :id ::converted
                      :msg (str "converted " (:synsets report) " synsets -> "
                                (:sentences report) " sentences")})
         report)))))

;;; ── loading ───────────────────────────────────────────────────────────

(def profiles
  "Named subsets.  `:taxonomy` is the reason most people want WordNet: the `genl`
  hierarchy and the instance memberships, without the lexical layer that is nine tenths
  of the sentences.  `:nouns` keeps that layer but only for nouns."
  {:full     {}
   :ontology {:drop-predicates '#{wordForm wnOffset derivationallyRelated}}
   :taxonomy {:drop-predicates '#{wordForm wnOffset comment derivationallyRelated
                                  antonym alsoSee verbGroup participleOf pertainsTo
                                  topicDomain regionDomain usageDomain attribute}}
   :nouns    {:keep-contexts #{'WordNetNounContext 'WordNetContext}}})

(defn load-dir!
  "Load the corpus at `dir` into `kb` — `vaelii.foreign.corpus/load-dir!` with this
  format's `profiles`."
  ([kb dir] (load-dir! kb dir {}))
  ([kb dir opts] (corpus/load-dir! kb dir profiles opts)))

(def reader
  "This format's reader, as the seam (`vaelii.impl.foreign`) hands it out."
  {:name       "WordNet database"
   :load-dir!  load-dir!
   :convert!   convert!
   :profiles   profiles
   :drop-kinds drop-kinds
   :drop-flags drop-flags})
