(ns vaelii.foreign.turtle
  "Read an RDF graph — N-Triples, N-Quads or Turtle — as data.

  This is the *lexer*; `vaelii.foreign.rdf` is the translation, and the split is the
  same one `cycl` and `cyc` make.  Nothing here knows what `rdfs:subClassOf` means.

  **Three syntaxes, one reader.**  They are the same grammar at three levels of sugar,
  so separating them would mean three parsers that disagree about IRIs:

  | syntax     | what it adds                                                     |
  |------------|------------------------------------------------------------------|
  | N-Triples  | `<s> <p> <o> .` — one statement per line, no abbreviation of any kind |
  | N-Quads    | a fourth term, the **graph** the statement belongs to            |
  | Turtle     | `@prefix`, `@base`, `a`, `;`, `,`, `[…]`, `(…)`, numeric and boolean literals |

  A bulk dump ships as N-Triples or N-Quads (Wikidata, YAGO, DBpedia) because a
  line-per-statement file streams and shards; a hand-written ontology ships as Turtle
  (schema.org, BFO, most of the OBO Foundry's OWL) because a person has to read it.  A
  reader that could only take the first would be a reader you cannot point at an
  ontology, and one that could only take the second would not scale to a real dump.

  **How a term comes back.**  As in `cycl`, the *namespace* of a symbol is what tells
  two kinds apart that would otherwise collide:

  | RDF                        | Clojure                        |
  |----------------------------|--------------------------------|
  | `<http://example.org/Dog>` | `iri/http://example.org/Dog`   |
  | `ex:Dog` (prefix expanded) | `iri/http://example.org/Dog`   |
  | `_:b0`                     | `bnode/b0`                     |
  | `\"text\"`                   | `\"text\"`                       |
  | `42`, `1.5`, `true`        | `42`, `1.5`, `true`            |
  | `\"dog\"@en`                 | `{:lex \"dog\" :lang \"en\"}`      |
  | `\"2020-01-01\"^^xsd:date`   | `{:lex \"2020-01-01\" :datatype iri/…}` |

  A literal is a **plain Clojure value** whenever it has nothing else to say — a
  language tag or a datatype vaelii has no native reading of is what makes it a map
  instead.  That keeps the common case cheap and the tagged case honest: a Wikidata
  dump's labels differ only by `@lang`, and a reader that dropped the tag would make
  fifty translations of one label indistinguishable.

  **Blank nodes are expanded, not deferred.**  `[ :p :o ]` and `( a b c )` are
  abbreviations for triples about a fresh blank node, so the reader emits those triples
  and hands back the node — which is why one statement can yield many, and why the seq
  is over *triples* rather than over lines.

  The reader is a lazy seq over a `Reader`, so a multi-gigabyte dump streams in constant
  memory."
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.io PushbackReader Reader)))

;;; ── terms ─────────────────────────────────────────────────────────────

(defn iri
  "The Clojure symbol an IRI reads as."
  [^String s] (symbol "iri" s))

(defn iri?
  "Is `x` an IRI — the `iri/…` symbol form?"
  [x] (and (symbol? x) (= "iri" (namespace x))))

(defn bnode?
  "Is `x` a blank node?  One written `_:b` in the source, or one this reader minted to
  hold a `[…]` or `(…)` abbreviation together."
  [x] (and (symbol? x) (= "bnode" (namespace x))))

(defn iri-str
  "The IRI text of an `iri/…` symbol."
  ^String [x] (name x))

(defn tagged?
  "Is `x` a literal carrying a language tag or a datatype — the map form?"
  [x] (and (map? x) (contains? x :lex)))

(defn lex
  "The lexical form of any literal, tagged or plain."
  ^String [x] (if (tagged? x) (:lex x) (str x)))

(def xsd
  "The XML Schema namespace, which every datatype RDF has a native reading of lives in.
  Public because `vaelii.foreign.rdf` tests a range against it — an `rdfs:range` of
  `xsd:string` constrains a value rather than naming a type of term."
  "http://www.w3.org/2001/XMLSchema#")

(def rdf-ns
  "The RDF namespace.  Public because the syntax's own vocabulary is spelled from it in
  three places — here, in `vaelii.foreign.rdfxml`, and in `vaelii.foreign.rdf` — and two
  spellings of `rdf:type` would be two terms."
  "http://www.w3.org/1999/02/22-rdf-syntax-ns#")

(def ^:private rdf-first (iri (str rdf-ns "first")))
(def ^:private rdf-rest  (iri (str rdf-ns "rest")))
(def ^:private rdf-nil   (iri (str rdf-ns "nil")))
(def ^:private rdf-type  (iri (str rdf-ns "type")))

(def ^:private native-datatypes
  "The XSD datatypes read as a Clojure value rather than kept as a tagged map.  Every
  one of them is a value vaelii stores natively, so keeping the tag would only make two
  spellings of the same number compare unequal."
  {"string" :string "normalizedString" :string "token" :string "anyURI" :string
   "integer" :integer "int" :integer "long" :integer "short" :integer "byte" :integer
   "nonNegativeInteger" :integer "positiveInteger" :integer
   "nonPositiveInteger" :integer "negativeInteger" :integer
   "unsignedInt" :integer "unsignedLong" :integer "unsignedShort" :integer
   "decimal" :decimal "double" :decimal "float" :decimal
   "boolean" :boolean})

(defn literal
  "A literal from its lexical form plus an optional datatype IRI and language tag.
  Untagged, and tagged with a datatype we read natively, come back as plain values; a
  language tag always survives, since it is the only thing telling two translations of
  one string apart.

  Public because it is the *contract*, not a helper: `vaelii.foreign.rdfxml` is a second
  lexer over the same graph, and two lexers that normalized literals differently would
  hand `vaelii.foreign.rdf` two different datasets for one ontology depending on which
  syntax it happened to be published in.  A datatype and a language tag together is not
  a thing RDF has — pass one or the other."
  [^String lexical datatype lang]
  (cond
    lang {:lex lexical :lang lang}
    (nil? datatype) lexical
    :else
    (let [s (iri-str datatype)
          native (when (str/starts-with? s xsd) (native-datatypes (subs s (count xsd))))]
      (case native
        :string  lexical
        :integer (try (Long/parseLong (str/trim lexical))
                      (catch NumberFormatException _ {:lex lexical :datatype datatype}))
        :decimal (try (Double/parseDouble (str/trim lexical))
                      (catch NumberFormatException _ {:lex lexical :datatype datatype}))
        :boolean (case (str/trim lexical)
                   ("true" "1")  true
                   ("false" "0") false
                   {:lex lexical :datatype datatype})
        {:lex lexical :datatype datatype}))))

;;; ── IRI resolution ────────────────────────────────────────────────────

(def ^:private uri-parts
  "RFC 3986's own appendix-B regex: scheme, authority, path, query, fragment."
  #"^(?:([^:/?#]+):)?(?://([^/?#]*))?([^?#]*)(?:\?([^#]*))?(?:#(.*))?")

(defn- parse-iri [^String s]
  (let [[_ scheme authority path query fragment] (re-find uri-parts s)]
    {:scheme scheme :authority authority :path (or path "") :query query :fragment fragment}))

(defn- remove-dot-segments
  "RFC 3986 §5.2.4.  `/dir/../file` is `/file`, and a `..` that would climb past the root
  is discarded rather than escaping it."
  ^String [^String path]
  (loop [in path, out []]
    (cond
      (str/blank? in) (str/join out)
      (str/starts-with? in "../") (recur (subs in 3) out)
      (str/starts-with? in "./")  (recur (subs in 2) out)
      (str/starts-with? in "/./") (recur (str "/" (subs in 3)) out)
      (= in "/.")                 (recur "/" out)
      (str/starts-with? in "/../") (recur (str "/" (subs in 4)) (cond-> out (seq out) pop))
      (= in "/..")                (recur "/" (cond-> out (seq out) pop))
      (or (= in ".") (= in "..")) (recur "" out)
      :else (let [i   (str/index-of in "/" 1)
                  seg (if i (subs in 0 (long i)) in)]
              (recur (if i (subs in (long i)) "") (conj out seg))))))

(defn- merge-path
  "RFC 3986 §5.3's `merge`.  A base that has an authority but no path contributes a bare
  `/`, which is what makes `relfile` against `http://example.org` come out as
  `http://example.org/relfile` rather than as a sibling of the host."
  ^String [base ^String ref-path]
  (if (and (:authority base) (str/blank? (:path base)))
    (str "/" ref-path)
    (let [p (:path base)
          i (str/last-index-of p "/")]
      (str (if i (subs p 0 (inc (long i))) "") ref-path))))

(defn resolve-iri
  "`ref` resolved against `base`, by RFC 3986 §5.3.

  This is the whole algorithm, dot segments included, and it is worth the fifty lines:
  `xml:base` in an OWL file is routinely `http://example.org/dir/file` with `../other`
  hanging off it, an ontology's import closure is written in relative references, and a
  resolver that is merely close produces IRIs that look right and name nothing.  The W3C
  syntax suites test exactly these cases, which is how the earlier approximation here was
  found to be wrong."
  ^String [^String base ^String ref]
  (if (or (nil? base) (str/blank? base))
    ref
    (let [b (parse-iri base)
          r (parse-iri ref)
          t (cond
              (:scheme r)    (assoc r :path (remove-dot-segments (:path r)))
              (:authority r) (assoc r :scheme (:scheme b) :path (remove-dot-segments (:path r)))
              (str/blank? (:path r))
              (assoc b :query (or (:query r) (:query b)) :fragment (:fragment r))
              :else
              (assoc b :query (:query r) :fragment (:fragment r)
                     :path (remove-dot-segments
                            (if (str/starts-with? (:path r) "/")
                              (:path r)
                              (merge-path b (:path r))))))]
      (str (when (:scheme t) (str (:scheme t) ":"))
           (when (:authority t) (str "//" (:authority t)))
           (:path t)
           (when (:query t) (str "?" (:query t)))
           (when (:fragment t) (str "#" (:fragment t)))))))

;;; ── the reader ────────────────────────────────────────────────────────

(defn- ws? [^long c] (or (= c 32) (= c 9) (= c 10) (= c 13)))

(defn- skip-ws!
  "Consume whitespace and `#` comments.  A `#` inside `<…>` or `\"…\"` never reaches
  here — both are read whole by their own routine."
  [^PushbackReader r]
  (loop []
    (let [c (.read r)]
      (cond
        (neg? c)   nil
        (ws? c)    (recur)
        (= \# (char c)) (do (loop [] (let [d (.read r)]
                                       (when-not (or (neg? d) (= \newline (char d))) (recur))))
                            (recur))
        :else      (.unread r c)))))

(defn- peek-char ^long [^PushbackReader r]
  (let [c (.read r)]
    (when-not (neg? c) (.unread r c))
    c))

(defn- bad!
  "Refuse a token.  Thrown rather than returned because a token reader is several frames
  below the statement loop with a half-built buffer in hand, and threading a failure back
  up through all of them would obscure every one of them.  `read-statement!` catches it
  and `malformed!` decides what it costs — the statement, or the document."
  [why]
  (throw (ex-info (str "Turtle: " why) {:type :turtle/syntax})))

(def ^:private max-nesting
  "How deep `[ … ]` and `( … )` may nest before the statement is refused.

  The reader descends by calling itself, so nesting is paid for in stack, and a document
  nested past what the stack holds ends the *process* with a `StackOverflowError` — an
  `Error`, which `read-statement!`'s catch does not see and `:strict?` therefore cannot
  turn into a clean refusal.  A limit is what makes such a document a bad statement like
  any other one.

  256 is far past anything written: the deepest nesting in the whole W3C Turtle suite is
  2, and a collection or a predicate-object list of any length is read by a loop rather
  than by descending.  It is also well under where the stack gives out — measured at
  ~415 levels with a 512k stack, the smallest a JVM is run with, and ~2,000 with the
  default."
  256)

(defn- deeper!
  "One level further into a `[…]` or `(…)`, refused past `max-nesting`."
  [^long depth]
  (when (>= depth max-nesting)
    (bad! (str "nesting deeper than " max-nesting)))
  (inc depth))

(defn- read-hex!
  "The code point of a `\\u` / `\\U` escape: exactly `n` hex digits, no fewer.  Checked
  rather than assumed, because `\\u00E9` and `\\u00E` differ by one character and the
  second silently eats the next one."
  ^long [^PushbackReader r ^long n]
  (let [cs (char-array n)
        got (.read r cs 0 (int n))
        s   (String. cs 0 (max 0 got))]
    (when (or (not= got n) (not (re-matches #"[0-9A-Fa-f]+" s)))
      (bad! (str "\\" (if (= n 4) "u" "U") " needs " n " hex digits, got " (pr-str s))))
    (let [cp (Long/parseLong s 16)]
      ;; a lone surrogate is well-formed hex and not a character.  It survives into a
      ;; Java string looking like one, so nothing downstream would notice
      (when (or (<= 0xD800 cp 0xDFFF) (> cp 0x10FFFF))
        (bad! (str "\\u" s " is not a code point")))
      cp)))

(def ^:private string-escapes
  {\n \newline, \t \tab, \r \return, \b \backspace, \f \formfeed,
   \" \", \' \', \\ \\})

(def ^:private iri-forbidden
  "The characters an IRIREF may not contain, straight from the grammar.  A space is the
  one that matters in practice: an ontology holding `<http://example/a b>` is a file two
  parsers will disagree about, so it is refused rather than guessed at."
  #{\< \> \" \{ \} \| \^ \` \\})

(defn- iri-char!
  "`cp` is admissible inside an IRI.  Checked on the escaped path too, because `\\u0020`
  is a space however it was spelled — writing the forbidden character as an escape is
  exactly how a document sneaks one past a reader that only looks at raw bytes."
  [^long cp]
  (when (or (<= cp 0x20) (and (< cp 0x80) (iri-forbidden (char cp))))
    (bad! (str "an IRI containing " (pr-str (char cp)))))
  cp)

(defn- read-escape!
  "The character(s) a backslash escape stands for, the backslash already consumed.

  `in` says which escapes are legal: a string literal takes the whole set, an IRI takes
  only the numeric ones — `<http://example/\\n>` is not a newline in an IRI, it is a
  document that does not parse."
  [^PushbackReader r ^StringBuilder sb in]
  (let [c (.read r)
        check (if (= in :iri) iri-char! identity)]
    (when (neg? c) (bad! "a backslash at the end of input"))
    (case (char c)
      \u (.appendCodePoint sb (int (long (check (read-hex! r 4)))))
      \U (.appendCodePoint sb (int (long (check (read-hex! r 8)))))
      (if-let [e (and (= in :string) (string-escapes (char c)))]
        (.append sb ^char e)
        (bad! (str "\\" (char c) " is not an escape in " (name in)))))))

(defn- read-iri-ref!
  "Read `<…>`, the opening angle already consumed.  Returns the raw reference text."
  ^String [^PushbackReader r]
  (let [sb (StringBuilder.)]
    (loop []
      (let [c (.read r)]
        (cond
          (neg? c)         (bad! "an IRI with no closing >")
          (= \> (char c))  (str sb)
          (= \\ (char c))  (do (read-escape! r sb :iri) (recur))
          :else            (do (iri-char! c) (.append sb (char c)) (recur)))))))

(defn- read-quoted!
  "Read a string literal, the opening quote character `q` already consumed once.  Handles
  both the one-quote and the three-quote (multi-line) forms; a `\"\"\"` literal is how a
  Turtle ontology writes a definition that runs to a paragraph."
  ^String [^PushbackReader r ^long q]
  (let [long? (and (= (peek-char r) q)
                   (do (.read r)
                       (if (= (peek-char r) q) (do (.read r) true) (do (.unread r q) false))))
        sb (StringBuilder.)]
    (if-not long?
      (loop []
        (let [c (.read r)]
          (cond
            (neg? c)        (bad! "a string with no closing quote")
            (= c q)         (str sb)
            ;; a bare newline ends a one-quote string in the grammar; allowing it would
            ;; make an unterminated string swallow the rest of the file
            (or (= c 10) (= c 13)) (bad! "a newline inside a single-quoted string")
            (= \\ (char c)) (do (read-escape! r sb :string) (recur))
            :else           (do (.append sb (char c)) (recur)))))
      (loop [run 0]
        (let [c (.read r)]
          (cond
            (neg? c) (bad! "a long string with no closing quotes")
            (= c q)  (if (= run 2) (str sb) (recur (inc run)))
            :else    (do (dotimes [_ run] (.appendCodePoint sb (int q)))
                         (if (= \\ (char c))
                           (read-escape! r sb :string)
                           (.append sb (char c)))
                         (recur 0))))))))

;; Turtle's own character classes, written out rather than approximated with `\p{L}`.
;; They are not a Unicode category: `PN_CHARS_BASE` deliberately admits the zero-width
;; joiners and the superscript digits and deliberately excludes the surrogate block, so
;; any Java category that looks close is wrong somewhere, and the suite has a test that
;; walks every one of these boundaries a character at a time.
(def ^:private pn-chars-base
  (str "A-Za-z\\u00C0-\\u00D6\\u00D8-\\u00F6\\u00F8-\\u02FF\\u0370-\\u037D\\u037F-\\u1FFF"
       "\\u200C-\\u200D\\u2070-\\u218F\\u2C00-\\u2FEF\\u3001-\\uD7FF\\uF900-\\uFDCF"
       "\\uFDF0-\\uFFFD\\x{10000}-\\x{EFFFF}"))

(def ^:private pn-chars-u (str pn-chars-base "_"))

(def ^:private pn-chars
  (str pn-chars-u "0-9\\u00B7\\u0300-\\u036F\\u203F-\\u2040\\-"))

(def ^:private blank-node-label
  "`BLANK_NODE_LABEL` after the `_:`: begins with a name character or a digit, may hold
  dots inside, may not end with one.  `read-name!` has already handed back any trailing
  dot, so this only has to reject what was never a label."
  (re-pattern (str "[" pn-chars-u "0-9](?:[" pn-chars ".]*[" pn-chars "])?")))

(def ^:private local-escapes
  "Turtle's `PN_LOCAL_ESC` — the characters a prefixed name may carry behind a backslash.
  They are exactly the ones that would otherwise end the name, which is what the escape is
  for; anything else behind a backslash is a typo the grammar does not admit."
  (set "_~.-!$&'()*+,;=/?#@%"))

(defn- name-char? [^long c]
  (and (pos? c)
       (not (ws? c))
       (not (contains? #{\( \) \[ \] \, \; \. \" \' \< \> \^ \# \@} (char c)))))

(defn- read-name!
  "Read a bare token — a prefixed name, `a`, a number, a boolean, a blank-node label.

  **A dot is part of the token unless it is the last character of it.**  That one rule is
  the whole of Turtle's awkwardness about `.`, and it covers every case at once: `1.5`
  and `123.E+1` are numbers, `ex:s.1` and `ex:s..2` and the prefix in `e.g:s` are names,
  and the `.` in `ex:s .` and in `ex:s.` is the statement terminator.  Reading dots
  greedily and then handing the trailing ones back is the only way to tell those apart,
  because which it is is not known until the character *after* the dot."
  ^String [^PushbackReader r]
  (let [sb (StringBuilder.)]
    (loop []
      (let [c (.read r)]
        (cond
          (neg? c)         nil
          (= \\ (char c))  (let [e (.read r)]          ; Turtle's PN_LOCAL_ESC
                             (when (neg? e) (bad! "a backslash at the end of input"))
                             (when-not (local-escapes (char e))
                               (bad! (str "\\" (char e) " is not an escape in a name")))
                             (.append sb (char e))
                             (recur))
          (= \. (char c))  (do (.append sb \.) (recur))
          ;; a `%` in a prefixed name opens a percent-encoding and must be two hex digits.
          ;; `:o%2` is not a name with a stray percent in it; it is a truncated one
          (= \% (char c))  (let [h (char-array 2)
                                 got (.read r h 0 2)
                                 s (String. h 0 (max 0 got))]
                             (when-not (re-matches #"[0-9A-Fa-f]{2}" s)
                               (bad! (str "%" s " is not a percent-encoding")))
                             (.append sb \%) (.append sb s)
                             (recur))
          (name-char? c)   (do (.append sb (char c)) (recur))
          :else            (.unread r c))))
    (loop []
      (when (and (pos? (.length sb)) (= \. (.charAt sb (dec (.length sb)))))
        (.unread r (int \.))
        (.setLength sb (dec (.length sb)))
        (recur)))
    (str sb)))

(def ^:private integer-pattern #"[-+]?\d+")
(def ^:private double-pattern  #"[-+]?(?:\d+\.\d*|\.\d+|\d+)(?:[eE][-+]?\d+)?")

;;; ── parser state ──────────────────────────────────────────────────────
;;
;; A statement can produce more triples than it has terms — `[ … ]` and `( … )` are
;; abbreviations for triples about a fresh blank node — so the parser writes into a
;; queue and the lazy seq drains it, rather than returning one triple per call.

(defn- ->state [^PushbackReader r opts]
  {:r r
   :prefixes (volatile! {})
   :base     (volatile! (:base opts))
   :counter  (volatile! 0)
   :out      (volatile! [])
   ;; A bare token read to see whether it was a SPARQL-style `PREFIX` / `BASE`, and was
   ;; not.  Handing it back through the state rather than unreading it keeps the
   ;; pushback buffer off the critical path — a prefixed name has no length bound, and a
   ;; buffer that is merely large is a parser that fails on somebody's IRI scheme.
   :pending  (volatile! nil)
   ;; refuse a malformed statement instead of skipping it — see `malformed!`
   :strict?  (:strict? opts false)})

(defn- emit! [{:keys [out]} s p o g]
  (vswap! out conj (cond-> {:s s :p p :o o} g (assoc :g g))))

(defn- fresh-bnode! [{:keys [counter]}]
  (symbol "bnode" (str "g" (vswap! counter inc))))

(defn- expand
  "A prefixed name `pfx:local` as a full IRI, or nil when the prefix is undeclared — a
  file that uses a prefix it never declared is malformed, and the statement is skipped
  rather than guessed at."
  [{:keys [prefixes]} ^String tok]
  (let [i (str/index-of tok ":")]
    (when i
      (let [pfx (subs tok 0 i)
            local (subs tok (inc i))]
        (when-let [ns-iri (get @prefixes pfx)]
          (iri (str ns-iri local)))))))

(declare read-term!)

(defn- read-predicate-object-list!
  "The `p o (, o)* (; p o (, o)*)*` that follows a subject, ending before `.`, `]`, or a
  term where none of those was expected.

  Turtle **requires** the `;` between one predicate-object pair and the next, and that
  requirement is what makes N-Quads readable by the same loop: a bare term sitting where
  a `;` should be cannot be another predicate, so it is the quad's graph label and the
  caller reads it.

  The triples are emitted; the return value says whether the list was **well formed** —
  false when a term came back nil, which is a malformed statement or an undeclared
  prefix, and either way means the caller should skip to the next `.` rather than try to
  resume in the middle of it."
  [st subject graph depth]
  (let [^PushbackReader r (:r st)]
    (loop []
      (skip-ws! r)
      ;; `;;` is legal and states nothing — a separator repeated is still one separator,
      ;; and the grammar allows an empty predicate-object entry anywhere in the list
      (loop []
        (when (= \; (char (max 0 (peek-char r))))
          (.read r)
          (skip-ws! r)
          (recur)))
      (let [c (peek-char r)]
        (cond
          (or (neg? c) (contains? #{\. \]} (char c))) true
          :else
          (let [p (read-term! st graph depth)]
            (if (nil? p)
              false
              (let [ok (loop []
                         (skip-ws! r)
                         (let [o (read-term! st graph depth)]
                           (if (nil? o)
                             false
                             (do (emit! st subject p o graph)
                                 (skip-ws! r)
                                 (if (= \, (char (max 0 (peek-char r))))
                                   (do (.read r) (recur))
                                   true)))))]
                (if-not ok
                  false
                  (do (skip-ws! r)
                      (if (= \; (char (max 0 (peek-char r))))
                        (do (.read r) (recur))
                        true)))))))))))

(defn- read-collection!
  "`( a b c )`, the opening paren already consumed — an rdf:first/rdf:rest chain over
  fresh blank nodes.  The empty collection is `rdf:nil`, which is why this can return a
  term that is not a blank node."
  [st graph depth]
  (let [^PushbackReader r (:r st)
        items (loop [acc []]
                (skip-ws! r)
                (let [c (peek-char r)]
                  (cond
                    (neg? c)        acc
                    (= \) (char c)) (do (.read r) acc)
                    :else           (let [t (read-term! st graph depth)]
                                      (if (nil? t) acc (recur (conj acc t)))))))]
    (if (empty? items)
      rdf-nil
      (let [cells (mapv (fn [_] (fresh-bnode! st)) items)]
        (dotimes [i (count items)]
          (emit! st (cells i) rdf-first (items i) graph)
          (emit! st (cells i) rdf-rest (if (< (inc i) (count cells)) (cells (inc i)) rdf-nil) graph))
        (first cells)))))

(defn- token->term
  "The term a bare token stands for — `a`, a boolean, a number, a blank-node label, a
  prefixed name.  nil for a prefix nobody declared: a file that uses one is malformed,
  and the statement is skipped rather than guessed at."
  [st ^String tok]
  (cond
    (= "" tok)                        nil
    (= "a" tok)                       rdf-type
    (= "true" tok)                    true
    (= "false" tok)                   false
    (str/starts-with? tok "_:")
    (let [label (subs tok 2)]
      ;; `_::a` and `_:` are not labels.  A blank node's label is the only name in the
      ;; grammar with no namespace to disambiguate it, so a colon in one has nowhere to go
      (when-not (re-matches blank-node-label label)
        (bad! (str "_:" label " is not a blank node label")))
      (symbol "bnode" label))
    (re-matches integer-pattern tok)  (try (Long/parseLong tok)
                                           (catch NumberFormatException _ (bigint tok)))
    (re-matches double-pattern tok)   (Double/parseDouble tok)
    (str/includes? tok ":")           (expand st tok)
    :else                             nil))

(defn- read-term!
  "One term in subject, predicate or object position, or nil at a structural token the
  caller owns.  A `[…]` or `(…)` emits its own triples and comes back as the node they
  are about.

  `depth` is how many of those this term is already inside, and `deeper!` is what refuses
  a document that nests past what the stack holds."
  [st graph depth]
  (let [^PushbackReader r (:r st)
        base    (:base st)
        pending (:pending st)]
    (if-let [tok @pending]
      (do (vreset! pending nil) (token->term st tok))
      (do
        (skip-ws! r)
        (let [c (.read r)]
          (cond
            (neg? c) nil

            (= \< (char c)) (iri (resolve-iri @base (read-iri-ref! r)))

            (or (= \" (char c)) (= \' (char c)))
            (let [lexical (read-quoted! r c)
                  d (peek-char r)]
              (cond
                (and (pos? d) (= \@ (char d)))
                (do (.read r) (literal lexical nil (read-name! r)))

                (and (pos? d) (= \^ (char d)))
                (do (.read r) (.read r)                     ; the second ^
                    (let [t (read-term! st graph depth)]
                      (literal lexical (when (iri? t) t) nil)))

                :else (literal lexical nil nil)))

            (= \[ (char c))
            (let [b (fresh-bnode! st)]
              (read-predicate-object-list! st b graph (deeper! depth))
              (skip-ws! r)
              (when (= \] (char (max 0 (peek-char r)))) (.read r))
              b)

            (= \( (char c)) (read-collection! st graph (deeper! depth))

            ;; a `.` that begins a token is a decimal — `.1` is a number, and it is the
            ;; one place a leading dot is not the statement terminator
            (and (= \. (char c)) (let [d (peek-char r)]
                                   (and (pos? d) (Character/isDigit (char d)))))
            (do (.unread r c) (token->term st (read-name! r)))

            (contains? #{\. \; \, \) \]} (char c)) (do (.unread r c) nil)

            :else (do (.unread r c) (token->term st (read-name! r)))))))))

(defn- read-directive!
  "`@prefix` / `@base`, and their keyword spellings (`PREFIX` / `BASE`, which SPARQL
  taught every Turtle file to use).  Returns true when one was consumed."
  [st ^String word]
  (let [^PushbackReader r (:r st)
        prefixes (:prefixes st)
        base     (:base st)
        w        (str/lower-case word)]
    (cond
      (or (= w "@prefix") (= w "prefix"))
      (do (skip-ws! r)
          (let [pfx (read-name! r)
                _   (skip-ws! r)
                _   (.read r)                            ; the <
                v   (resolve-iri @base (read-iri-ref! r))]
            (vswap! prefixes assoc (str/replace pfx #":$" "") v)
            (skip-ws! r)
            (when (= \. (char (max 0 (peek-char r)))) (.read r))
            true))

      (or (= w "@base") (= w "base"))
      (do (skip-ws! r)
          (.read r)                                      ; the <
          (vreset! base (resolve-iri @base (read-iri-ref! r)))
          (skip-ws! r)
          (when (= \. (char (max 0 (peek-char r)))) (.read r))
          true)

      :else false)))

(defn- skip-to-dot!
  "Consume through the next statement-ending `.` — how a malformed statement is
  recovered from.  A dump of a hundred million triples has a few bad lines in it, and
  losing the file to one of them is worse than losing the statement."
  [^PushbackReader r]
  (loop []
    (let [c (.read r)]
      (cond
        (neg? c)        nil
        (= \. (char c)) nil
        (= \" (char c)) (do (read-quoted! r c) (recur))
        (= \< (char c)) (do (read-iri-ref! r) (recur))
        :else           (recur)))))

(defn- malformed!
  "What a statement this reader could not read costs.  Under `:strict?` it costs the
  document; otherwise it costs itself, and `skip-to-dot!` says why that is the default.

  Both readings are wanted and neither is right for the other's job: a converter pointed
  at a billion-triple dump must survive a bad line, and a *conformance* test asks
  precisely whether the reader can tell a bad document from a good one — a recovering
  reader answers \"good\" to everything, which is not a pass.

  `why` is either a reason to describe or the refusal `bad!` already threw.  A refusal is
  rethrown as it stands: it carries its own message and whatever `ex-data` the token
  reader attached, and rebuilding it from the message alone would name the syntax twice
  and lose the rest."
  [st ^PushbackReader r why]
  (when (:strict? st)
    (throw (if (instance? clojure.lang.ExceptionInfo why)
             why
             (ex-info (str "Turtle: " why) {:type :turtle/syntax}))))
  (skip-to-dot! r)
  nil)

(defn- read-statement!
  "Read one statement, emitting its triples.  Returns `:eof` at the end of input.

  The N-Quads graph term is read here rather than in `read-term!`: it is the one
  position whose meaning comes from *where* it is — a fourth term before the `.` — and
  nothing else in the grammar has that shape."
  [st]
  (let [^PushbackReader r (:r st)
        pending (:pending st)
        ;; where this statement's triples begin, so a statement that turns out to be
        ;; malformed can be un-emitted whole — half a statement states nothing
        mark (count @(:out st))]
    (skip-ws! r)
    (try
      (let [c (peek-char r)]
        (cond
          (neg? c) :eof

          ;; `@prefix` / `@base` — unambiguous, nothing else starts a statement with `@`
          (= \@ (char c))
          (do (.read r) (read-directive! st (str "@" (read-name! r))) nil)

          :else
          ;; SPARQL's keyword spelling (`PREFIX` / `BASE`) is a bare token, and so is a
          ;; prefixed name — they are told apart by reading one and looking.  A token that
          ;; turns out not to be a directive is handed to `read-term!` through `:pending`.
          ;; A directive **is** a whole statement: falling through to read a term after one
          ;; would demand a subject the document need not have, and a file that is nothing
          ;; but `PREFIX` lines is legal.
          (if (and (contains? #{\P \p \B \b} (char c))
                   (let [tok (read-name! r)]
                     (or (read-directive! st tok)
                         (do (vreset! pending tok) false))))
            nil
            (let [s (read-term! st nil 0)]
              (if (nil? s)
                (malformed! st r "a statement that does not begin with a term")
                (let [before (count @(:out st))
                      ok     (read-predicate-object-list! st s nil 0)]
                  (if-not ok
                    (do (vswap! (:out st) subvec 0 before)   ; a half-read statement states nothing
                        (malformed! st r "a statement with no readable predicate-object list"))
                    (do
                      (skip-ws! r)
                      ;; N-Quads: a term where the `.` should be names the graph, and every
                      ;; triple this statement just produced belongs to it
                      (let [d (peek-char r)]
                        (when (and (pos? d) (not= \. (char d)))
                          (when-let [g (read-term! st nil 0)]
                            (vswap! (:out st)
                                    (fn [v] (into (subvec v 0 before)
                                                  (map #(assoc % :g g))
                                                  (subvec v before)))))))
                      (skip-ws! r)
                      ;; the terminating `.` is required, not optional.  Letting it be
                      ;; missing is what turns two statements into one and a truncated file
                      ;; into a shorter graph rather than an error
                      (if (= \. (char (max 0 (peek-char r))))
                        (do (.read r) nil)
                        (do (vswap! (:out st) subvec 0 mark)
                            (malformed! st r "a statement with no terminating .")))))))))))
      (catch clojure.lang.ExceptionInfo e
        ;; a token reader refused (`bad!`).  Non-strict, that costs this statement and the
        ;; file carries on; strict, `malformed!` rethrows and it costs the document.
        (if (= :turtle/syntax (:type (ex-data e)))
          (do (vswap! (:out st) subvec 0 mark)
              (malformed! st r e))
          (throw e))))))

(defn triples
  "Every triple `rdr` holds, as a lazy seq of `{:s :p :o}` maps — with `:g` on a quad.
  The reader is left open: the caller owns it (`with-open`), because the seq is consumed
  lazily.

  `opts` may carry `:base`, for a fragment of a document whose header is elsewhere.

  `:strict? true` makes a malformed statement throw rather than be skipped.  The default
  is to skip, because that is what a bulk dump needs; `malformed!` says why both readings
  have to exist."
  ([rdr] (triples rdr {}))
  ([^Reader rdr opts]
   (let [st (->state (PushbackReader. rdr 64) opts)]
     ((fn step []
        (lazy-seq
         (let [out (:out st)]
           (loop []
             (if (seq @out)
               (let [t (first @out)]
                 (vswap! out subvec 1)
                 (cons t (step)))
               (when-not (= :eof (read-statement! st))
                 (recur)))))))))))

(defn with-triples
  "Open the RDF file at `path`, call `(f triples)` on a lazy seq of its triples, and
  close the file after.  A `.gz` path is decompressed on the way through, since that is
  how every dump worth streaming ships.  The seq is only valid inside `f` — it reads
  from the open stream."
  ([path f] (with-triples path f {}))
  ([path f opts]
   (with-open [in (io/input-stream path)]
     (let [in (if (str/ends-with? (str/lower-case (str path)) ".gz")
                (java.util.zip.GZIPInputStream. in)
                in)]
       (with-open [r (io/reader in)]
         (f (triples r opts)))))))
