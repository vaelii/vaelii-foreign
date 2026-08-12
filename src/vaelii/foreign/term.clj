(ns vaelii.foreign.term
  "Spell a foreign name as a vaelii term.

  Every ontology this repo reads names its terms in its own alphabet — Cyc writes
  `#$Agent-PartiallyTangible`, RDF an IRI, OBO `GO:0008150`, WordNet a lemma and a byte
  offset, ATOMIC an English sentence — and vaelii names a term with a symbol whose
  *shape encodes its role* (`vaelii.impl.naming`):

      type         snake_case                 dog, physical_object
      predicate    camelCase                  parentOf, argIsa
      individual   CapitalCamelCase           Rover, Einstein
      function     CapitalCamelCase           GroupFn
      context      Cx + CapitalCamelCase   CxUniverse

  So a translation cannot rename term-by-term as it goes: it has to know a term's
  **role** first, and every reader here earns that the same way — a classifying pass
  over the source, then a naming pass with the classification in hand.  What differs
  between them is only what counts as evidence, which is each reader's business; the
  spelling and the collision handling are this namespace's, so five readers cannot
  disagree about what a legal predicate name looks like.

  Two properties matter more than they look:

  * **Renaming is not injective, so collisions are the normal case.**  `GO:0008150`
    and `GO:0008151` may both be named `biological_process`, `owl:Class` and
    `rdfs:Class` both `class`.  `name-table` resolves them with the smallest numeric
    suffix that is free, in a **sorted** pass, so the same source always produces the
    same names whatever order it was read in — a corpus you can diff between runs.

  * **A name becomes a file name.**  A context file is `Cx<C>.txt`, and a
    filesystem bounds a name at 255 bytes, while a computed Cyc microtheory or an
    ATOMIC event runs far longer.  `abbreviate` truncates and appends a hash of the
    whole, in letters rather than digits so `spell` cannot break the hash into a word
    of its own."
  (:require [clojure.string :as str]))

;;; ── words ─────────────────────────────────────────────────────────────

(def ^:private word-pattern
  ;; an acronym run, a Capitalized word, or a lowercase/digit run
  #"[A-Z]+(?![a-z])|[A-Z][a-z0-9]*|[a-z0-9]+")

(defn words
  "The word parts of a foreign name.  Splits on case boundaries as well as on the
  punctuation a source may allow and vaelii's symbols may not (`Agent-PartiallyTangible`,
  `owl:Class`, `has_part`, `mitochondrion inheritance`)."
  [^String s]
  (re-seq word-pattern s))

(defn- capitalize-head
  "Uppercase the first character, leaving the rest alone — so an acronym part keeps its
  shape (`US` stays `US`, not `Us`)."
  [^String w]
  (if (empty? w) w (str (Character/toUpperCase (.charAt w 0)) (subs w 1))))

(defn- lowercase-head
  [^String w]
  (if (empty? w) w (str (Character/toLowerCase (.charAt w 0)) (subs w 1))))

(defn spell
  "The vaelii spelling of foreign name `nm` in `role`, before collision resolution.
  Each role's shape is the naming invariant it must satisfy.  A name that would come out
  empty or leading-digit is prefixed rather than mangled further, because a mangled name
  is one nobody can look up in the source and a prefixed one still reads."
  [role ^String nm]
  (let [ws (words nm)]
    (case role
      :type       (let [s (str/lower-case (str/join "_" ws))]
                    (if (re-matches #"[a-z][a-z0-9_]*" s) s (str "t_" s)))
      :predicate  (let [s (lowercase-head (str/join "" (map capitalize-head ws)))]
                    (if (re-matches #"[a-z][a-zA-Z0-9]*" s) s (str "p" (capitalize-head s))))
      :context    (let [ws   (if (and (> (count ws) 1) (= "Mt" (last ws))) (butlast ws) ws)
                        body (str/join "" (map capitalize-head ws))
                        s    (str "Cx" body)]
                    (if (re-matches #"Cx[A-Z][A-Za-z0-9]*" s) s (str "CxMt" body)))
      ;; :individual and :function share a shape; an individual that came out matching
      ;; the Cx… context shape would read as a context, so it is pushed off that
      ;; spelling.
      (let [s (str/join "" (map capitalize-head ws))
            s (if (re-matches #"[A-Z][A-Za-z0-9]*" s) s (str "X" s))]
        (if (re-matches #"Cx[A-Z][A-Za-z0-9]*" s) (str "X" s) s)))))

;;; ── collisions ────────────────────────────────────────────────────────

(defn- suffixed
  "`base` with disambiguating suffix `n`, still spelled as `role` requires: a type keeps
  its snake_case underscore before the digit, and every other role's shape — a **context**
  included, since its `Cx` marker sits at the front rather than the end — survives a bare
  append."
  [role ^String base n]
  (case role
    :type (str base "_" n)
    (str base n)))

(defn disambiguate
  "`base` if free in `taken`, else `base` with the smallest numeric suffix that is."
  [taken role base]
  (if-not (contains? taken base)
    base
    (first (remove #(contains? taken %)
                   (map #(suffixed role base %) (iterate inc 2))))))

(def name-limit
  "How long a minted name may run before it is abbreviated.  A context becomes a file
  name, which a filesystem bounds at 255 bytes, and a minted name — a nested Cyc
  microtheory, an ATOMIC event sentence — has no bound of its own."
  56)

(defn abbreviate
  "`s` if it is short enough, else its head plus a hash of the whole, so a long name
  stays readable at a glance and still cannot collide by truncation alone.  The hash is
  spelled in letters: the name goes through `spell`, which would break a digit run into
  a word of its own."
  [^String s]
  (if (<= (count s) name-limit)
    s
    (let [h (bit-and (hash s) 0x7fffffff)]
      (str (subs s 0 name-limit)
           (apply str (map #(char (+ (int \a) (mod (quot h (long (Math/pow 26 %))) 26)))
                           (range 7)))))))

;;; ── the table ─────────────────────────────────────────────────────────

(defn name-table
  "The foreign term -> vaelii term map, built from `entries` — `[key role base]`
  triples, where `base` is the foreign name to spell and `key` is whatever the reader
  looks a term up by (a symbol, an IRI string, a whole form).

  Entries are consumed **in the order given**, and the order is what decides which of
  two colliding names keeps the unsuffixed spelling.  So a caller sorts first: a
  translation whose names depend on the order the source happened to stream in is one
  whose output cannot be diffed against the previous run.

  Returns `{key {:term symbol :role role}}`."
  [entries]
  (second
   (reduce (fn [[taken out] [key role base]]
             (let [nm (disambiguate taken role (spell role base))]
               [(conj taken nm) (assoc out key {:term (symbol nm) :role role})]))
           [#{} {}]
           entries)))

(defn resolve-role
  "The single role each key is given, resolving `evidence` — a map of `evidence-key ->
  #{key …}` — by `precedence`, a seq of `[role evidence-key]` in decreasing authority.
  The first rule that names a key decides it.

  Every reader classifies this way because the evidence is always partial and always
  contradictory: a source states a term's role in some places, implies it by position in
  others, and leaves the rest to be whatever is left over.  Precedence is where a reader
  says which of those it trusts, and it is the one place to look to find out."
  [precedence evidence]
  (persistent!
   (reduce (fn [acc [role k]]
             (reduce (fn [a c] (if (contains? a c) a (assoc! a c role)))
                     acc
                     (get evidence k)))
           (transient {})
           precedence)))
