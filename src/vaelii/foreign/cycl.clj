(ns vaelii.foreign.cycl
  "Read a CycL assertion dump — the s-expression form of an OpenCyc KB — as data.

  This is the reader for a corpus that arrives already extracted as text;
  `vaelii.foreign.units` reads the distribution's own binary dump, and both yield the
  same maps.  A CycL dump is a stream of `ke-assert` forms, one per assertion:

      (ke-assert '(#$genls #$Dog #$Mammal) #$BiologyMt :monotonic :forward)

  so each carries a **formula**, the **microtheory** it holds in, its **strength**
  (`:monotonic` / `:default` — Cyc's own monotonic-vs-default truth value, which is
  vaelii's assumption strength under another name) and its **direction**
  (`:forward` / `:backward` / `:code`).

  Nothing here interprets CycL: this namespace is the *lexer*, and
  `vaelii.foreign.cyc` is the translation.  Reading is separated because the
  dump's syntax is Common Lisp, not EDN — `#$Constant` is not a reader tag, a
  string may span lines, and a float prints as `1.0d0` — so `clojure.edn/read`
  cannot be pointed at it.

  **How a CycL token comes back.**  Every kind of token maps to ordinary Clojure
  data, and the *namespace* of a symbol is what distinguishes the kinds that would
  otherwise collide:

  | CycL           | Clojure                | why                                    |
  |----------------|------------------------|----------------------------------------|
  | `#$Dog`        | `cyc/Dog`              | a KB constant — the only kind translated |
  | `?X`           | `?X`                   | a variable, already vaelii's spelling  |
  | `foo-bar`      | `subl/foo-bar`         | a bare SubL symbol — executable code, never KB content |
  | `\"text\"`       | `\"text\"`               | |
  | `:keyword`     | `:keyword`             | |
  | `12`, `1.0d0`  | `12`, `1.0`            | |
  | `(a b)`        | `(a b)`                | |

  A form the dumper could not resolve reads back as its own marker list —
  `(:nart 23227)`, `(:unresolved-assertion 88)` — and stays that way, for the
  translation to reject.

  The reader is a lazy seq over a `Reader`, so a 268 MB dump streams in constant
  memory."
  (:require [clojure.java.io :as io])
  (:import (java.io PushbackReader Reader)))

(def ^:private eof ::eof)

(defn- cyc-constant
  "The Clojure symbol a `#$Name` constant reads as."
  [^String nm] (symbol "cyc" nm))

(defn constant?
  "Is `x` a CycL constant — a `#$Name` from the dump?"
  [x] (and (symbol? x) (= "cyc" (namespace x))))

(defn subl?
  "Is `x` a bare SubL symbol?  Those name Lisp code, not knowledge."
  [x] (and (symbol? x) (= "subl" (namespace x))))

(defn cyc-name
  "The Cyc-side name of a constant — `cyc/Dog` -> \"Dog\"."
  [x] (name x))

;;; ── the reader ────────────────────────────────────────────────────────

(defn- bad!
  "Refuse the dump, saying why.  Thrown rather than returned because the reader is a
  stream of forms and there is no form to hand back — a caller that wants to survive one
  catches it and stops reading, which is what it would have to do anyway."
  [why]
  (throw (ex-info (str "CycL: " why) {:type :cycl/syntax})))

(def ^:private max-nesting
  "How deep a form may nest before the reader refuses it.

  A list is read by descending into it, so nesting is paid for in stack, and a dump
  nested past what the stack holds ends the *process* with a `StackOverflowError` — an
  `Error`, which a caller's catch around a read does not see and cannot report as a bad
  dump.  A limit is what keeps a refusal a refusal.

  256 is far past any formula: a CycL assertion nests as deep as its quantifiers and
  connectives do, which is single digits in the dump and a couple of dozen in the worst
  hand-written rule.  It is also well under where the stack gives out — measured at ~990
  levels with a 512k stack, the smallest a JVM is run with, and ~4,900 with the default."
  256)

(defn- deeper!
  "One level further into a list, refused past `max-nesting`."
  [^long depth]
  (when (>= depth max-nesting)
    (bad! (str "a form nested deeper than " max-nesting)))
  (inc depth))

(defn- terminator?
  "Does character `c` end an unquoted token?"
  [^long c]
  (or (neg? c)
      (Character/isWhitespace (int c))
      (contains? #{\( \) \" \' \;} (char c))))

(defn- read-token
  "Read one unquoted token's characters, stopping before the terminator."
  ^String [^PushbackReader r]
  (let [sb (StringBuilder.)]
    (loop []
      (let [c (.read r)]
        (cond
          (neg? c)      (str sb)
          (terminator? c) (do (.unread r c) (str sb))
          :else         (do (.append sb (char c)) (recur)))))))

(defn- read-string-literal
  "Read a `\"…\"` literal, the opening quote already consumed.  Backslash escapes
  any character (Lisp's rule), and a newline inside the literal is ordinary
  content — a Cyc `comment` string routinely spans lines."
  ^String [^PushbackReader r]
  (let [sb (StringBuilder.)]
    (loop []
      (let [c (.read r)]
        (cond
          (neg? c)      (str sb)
          (= \" (char c)) (str sb)
          (= \\ (char c)) (let [e (.read r)]
                            (when-not (neg? e) (.append sb (char e)))
                            (recur))
          :else         (do (.append sb (char c)) (recur)))))))

(def ^:private integer-pattern #"[-+]?\d+")
(def ^:private ratio-pattern   #"[-+]?\d+/\d+")
(def ^:private float-pattern   #"[-+]?(?:\d+\.\d*|\.\d+|\d+)(?:[esfdlESFDL][-+]?\d+)?")

(defn- read-atom
  "The value of an unquoted token: a number where it parses as one, a keyword, a
  variable, a `#$` constant, else a SubL symbol.  A Lisp float carries an exponent
  marker other than `e` (`1.0d0` is a double), which `Double/parseDouble` does not
  accept, so the marker is normalized first."
  [^String tok]
  (cond
    (= "" tok)                          nil
    (re-matches integer-pattern tok)    (try (Long/parseLong tok)
                                             (catch NumberFormatException _ (bigint tok)))
    (re-matches ratio-pattern tok)      (let [[n d] (.split tok "/")]
                                          (/ (Long/parseLong n) (Long/parseLong d)))
    (re-matches float-pattern tok)      (Double/parseDouble
                                         (.replaceAll tok "[sfdlSFDL]" "e"))
    (.startsWith tok ":")               (keyword (subs tok 1))
    (.startsWith tok "#$")              (cyc-constant (subs tok 2))
    (.startsWith tok "?")               (symbol tok)
    :else                               (symbol "subl" tok)))

(declare read-form)

(defn- read-list
  "Read the rest of a list, the opening paren already consumed.  A dotted tail
  (`(a . b)`) cannot appear in a CycL formula, so a lone `.` is read as the SubL
  symbol it lexes to rather than given cons semantics.  `depth` is how many lists this
  one is inside."
  [^PushbackReader r depth]
  (loop [acc []]
    (let [c (.read r)]
      (cond
        (neg? c)          (seq acc)
        (= \) (char c))   (apply list acc)
        :else             (do (.unread r c)
                              (let [f (read-form r depth)]
                                (if (identical? f eof)
                                  (apply list acc)
                                  (recur (conj acc f)))))))))

(defn- read-form
  "Read one form, or `eof`.  Whitespace, `;` line comments and `#| … |#` block
  comments are skipped; a `'` quote is transparent — the dump quotes every formula
  and the quote carries no meaning once it is data, so the quoted form is read in
  this frame rather than a deeper one.  `depth` is how many lists the form is inside."
  [^PushbackReader r depth]
  (loop []
    (let [c (.read r)]
      (cond
        (neg? c)                        eof
        (Character/isWhitespace c)      (recur)  ; c is an int codepoint
        (= \; (char c))                 (do (loop [] (let [d (.read r)]
                                                       (when-not (or (neg? d) (= \newline (char d)))
                                                         (recur))))
                                            (recur))
        (= \' (char c))                 (recur)
        (= \( (char c))                 (read-list r (deeper! depth))
        (= \) (char c))                 (recur)          ; stray close: skip
        (= \" (char c))                 (read-string-literal r)
        (= \# (char c))                 (let [d (.read r)]
                                          (if (and (pos? d) (= \| (char d)))
                                            (do (loop [prev \space]          ; block comment
                                                  (let [e (.read r)]
                                                    (when-not (or (neg? e)
                                                                  (and (= \| prev) (= \# (char e))))
                                                      (recur (char e)))))
                                                (recur))
                                            (do (when-not (neg? d) (.unread r d))
                                                (.unread r c)
                                                (read-atom (read-token r)))))
        :else                           (do (.unread r c)
                                            (read-atom (read-token r)))))))

(defn forms
  "Every top-level form of `rdr`, as a lazy seq.  The reader is left open — the
  caller owns it (`with-open`), because the seq is consumed lazily."
  [^Reader rdr]
  (let [r (PushbackReader. rdr 8)]
    ((fn step []
       (lazy-seq
        (let [f (read-form r 0)]
          (when-not (identical? f eof)
            (cons f (step)))))))))

;;; ── the assertion shape ───────────────────────────────────────────────

(defn assertion
  "The assertion a top-level `(ke-assert FORMULA MT STRENGTH DIRECTION)` form
  states, as `{:formula :mt :strength :direction}` — or nil for any other form
  (the dumper's license header reads as data too).  `:direction` is nil when the
  dump omitted it."
  [form]
  (when (and (seq? form)
             (= 'subl/ke-assert (first form))
             (>= (count form) 4))
    (let [[_ formula mt strength direction] form]
      {:formula formula :mt mt :strength strength :direction direction})))

(defn assertions
  "Every assertion `rdr` holds, as a lazy seq of assertion maps."
  [rdr]
  (keep assertion (forms rdr)))

(defn with-assertions
  "Open the dump file at `path`, call `(f assertions)` on a lazy seq of its
  assertions, and close the file after.  The seq is only valid inside `f` — it reads
  from the open stream — so a caller consumes it there rather than returning it."
  [path f]
  (with-open [r (io/reader path)]
    (f (assertions r))))
