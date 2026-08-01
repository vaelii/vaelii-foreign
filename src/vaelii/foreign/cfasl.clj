(ns vaelii.foreign.cfasl
  "Read CFASL — Cyc's binary serialization format — as Clojure data.

  A CFASL stream is a sequence of self-describing objects: one **opcode** byte, then
  whatever that opcode's payload is, recursively.  Nothing frames an object but its own
  opcode, so a reader that mis-sizes one payload desynchronizes the whole rest of the
  file rather than failing where the mistake is — which is why `vaelii.foreign.units`
  checks its record count against the dump's own and treats a short read as an error.

  **Where this comes from.**  Cycorp published CFASL's reference implementation under
  the Apache License 2.0, so none of this was reverse-engineered:
  `com.cyc.cycjava.cycl.cfasl` supplies the opcode table and every encoding below, and
  `org.opencyc.api.CfaslInputStream` (the same license, per OpenCyc's `LEGAL.txt`)
  supplies the four the SubL sources declare and leave undefined — float, bignum,
  character and byte-vector.  `licenses/THIRD-PARTY.md` records the attribution and
  which upstreams are *not* admissible here.

  **The opcode table is the server's, not the API client's**, and they disagree in
  exactly the range a dump uses: the client reads 36/37/38 as source / source-def /
  axiom, while a KB dump writes deduction / kb-hl-support / clause-struc, and 50 is the
  client's special-object against the server's common-symbol.  Reading unit files with
  the client's table decodes the first few thousand objects and then quietly produces
  nonsense.  The table below is `cfasl.lisp`'s and `cfasl-kb-methods.lisp`'s.

  **Handles are not resolved here.**  A constant, NART, assertion or clause-struc is
  written as its opcode plus an integer dump-id, and what that id means lives in another
  file of the same dump.  So a caller passes `resolvers` — `{:constant f :nart f …}` —
  and anything it does not name reads back as its own marker list, `(:nart 23227)`, the
  same shape `vaelii.foreign.cycl` yields for a reference its own dump could not
  resolve.  That indirection is upstream's too (`*cfasl-nart-handle-lookup-func*`)."
  (:import (java.io ByteArrayOutputStream InputStream)))

;; Opcodes 0-127 are opcodes; 128-255 encode the fixnums 0-127 inline, so the single
;; commonest object in a KB dump — a small non-negative integer — costs one byte.
(def ^:private ^:const immediate-fixnum-offset 128)

(def opcodes
  "opcode -> what it names, the whole table this reader dispatches on.

  Two families, both from Cycorp's own sources: the general ones (`cfasl.lisp`) and the
  KB-object ones (`cfasl-kb-methods.lisp`), which are the ones carrying a dump-id.
  `:cfasl/…` in a comment below marks an opcode this reader refuses rather than guesses
  at — see `unsupported`."
  {0 :p-8bit-int
   1 :n-8bit-int
   2 :p-16bit-int
   3 :n-16bit-int
   4 :p-24bit-int
   5 :n-24bit-int
   6 :p-32bit-int
   7 :n-32bit-int
   8 :p-float
   9 :n-float
   10 :keyword
   11 :other-symbol
   12 :nil
   13 :list
   14 :general-vector
   15 :string
   16 :character
   17 :dotted-list
   18 :hashtable
   23 :p-bignum
   24 :n-bignum
   25 :legacy-guid
   26 :byte-vector
   27 :result-set
   28 :package
   29 :wide-cfasl-opcode
   30 :constant
   31 :nart
   32 :complete-constant
   33 :assertion
   36 :deduction
   37 :kb-hl-support
   38 :clause-struc
   40 :variable
   42 :complete-variable
   43 :guid
   44 :defstruct-recipe
   50 :common-symbol
   51 :externalization
   90 :sbhl-directed-link
   91 :sbhl-undirected-link
   94 :hl-start
   95 :hl-end
   124 :instance
   126 :guid-denoted-type})

(defn- unsupported
  "Throw on an opcode this reader does not decode.

  Refusing is the only safe answer: a payload whose width we guessed wrong is not a
  local error but a desynchronized stream, and everything after it decodes to junk that
  still looks like data.  None of these appear in the unit files of an OpenCyc KB dump;
  one turning up is a fact about the dump worth knowing rather than worth surviving."
  [^long op]
  (throw (ex-info (str "unsupported CFASL opcode " op
                       (when-let [n (opcodes op)] (str " (" n ")")))
                  {:type :cfasl/unsupported-opcode :opcode op :names (opcodes op)})))

(defn- raw-byte
  "One byte, 0-255.  Throws at end of stream: inside a payload, EOF is truncation."
  ^long [^InputStream in]
  (let [b (.read in)]
    (if (neg? b)
      (throw (ex-info "CFASL stream ended inside an object"
                      {:type :cfasl/truncated}))
      b)))

(defn- read-uint
  "`n` bytes as one unsigned little-endian integer.

  Integers are written a byte at a time, low byte first (`cfasl-output-integer-internal`
  recurses on `(quot integer 256)`), so this is the whole of the fixed-width family; the
  negative opcodes are the same bytes negated.  Four bytes unsigned exceeds an int and
  not a long, so nothing here overflows."
  ^long [^InputStream in ^long n]
  (loop [i 0, acc 0]
    (if (= i n)
      acc
      (recur (inc i) (bit-or acc (bit-shift-left (raw-byte in) (* 8 i)))))))

;; How much of a raw payload is held before any of it has arrived.  A file names its own
;; lengths and nothing corroborates them, so the buffer grows with what the stream
;; actually yields rather than being allocated from the stated length: a six-byte file
;; claiming a gigabyte then costs one chunk and a truncation error instead of a gigabyte
;; of heap the reader never gets far enough to discover it cannot fill.
(def ^:private ^:const raw-chunk-bytes (* 64 1024))

(defn- read-raw-bytes
  "`n` bytes of payload.  Fewer than `n` left in the stream is truncation."
  ^bytes [^InputStream in ^long n]
  (let [out (ByteArrayOutputStream. (int (min n raw-chunk-bytes)))
        buf (byte-array (int (max 1 (min n raw-chunk-bytes))))]
    (loop [off 0]
      (if (>= off n)
        (.toByteArray out)
        (let [got (.read in buf 0 (int (min (- n off) (alength buf))))]
          (if (neg? got)
            (throw (ex-info "CFASL stream ended inside a byte payload"
                            {:type :cfasl/truncated :wanted n :got off}))
            (do (.write out buf 0 got)
                (recur (+ off got)))))))))

;; A `?varN` symbol is already vaelii's spelling for a variable, so an HL variable needs
;; no rewriting downstream — upstream prints one as prefix char + id
;; (`*hl-variable-prefix-char*` is `?`), and that is what a rule's literals carry.
(defn- hl-variable [id] (symbol (str "?var" id)))

(defn- resolve-handle
  "What a handle reads back as: whatever `resolvers` has for its kind, or its own marker
  list.

  The marker shape is the one `vaelii.foreign.cycl` already uses for an unresolved
  reference, so a formula carrying one flows through the translation and is dropped with
  a reason instead of being mistaken for a term.  An assertion is the one kind whose
  marker is not named after its opcode: `:unresolved-assertion` is the spelling a CycL
  text dump writes, and the two readers answer alike."
  [resolvers kind ^long id]
  (if-let [f (get resolvers kind)]
    (f id)
    (list (if (= :assertion kind) :unresolved-assertion kind) id)))

;; A genuine cycle, not an ordering problem: a payload is made of whole CFASL objects in
;; turn — a bignum's chunks, a float's significand, a list's members, a string's length —
;; so everything that reads part of an object calls `read-object`, which calls them back.
;; Reordering cannot break it.
(declare read-object)

(defn- read-element
  "One object that is part of another — a list's member, a string's length, a handle's id.

  `read-object` answers `::eof` when the stream ends, because between objects an ending
  is the file's own.  Inside one it is truncation, and saying so here is what keeps a
  count larger than the file from reading as that many endings."
  [^InputStream in resolvers]
  (let [o (read-object in resolvers)]
    (if (= ::eof o)
      (throw (ex-info "CFASL stream ended inside an object" {:type :cfasl/truncated}))
      o)))

(defn- read-natural
  "An object the format requires to be a non-negative integer — a length, an element
  count, a dump-id — as a long.

  Nothing in the encoding constrains what is written in one of those positions: `nil`, a
  negative integer and a bignum past a long all decode fine and then ask for an
  allocation, a loop or an index no input can satisfy.  Refusing here is what makes that
  a named error rather than a `NegativeArraySizeException`, a silently empty list, or a
  heap the reader does not come back from."
  ^long [^InputStream in resolvers]
  (let [n (read-element in resolvers)]
    (if (and (integer? n) (not (neg? n)) (<= n Long/MAX_VALUE))
      (long n)
      (throw (ex-info (str "CFASL object is not a count: " (pr-str n))
                      {:type :cfasl/bad-length :length n})))))

(defn- read-bignum
  "A bignum: a chunk count, then that many 8-bit chunks as objects, least-significant
  first.  `sign` is 1 or -1 — the two opcodes differ in nothing else."
  [^InputStream in resolvers ^long sign]
  (let [n (read-natural in resolvers)]
    (loop [i 0, acc (biginteger 0)]
      (if (= i n)
        (* sign (bigint acc))
        (recur (inc i)
               (.or ^BigInteger acc
                    (.shiftLeft (biginteger (long (read-element in resolvers)))
                                (* 8 i))))))))

(defn- read-float
  "A float: a significand and a base-2 exponent, each a whole object — which is why the
  significand routinely arrives as a bignum, and why a wrong bignum reading shows up
  first as an absurd float.  `sign` is 1.0 or -1.0."
  ^double [^InputStream in resolvers ^double sign]
  (let [significand (read-element in resolvers)
        exponent    (read-element in resolvers)]
    (* sign (double significand) (Math/pow 2.0 (double exponent)))))

(defn read-object
  "Read one CFASL object from `in`, resolving handles through `resolvers`.

  Returns `::eof` when the stream is exhausted **between** objects — the only place an
  ending is legal.  A dump file is read by calling this until it says so, and everything
  inside an object goes through `read-element`, for which an ending is truncation."
  [^InputStream in resolvers]
  (let [op (.read in)]
    (cond
      (neg? op) ::eof
      (>= op immediate-fixnum-offset) (- op immediate-fixnum-offset)
      :else
      (case op
        0 (read-uint in 1)
        1 (- (read-uint in 1))
        2 (read-uint in 2)
        3 (- (read-uint in 2))
        4 (read-uint in 3)
        5 (- (read-uint in 3))
        6 (read-uint in 4)
        7 (- (read-uint in 4))

        8 (read-float in resolvers 1.0)
        9 (read-float in resolvers -1.0)

        ;; The name arrives as a string object, and upstream strips a leading colon
        ;; before interning — a keyword may be written either way.
        10 (let [s (str (read-element in resolvers))]
             (keyword (if (.startsWith s ":") (subs s 1) s)))

        ;; A bare SubL symbol is executable code, never knowledge, and the `subl`
        ;; namespace is how the translation tells it from a KB constant. A string means
        ;; the CYC package; anything else is an explicit package followed by the name.
        11 (let [head (read-element in resolvers)]
             (if (string? head)
               (symbol "subl" head)
               (symbol "subl" (str (read-element in resolvers)))))

        12 nil

        ;; A count and then that many objects.  The count is the file's own claim about
        ;; itself, so it is read as a count (`read-natural`) rather than trusted: a
        ;; negative one would otherwise read as the empty list and leave the stream
        ;; standing in the middle of an object with nothing said about it.
        13 (let [n (read-natural in resolvers)]
             (apply list (repeatedly n #(read-element in resolvers))))

        14 (let [n (read-natural in resolvers)]
             (vec (repeatedly n #(read-element in resolvers))))

        ;; Raw bytes, one char each — a wide string has its own opcode, so this is
        ;; Latin-1 by construction rather than by assumption.
        15 (String. ^bytes (read-raw-bytes in (read-natural in resolvers)) "ISO-8859-1")

        16 (char (raw-byte in))

        ;; `length` cars and then one tail object. Clojure has no improper list, so it
        ;; reads back as a marker whose head is not a constant — which is exactly what
        ;; the translation drops. None appears in a formula.
        17 (let [n    (read-natural in resolvers)
                 cars (doall (repeatedly n #(read-element in resolvers)))
                 tail (read-element in resolvers)]
             (list :cfasl/dotted (apply list cars) tail))

        18 (unsupported op)

        ;; A count, then that many 8-bit chunks least-significant first — and each chunk
        ;; is a whole CFASL *object*, not a raw byte. That distinction is the one place
        ;; where guessing costs more than a wrong number: a chunk of 128 or more is
        ;; written as an opcode plus a byte, so reading chunks raw consumes one byte
        ;; where the writer wrote two and desynchronizes the rest of the file.
        ;; `cfasl-output-integer` writes them with `cfasl-output`, over the chunks
        ;; `disassemble-integer-to-fixnums` makes by shifting down 8 bits at a time.
        23 (read-bignum in resolvers 1)
        24 (read-bignum in resolvers -1)

        (25 43) (str (read-element in resolvers))

        26 (vec (read-raw-bytes in (read-natural in resolvers)))

        (27 28 29 44 51 90 91 94 95 124 126) (unsupported op)

        30 (resolve-handle resolvers :constant (read-natural in resolvers))
        31 (resolve-handle resolvers :nart (read-natural in resolvers))

        ;; The complete forms carry the id and then the name, and the name is redundant
        ;; against the dump's own constant table — upstream reads and discards it too.
        32 (let [c (resolve-handle resolvers :constant (read-natural in resolvers))]
             (read-element in resolvers)
             c)

        33 (resolve-handle resolvers :assertion (read-natural in resolvers))
        36 (resolve-handle resolvers :deduction (read-natural in resolvers))
        37 (resolve-handle resolvers :kb-hl-support (read-natural in resolvers))
        38 (resolve-handle resolvers :clause-struc (read-natural in resolvers))

        40 (hl-variable (read-natural in resolvers))
        42 (let [v (hl-variable (read-natural in resolvers))]
             (read-element in resolvers)
             v)

        ;; An index into a symbol table the *server* installs at runtime
        ;; (`cfasl-set-common-symbols`); a dump on disk carries no such table, so the
        ;; index is all there is and it reads back as a marker.
        50 (list :cfasl/common-symbol (read-element in resolvers))

        (unsupported op)))))

(defn objects
  "A lazy seq of every object in `in`, to end of stream.

  Holds no more than the object being read, so a 780 MB dump streams."
  [^InputStream in resolvers]
  (lazy-seq
   (let [o (read-object in resolvers)]
     (when-not (= ::eof o)
       (cons o (objects in resolvers))))))
