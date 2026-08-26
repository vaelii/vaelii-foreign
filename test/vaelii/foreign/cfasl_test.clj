(ns vaelii.foreign.cfasl-test
  "Decode CFASL from bytes this file writes by hand.

  Every fixture here is a literal byte vector, which is the point: it states what the
  format *is* independently of any Cyc artifact, so a wrong belief about an encoding
  fails here rather than being confirmed by round-tripping through the same
  misunderstanding.  `vaelii.foreign.units-test` is the other half — a real dump, read
  against the record counts the dump itself states.

  A byte vector is written in hex to match the specification it comes from, and the
  reader's own arithmetic is spelled out in each test's expectation."
  (:require [clojure.test :refer [deftest is testing]]
            [vaelii.foreign.cfasl :as cfasl])
  (:import (java.io ByteArrayInputStream)))

(defn- stream
  "The bytes as a stream.  `unchecked-byte` so a fixture can be written 0xC8 rather
  than -56 — CFASL bytes are unsigned and the spec quotes them that way."
  ^ByteArrayInputStream [bs]
  (ByteArrayInputStream. (byte-array (map unchecked-byte bs))))

(defn- read1
  "The one object `bs` encodes, with `resolvers` (default none)."
  ([bs] (read1 bs nil))
  ([bs resolvers] (cfasl/read-object (stream bs) resolvers)))

;;; ── integers ──────────────────────────────────────────────────────────

(deftest immediate-fixnums-are-the-opcode-minus-128
  ;; Opcodes 0-127 are opcodes; 128-255 carry the fixnums 0-127 inline, which is what
  ;; makes a small non-negative integer — by far the commonest object in a dump — one
  ;; byte instead of two.
  (is (= 0 (read1 [0x80])))
  (is (= 1 (read1 [0x81])))
  (is (= 127 (read1 [0xFF])))
  (testing "128 itself does not fit, so it takes the 8-bit opcode"
    (is (= 128 (read1 [0x00 0x80])))))

(deftest fixed-width-integers-are-little-endian
  (is (= 200 (read1 [0x00 0xC8])) "p-8bit")
  (is (= -5 (read1 [0x01 0x05])) "n-8bit")
  (is (= 193 (read1 [0x02 0xC1 0x00])) "p-16bit, low byte first")
  (is (= 0x0201 (read1 [0x02 0x01 0x02])) "p-16bit: 0x01 is the LOW byte")
  (is (= -513 (read1 [0x03 0x01 0x02])) "n-16bit is the same bytes negated")
  (is (= 0x030201 (read1 [0x04 0x01 0x02 0x03])) "p-24bit")
  (is (= 0x04030201 (read1 [0x06 0x01 0x02 0x03 0x04])) "p-32bit")
  (testing "four bytes unsigned exceeds an int and not a long"
    (is (= 4294967295 (read1 [0x06 0xFF 0xFF 0xFF 0xFF])))))

(deftest a-bignum-is-chunks-as-objects-not-raw-bytes
  ;; The one encoding where guessing costs more than a wrong number. Chunks are 8-bit
  ;; and least-significant first, but each is written with `cfasl-output` — so a chunk
  ;; of 128 or more arrives as an opcode PLUS a byte. A reader taking chunks raw
  ;; consumes one byte where the writer wrote two, and every later object in the file
  ;; decodes to plausible junk.
  (testing "chunks below 128 are immediate fixnums, one byte each"
    ;; 2 chunks: 44, then 1 -> 44 + (1 << 8) = 300
    (is (= 300N (read1 [0x17 0x82 0xAC 0x81]))))
  (testing "a chunk of 128 or more takes two bytes, and the count still says 2"
    ;; chunk0 = 200 written as p-8bit, chunk1 = 1 -> 200 + 256 = 456
    (is (= 456N (read1 [0x17 0x82 0x00 0xC8 0x81]))))
  (testing "the negative opcode is the same chunks"
    (is (= -300N (read1 [0x18 0x82 0xAC 0x81]))))
  (testing "a chunk count of zero is zero"
    (is (= 0N (read1 [0x17 0x80])))))

(deftest a-float-is-a-significand-scaled-by-a-base-2-exponent
  ;; Both are whole objects, which is why a significand routinely arrives as a bignum —
  ;; and why a wrong bignum reading shows up first as an absurd float.
  (is (= 1.5 (read1 [0x08 0x83 0x01 0x01])) "3 * 2^-1")
  (is (= -1.5 (read1 [0x09 0x83 0x01 0x01])) "the negative opcode negates it")
  (is (= 12.0 (read1 [0x08 0x83 0x82])) "3 * 2^2")
  (testing "a bignum significand, which is the case that made the encoding matter"
    ;; significand 300 as a bignum, exponent 0
    (is (= 300.0 (read1 [0x08 0x17 0x82 0xAC 0x81 0x80])))))

;;; ── the rest of the general opcodes ────────────────────────────────────

(deftest nil-strings-lists-and-vectors
  (is (nil? (read1 [0x0C])) "nil has no payload")
  (testing "a string is a length object then that many raw bytes"
    (is (= "abc" (read1 [0x0F 0x83 0x61 0x62 0x63])))
    (is (= "" (read1 [0x0F 0x80]))))
  (testing "a length is an object, so a long string's length is not one byte"
    (is (= (apply str (repeat 200 "x"))
           (read1 (concat [0x0F 0x00 0xC8] (repeat 200 0x78))))))
  (testing "a list is a length then that many objects"
    (is (= '(1 2) (read1 [0x0D 0x82 0x81 0x82])))
    (is (= '() (read1 [0x0D 0x80])))
    (is (seq? (read1 [0x0D 0x82 0x81 0x82]))))
  (testing "nested lists"
    (is (= '(1 (2 3)) (read1 [0x0D 0x82 0x81 0x0D 0x82 0x82 0x83]))))
  (testing "a general vector reads as a vector, keeping it distinct from a list"
    (is (= [1 2] (read1 [0x0E 0x82 0x81 0x82])))))

(deftest keywords-and-subl-symbols
  (testing "a keyword's name arrives as a string object"
    (is (= :foo (read1 [0x0A 0x0F 0x83 0x66 0x6F 0x6F]))))
  (testing "a leading colon is stripped — either spelling is written"
    (is (= :foo (read1 [0x0A 0x0F 0x84 0x3A 0x66 0x6F 0x6F]))))
  (testing "a bare symbol lands in the subl namespace: it is code, never knowledge"
    (is (= 'subl/foo (read1 [0x0B 0x0F 0x83 0x66 0x6F 0x6F])))
    (is (= "subl" (namespace (read1 [0x0B 0x0F 0x83 0x66 0x6F 0x6F]))))))

(deftest characters-byte-vectors-and-guids
  (is (= \A (read1 [0x10 0x41])) "a character is one raw byte")
  (is (= [1 2 3] (read1 [0x1A 0x83 0x01 0x02 0x03])) "a byte vector is raw bytes")
  (testing "both GUID opcodes read their string"
    (is (= "abc" (read1 [0x19 0x0F 0x83 0x61 0x62 0x63])) "legacy, opcode 25")
    (is (= "abc" (read1 [0x2B 0x0F 0x83 0x61 0x62 0x63])) "opcode 43")))

(deftest a-dotted-list-keeps-its-tail-as-a-marker
  ;; Clojure has no improper list, and the head of the marker is not a constant, so a
  ;; formula carrying one is dropped by the translation rather than misread. These are
  ;; Cyc's variable-arity expansion templates, which are SubL machinery either way.
  (is (= '(:cfasl/dotted (1) 2) (read1 [0x11 0x81 0x81 0x82])))
  (is (= '(:cfasl/dotted (1 2) 3) (read1 [0x11 0x82 0x81 0x82 0x83]))))

;;; ── handles ───────────────────────────────────────────────────────────

(deftest a-handle-is-an-opcode-and-a-dump-id
  (testing "with no resolver a handle reads back as its own marker"
    (is (= '(:constant 44) (read1 [0x1E 0xAC])))
    (is (= '(:nart 0) (read1 [0x1F 0x80])))
    (is (= '(:clause-struc 7) (read1 [0x26 0x87])))
    (is (= '(:unresolved-assertion 9) (read1 [0x21 0x89]))))

  (testing "a resolver is what turns an id into a term"
    (is (= 'cyc/Dog (read1 [0x1E 0xAC] {:constant (fn [id] (when (= 44 id) 'cyc/Dog))}))))

  (testing "the id is an object, so a large one is not a single byte"
    (is (= '(:constant 704) (read1 [0x1E 0x02 0xC0 0x02]))))

  (testing "the dump opcodes are the SERVER's, which is where 36/37/38 differ from the
            API client's source / source-def / axiom"
    (is (= :deduction (get cfasl/opcodes 36)))
    (is (= :kb-hl-support (get cfasl/opcodes 37)))
    (is (= :clause-struc (get cfasl/opcodes 38)))
    (is (= :common-symbol (get cfasl/opcodes 50))
        "50 is the server's common-symbol, not the client's special-object")))

(deftest an-hl-variable-is-already-vaeliis-spelling
  ;; Upstream prints one as its prefix char and id, and `?` is that prefix — so a rule's
  ;; literals need no rewriting downstream.
  (is (= '?var0 (read1 [0x28 0x80])))
  (is (= '?var12 (read1 [0x28 0x8C])))
  (is (= '?var12 (read1 [0x2A 0x8C 0x0C])) "the complete form carries and discards more"))

(deftest a-complete-constant-discards-the-redundant-name
  ;; The id and then the name; the dump's own table is where a name comes from.
  (is (= '(:constant 44) (read1 [0x20 0xAC 0x0F 0x83 0x61 0x62 0x63]))))

;;; ── refusing, rather than guessing ────────────────────────────────────

(deftest an-undecoded-opcode-is-an-error-and-not-a-skip
  ;; Nothing frames a CFASL object but its own opcode, so a payload whose width was
  ;; guessed does not fail locally — it desynchronizes the file and everything after
  ;; decodes to junk that still looks like data. Refusing is the only safe answer.
  (is (thrown? clojure.lang.ExceptionInfo (read1 [0x12])) "hashtable")
  (is (thrown? clojure.lang.ExceptionInfo (read1 [0x5A])) "sbhl-directed-link")
  (is (= :cfasl/unsupported-opcode
         (try (read1 [0x12]) (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))
  (testing "an opcode outside the table at all"
    (is (thrown? clojure.lang.ExceptionInfo (read1 [0x63])))))

(deftest eof-is-legal-between-objects-and-not-inside-one
  (is (= ::cfasl/eof (cfasl/read-object (stream []) nil))
      "an ending between objects is how a dump file ends")
  (is (thrown? clojure.lang.ExceptionInfo (read1 [0x0F 0x83 0x61]))
      "a string promising three bytes and giving one is truncation")
  (is (= :cfasl/truncated
         (try (read1 [0x02 0x01])
              (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))
      "so is a 16-bit integer with one byte"))

(deftest a-length-the-file-declares-is-not-a-length-the-reader-trusts
  ;; A dump names its own lengths, so every one of them is somebody else's number.  The
  ;; failure that matters is not a wrong answer but a refusal that never arrives: a
  ;; four-byte length can ask for a gigabyte, and allocating it before reading a byte
  ;; turns six bytes of input into an OutOfMemoryError.  `.github/SECURITY.md` names
  ;; this shape, and what it promises is a clean refusal rather than a heap.
  (letfn [(kind [bs] (try (read1 bs) ::read
                          (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))]
    (testing "a length past the end of the file is truncation, not an allocation"
      ;; 0x40000000 = 1 GiB promised, nothing delivered
      (is (= :cfasl/truncated (kind [0x0F 0x06 0x00 0x00 0x00 0x40])))
      (is (= :cfasl/truncated (kind [0x1A 0x06 0x00 0x00 0x00 0x40])) "byte-vector too"))

    (testing "a negative length is refused rather than read as empty"
      ;; It used to answer `()` for a list, on a stream that is by then desynchronized —
      ;; the one outcome worse than throwing, since nothing downstream can tell.
      (is (= :cfasl/bad-length (kind [0x0F 0x01 0x05])) "string")
      (is (= :cfasl/bad-length (kind [0x0D 0x01 0x05])) "list")
      (is (= :cfasl/bad-length (kind [0x17 0x01 0x05])) "bignum chunk count"))

    (testing "a length that is not an integer at all"
      (is (= :cfasl/bad-length (kind [0x0F 0x0C])) "nil where a count belongs"))

    (testing "a list one element short ends the object, not the stream"
      (is (= :cfasl/truncated (kind [0x0D 0x82 0x81]))
          "two promised, one given — reading `::eof` as an element is the same door"))))

(deftest objects-streams-to-the-end
  (is (= [1 2 "a"] (vec (cfasl/objects (stream [0x81 0x82 0x0F 0x81 0x61]) nil))))
  (is (= [] (vec (cfasl/objects (stream []) nil)))))

(deftest the-opcode-table-is-the-documentation
  ;; The badge counts this table, and a reader that dispatches on an opcode the table
  ;; does not name would be undocumented by construction.
  (is (= 45 (count cfasl/opcodes)))
  (is (every? keyword? (vals cfasl/opcodes)))
  (is (every? #(<= 0 % 127) (keys cfasl/opcodes))
      "128 and up are immediate fixnums, so no opcode may live there"))

(deftest a-deeply-nested-object-is-refused-not-a-stack-overflow
  ;; Nothing frames a CFASL object but its own opcode, so a corrupt file of one-element
  ;; lists (`0x0D 0x81` repeated) nests one frame per two bytes.  Overflowing the stack
  ;; is an `Error` no `catch Exception` around a parse turns into a refusal, so the
  ;; reader bounds its own nesting and refuses past it by name.
  (let [deep (vec (concat (mapcat (fn [_] [0x0D 0x81]) (range 4000)) [0x0C]))]
    (is (= :cfasl/too-deep
           (try (read1 deep) ::read
                (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))
        "a thousands-deep nest is refused, not a StackOverflowError")))

(deftest a-mistyped-number-payload-is-a-named-refusal
  ;; a bignum chunk or a float part that decodes to something that is not a number is
  ;; a desynchronized stream — refused by name, the way a bad length is, rather than a
  ;; ClassCastException out of a cast.
  (letfn [(kind [bs] (try (read1 bs) ::read
                          (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))]
    ;; 0x17 bignum: chunk-count 1, then a `nil` (0x0C) where an integer chunk belongs
    (is (= :cfasl/bad-number (kind [0x17 0x81 0x0C])) "bignum chunk")
    ;; 0x08 float: a `nil` significand
    (is (= :cfasl/bad-number (kind [0x08 0x0C 0x81])) "float significand")))
