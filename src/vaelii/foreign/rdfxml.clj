(ns vaelii.foreign.rdfxml
  "Read an RDF/XML document as the same triples `vaelii.foreign.turtle` yields.

  RDF/XML is a **third syntax for the graph the other two already carry**, so this is a
  second lexer and not a second reader: it emits `{:s :p :o}` maps with terms spelled
  exactly as `turtle` spells them, and `vaelii.foreign.rdf`'s OWL projection is reached
  without changing a line of it.  Nothing here knows what `rdfs:subClassOf` means.

  **Why it has to exist.**  Turtle is what a person writes and N-Triples is what a dump
  ships, but RDF/XML is what an *OWL ontology* is published as — it was the only
  serialization OWL 1 defined, so the tooling that generates ontologies still emits it.
  OpenCyc's OWL export, DOLCE, and the OWL product of every OBO Foundry ontology are all
  RDF/XML.  A reader that stopped at Turtle could not open any of them.

  **The syntax is striped**, alternating node and property elements:

  ```xml
  <rdf:Description rdf:about=\"#Dog\">            <!-- node:     the subject -->
    <rdfs:subClassOf rdf:resource=\"#Mammal\"/>    <!-- property: one triple  -->
    <rdfs:label xml:lang=\"en\">dog</rdfs:label>
  </rdf:Description>
  ```

  and nearly everything else in the grammar abbreviates that shape.  A node element's
  tag is an `rdf:type` triple unless it is `rdf:Description`; an attribute that is not
  syntax is a triple with a literal object; `rdf:parseType` switches the content between
  three readings; `rdf:li` counts.  The awkward part of RDF/XML is not the striping but
  how many ways it offers to write one triple, and this reader's job is that every one
  of them lands as the same data.

  **Parsed with the JDK's StAX**, pulled one top-level node element at a time, so a
  252 MB ontology streams rather than building a DOM.  DTD support is on because RDF/XML
  routinely declares internal entities for its namespaces (`<!ENTITY owl \"http://…\">`,
  then `&owl;Class`) and a parser rejecting those could not read the files it exists for;
  **external** entities are off, which is what keeps that safe.

  **What is approximated.**  `rdf:parseType=\"Literal\"` should hold the exclusive XML
  canonicalization of its content.  This writes a faithful but not canonical
  serialization — attribute order and namespace declarations may differ from C14N — so
  an XMLLiteral compares equal to itself and not necessarily to another parser's.  No
  ontology reasons over one, and `vaelii.foreign.rdf` keeps it as a string."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [vaelii.foreign.turtle :as ttl])
  (:import (java.io Reader)
           (javax.xml.stream XMLInputFactory XMLStreamConstants XMLStreamReader)))

;;; ── the vocabulary the syntax itself spends ───────────────────────────

(def ^:private rdf ttl/rdf-ns)
(def ^:private xml-ns "http://www.w3.org/XML/1998/namespace")

(def ^:private core-syntax
  "The names that *are* the syntax.  None may be a node element, a property element or a
  property attribute — using one is the commonest way a hand-written RDF/XML file is
  wrong, so it is an error rather than a triple."
  #{"RDF" "ID" "about" "parseType" "resource" "nodeID" "datatype"})

(def ^:private old-terms
  "Withdrawn from RDF in 2004.  `rdf:aboutEach` distributed a property over a container's
  members and `rdf:bagID` reified into a named bag; both were dropped, so a document
  still using one means something this reader cannot honour and it says so."
  #{"aboutEach" "aboutEachPrefix" "bagID"})

(defn- rdf-name
  "The local name of `uri` when it is in the RDF namespace, else nil."
  [^String uri]
  (when (and uri (str/starts-with? uri rdf))
    (subs uri (count rdf))))

(defn- syntax-error!
  "Refuse the document, saying where.  A negative-syntax test is a document that *must*
  fail, so failing precisely is as much the contract as parsing is."
  [^XMLStreamReader r msg]
  (throw (ex-info (str "RDF/XML: " msg)
                  {:type   :rdfxml/syntax
                   :line   (some-> r .getLocation .getLineNumber)
                   :column (some-> r .getLocation .getColumnNumber)})))

(def ^:private max-nesting
  "How deep elements may nest before the document is refused, counted in elements — so
  half this many levels of the node/property striping.

  The grammar is read by two functions calling each other, so nesting is paid for in
  stack, and a document nested past what the stack holds ends the *process* with a
  `StackOverflowError` — an `Error`, which is not the `ex-info` a caller catches around a
  parse and not something it can report as a bad document.  A limit is what keeps a
  refusal a refusal.

  256 elements is far past anything published: the deepest nesting in the whole W3C
  RDF/XML suite is 7 elements, and a Collection or a long property list is read by a loop
  rather than by descending.  It is also well under where the stack gives out — measured
  at ~660 elements with a 512k stack, the smallest a JVM is run with, and ~3,200 with the
  default."
  256)

(defn- deeper!
  "One element further in, refused past `max-nesting`."
  [^XMLStreamReader r ^long depth]
  (when (>= depth max-nesting)
    (syntax-error! r (str "elements nested deeper than " max-nesting)))
  (inc depth))

(def ^:private ncname
  "An XML NCName — a Name with no colon in it.  `rdf:ID` and `rdf:nodeID` take one, and
  neither is a free-form string: an ID becomes a fragment identifier and a nodeID becomes
  a blank node label, so `333-555-666` and `_:b` have to be refused rather than quietly
  turned into an IRI nobody can dereference."
  #"(?U)^[\p{L}_][\p{L}\p{Nd}_.·\-]*$")

(defn- check-ncname!
  [^XMLStreamReader r ^String what ^String v]
  (when-not (re-matches ncname v)
    (throw (ex-info (str "RDF/XML: " what " " (pr-str v) " is not an XML NCName")
                    {:type :rdfxml/syntax
                     :line (some-> r .getLocation .getLineNumber)})))
  v)

(defn- check-name!
  "`uri` is admissible in `role`.  The three roles exclude slightly different sets — a
  property element may be `rdf:li` and the others may not, a node element may be
  `rdf:Description` and the others may not — which is the whole of RDF/XML's name
  discipline."
  [r ^String uri role]
  (when (str/blank? uri)
    (syntax-error! r (str "an unqualified name cannot be a " (name role))))
  (when-let [n (rdf-name uri)]
    (when (or (core-syntax n)
              (old-terms n)
              (and (not= :property-element role) (= n "li"))
              (and (not= :node role) (= n "Description")))
      (syntax-error! r (str "rdf:" n " cannot be a " (name role))))))

;;; ── reader state ──────────────────────────────────────────────────────

(defn- ->state
  "What has to survive from one element to the next: the triple queue, the blank-node
  counter, and the `rdf:ID`s already spent.  The reader and the base in force are not in
  here — both are parameters of the functions that need them, and the base is scoped by
  the element it is declared on rather than by the document."
  []
  {:out (volatile! []) :counter (volatile! 0) :ids (volatile! #{})})

(defn- emit! [{:keys [out]} s p o] (vswap! out conj {:s s :p p :o o}))

(defn- fresh-bnode! [{:keys [counter]}] (symbol "bnode" (str "x" (vswap! counter inc))))

(defn- named-bnode
  "A blank node written `rdf:nodeID`.  Prefixed so a document's own labels can never
  collide with the ones this reader mints for `parseType=\"Resource\"` and friends."
  [id] (symbol "bnode" (str "n" id)))

(defn- rdf-iri [n] (ttl/iri (str rdf n)))
(def ^:private rdf-type-iri (rdf-iri "type"))

;;; ── XML plumbing ──────────────────────────────────────────────────────

(defn- attr-uri
  "The full name of attribute `i`.  An unprefixed attribute is *not* in the default
  namespace — XML says so — and is therefore never in the RDF namespace."
  ^String [^XMLStreamReader r ^long i]
  (let [ns (.getAttributeNamespace r (int i))]
    (str (when-not (str/blank? ns) ns) (.getAttributeLocalName r (int i)))))

(defn- attrs
  "The current element's attributes as `{:attrs {uri value} :base b :lang l}`.  `xml:base`
  and `xml:lang` come out separately because they scope over the subtree rather than
  describing the element; `:lang` is `nil` when absent and `\"\"` when explicitly
  cleared, which are different things."
  [^XMLStreamReader r]
  (loop [i 0, m {}, base nil, lang nil]
    (if (= i (.getAttributeCount r))
      {:attrs m :base base :lang lang}
      (let [ns  (.getAttributeNamespace r (int i))
            loc (.getAttributeLocalName r (int i))
            v   (.getAttributeValue r (int i))]
        (cond
          (and (= ns xml-ns) (= loc "base")) (recur (inc i) m v lang)
          (and (= ns xml-ns) (= loc "lang")) (recur (inc i) m base v)
          (= ns xml-ns)                      (recur (inc i) m base lang)
          ;; XML reserves every name beginning "xml" to itself, prefixed or not, and says
          ;; a processor must ignore the ones it does not know.  Reading one as a property
          ;; would invent a triple out of markup housekeeping.
          (and (str/blank? ns) (str/starts-with? (str/lower-case loc) "xml"))
          (recur (inc i) m base lang)
          :else (recur (inc i) (assoc m (attr-uri r i) v) base lang))))))

(defn- scope
  "The `[base lang]` in force inside an element, given what is in force outside it and
  what the element's own `xml:base` / `xml:lang` say.  `xml:lang=\"\"` *clears* an
  inherited language rather than being an absent attribute, which is the whole reason
  `attrs` keeps the two apart."
  [x base lang]
  [(if (:base x) (ttl/resolve-iri base (:base x)) base)
   (cond (nil? (:lang x)) lang
         (= "" (:lang x)) nil
         :else            (:lang x))])

(defn- element-uri ^String [^XMLStreamReader r]
  (let [ns (.getNamespaceURI r)]
    (str (when-not (str/blank? ns) ns) (.getLocalName r))))

(defn- skip-to-tag!
  "Advance to the next start or end tag, discarding comments, processing instructions and
  whitespace.  Non-whitespace text where an element belongs is a striping error.  Returns
  `:start`, `:end`, or nil at the end of the document."
  [^XMLStreamReader r]
  (loop []
    (when (.hasNext r)
      (let [e (.next r)]
        (condp = e
          XMLStreamConstants/START_ELEMENT :start
          XMLStreamConstants/END_ELEMENT   :end
          XMLStreamConstants/END_DOCUMENT  nil
          XMLStreamConstants/CHARACTERS
          (if (str/blank? (.getText r))
            (recur)
            (syntax-error! r (str "text where an element was expected: "
                                  (pr-str (str/trim (.getText r))))))
          (recur))))))

;;; ── serializing an XML literal ────────────────────────────────────────

(defn- xml-escape [^String s in-attr?]
  (cond-> (-> s (str/replace "&" "&amp;") (str/replace "<" "&lt;") (str/replace ">" "&gt;"))
    in-attr? (str/replace "\"" "&quot;")))

(defn- qualified
  "`prefix:local` for the element the reader is on, or `local` when unprefixed."
  [^XMLStreamReader r]
  (let [p (.getPrefix r)]
    (str (when-not (str/blank? p) (str p ":")) (.getLocalName r))))

(defn- read-xml-literal!
  "The content of `rdf:parseType=\"Literal\"`, serialized.  Entered on the property
  element's start tag and left on its end tag.  See the namespace docstring for why this
  is a serialization rather than a canonicalization."
  [^XMLStreamReader r]
  (let [sb (StringBuilder.)]
    (loop [depth 0]
      (let [e (.next r)]
        (condp = e
          XMLStreamConstants/START_ELEMENT
          (do (.append sb (str "<" (qualified r)))
              (dotimes [i (.getNamespaceCount r)]
                (let [p (.getNamespacePrefix r (int i))]
                  (.append sb (str " xmlns" (when p (str ":" p)) "=\""
                                   (xml-escape (.getNamespaceURI r (int i)) true) "\""))))
              (dotimes [i (.getAttributeCount r)]
                (let [p (.getAttributePrefix r (int i))]
                  (.append sb (str " " (when-not (str/blank? p) (str p ":"))
                                   (.getAttributeLocalName r (int i)) "=\""
                                   (xml-escape (.getAttributeValue r (int i)) true) "\""))))
              (.append sb ">")
              (recur (inc depth)))

          XMLStreamConstants/END_ELEMENT
          (if (zero? depth)
            (str sb)
            (do (.append sb (str "</" (qualified r) ">")) (recur (dec depth))))

          XMLStreamConstants/CHARACTERS (do (.append sb (xml-escape (.getText r) false))
                                            (recur depth))
          XMLStreamConstants/CDATA      (do (.append sb (xml-escape (.getText r) false))
                                            (recur depth))
          XMLStreamConstants/END_DOCUMENT (syntax-error! r "document ended inside an XML literal")
          (recur depth))))))

;;; ── the grammar ───────────────────────────────────────────────────────
;;;
;;; `node-element!` and `property-element!` are mutually recursive: a property's object
;;; may be a node, and a node's properties are properties.  That is the striping, and two
;;; functions calling each other is the most direct statement of it.

(declare property-element!)

(defn- subject-of
  "A node element's subject, from whichever of `rdf:about`, `rdf:ID` and `rdf:nodeID` it
  carries — at most one, and a fresh blank node when it carries none.

  `rdf:ID` is *not* an abbreviation of `rdf:about`: it also declares the name unique
  within the base, so the same `rdf:ID` twice is an error where the same `rdf:about`
  twice is simply two descriptions of one resource."
  [st ^XMLStreamReader r a base]
  (let [about  (a (str rdf "about"))
        id     (a (str rdf "ID"))
        nodeid (a (str rdf "nodeID"))]
    (when (> (count (remove nil? [about id nodeid])) 1)
      (syntax-error! r "a node element takes at most one of rdf:about, rdf:ID, rdf:nodeID"))
    (cond
      about  (ttl/iri (ttl/resolve-iri base about))
      id     (let [iri (ttl/resolve-iri base (str "#" (check-ncname! r "rdf:ID" id)))]
               (when (contains? @(:ids st) iri)
                 (syntax-error! r (str "rdf:ID " (pr-str id) " is used twice")))
               (vswap! (:ids st) conj iri)
               (ttl/iri iri))
      nodeid (named-bnode (check-ncname! r "rdf:nodeID" nodeid))
      :else  (fresh-bnode! st))))

(defn- property-attributes!
  "The triples an element's non-syntax attributes stand for, about `subj`.

  `rdf:type` is the one whose value is an IRI rather than a literal — it is the attribute
  form of the type a node element's own tag would have given."
  [st ^XMLStreamReader r a subj base lang]
  (doseq [[^String uri v] a]
    (let [n (rdf-name uri)]
      (cond
        (and n (core-syntax n)) nil                    ; syntax; the caller has it
        (and n (old-terms n))   (syntax-error! r (str "rdf:" n " was withdrawn from RDF"))
        (= uri (str rdf "type")) (emit! st subj rdf-type-iri (ttl/iri (ttl/resolve-iri base v)))
        :else (do (check-name! r uri :property-attribute)
                  (emit! st subj (ttl/iri uri) (ttl/literal v nil lang)))))))

(defn- node-element!
  "One node element, entered on its start tag and left on its end tag.  Returns its
  subject, which is what an enclosing property element needs.

  `depth` is how many elements this one is inside, and `deeper!` is what refuses a
  document that nests past what the stack holds."
  [st ^XMLStreamReader r base lang depth]
  (let [uri (element-uri r)
        _   (check-name! r uri :node)
        x   (attrs r)
        [base lang] (scope x base lang)
        subj (subject-of st r (:attrs x) base)]
    (when-not (= uri (str rdf "Description"))
      (emit! st subj rdf-type-iri (ttl/iri uri)))
    (property-attributes! st r (:attrs x) subj base lang)
    (loop [n 1]
      (case (skip-to-tag! r)
        :start (recur (long (property-element! st r subj base lang n (deeper! r depth))))
        :end   subj
        nil    (syntax-error! r "document ended inside a node element")))))

(defn- collection!
  "`rdf:parseType=\"Collection\"` — the node elements inside become an `rdf:List`, the
  same structure Turtle writes `( a b c )`.  `depth` is the members' own, since the
  collection is a parse type rather than an element."
  [st ^XMLStreamReader r base lang depth]
  (let [nodes (loop [acc []]
                (case (skip-to-tag! r)
                  :start (recur (conj acc (node-element! st r base lang depth)))
                  :end   acc
                  nil    (syntax-error! r "document ended inside a Collection")))]
    (if (empty? nodes)
      (rdf-iri "nil")
      (let [cells (mapv (fn [_] (fresh-bnode! st)) nodes)]
        (dorun (map (fn [cell item nxt]
                      (emit! st cell (rdf-iri "first") item)
                      (emit! st cell (rdf-iri "rest") (or nxt (rdf-iri "nil"))))
                    cells nodes (concat (rest cells) [nil])))
        (first cells)))))

(defn- text-content!
  "The rest of a property element's text, leaving on its end tag.  Only reached once the
  caller has established the content is text and not an element."
  [^XMLStreamReader r]
  (let [sb (StringBuilder.)]
    (loop []
      (let [e (.next r)]
        (condp = e
          XMLStreamConstants/END_ELEMENT (str sb)
          XMLStreamConstants/CHARACTERS  (do (.append sb (.getText r)) (recur))
          XMLStreamConstants/CDATA       (do (.append sb (.getText r)) (recur))
          XMLStreamConstants/START_ELEMENT
          (syntax-error! r "an element inside a property element that already has text")
          XMLStreamConstants/END_DOCUMENT
          (syntax-error! r "document ended inside a property element")
          (recur))))))

(defn- reify!
  "`rdf:ID` on a *property* element names the statement rather than the object, so the
  statement is restated as an `rdf:Statement` under that name.  Rare in the wild and
  required by the syntax, which is why it is here at all.

  The name is held to the same NCName rule a node element's `rdf:ID` is: it becomes a
  fragment identifier either way, and `333-555-666` is not one."
  [st ^XMLStreamReader r id s p o base]
  (let [iri (ttl/resolve-iri base (str "#" (check-ncname! r "rdf:ID" id)))]
    (when (contains? @(:ids st) iri)
      (syntax-error! r (str "rdf:ID " (pr-str id) " is used twice")))
    (vswap! (:ids st) conj iri)
    (let [n (ttl/iri iri)]
      (emit! st n rdf-type-iri (rdf-iri "Statement"))
      (emit! st n (rdf-iri "subject") s)
      (emit! st n (rdf-iri "predicate") p)
      (emit! st n (rdf-iri "object") o))))

(defn- property-element!
  "One property element of `subj`, entered on its start tag and left on its end tag.
  Returns the next `rdf:li` counter, since `rdf:li` numbers within its parent node.
  `depth` is how many elements this one is inside — see `node-element!`."
  [st ^XMLStreamReader r subj base lang li depth]
  (let [uri  (element-uri r)
        _    (check-name! r uri :property-element)
        x    (attrs r)
        a    (:attrs x)
        [base lang] (scope x base lang)
        li?  (= uri (str rdf "li"))
        pred (ttl/iri (if li? (str rdf "_" li) uri))
        ptype    (a (str rdf "parseType"))
        resource (a (str rdf "resource"))
        nodeid   (a (str rdf "nodeID"))
        datatype (a (str rdf "datatype"))
        stmt-id  (a (str rdf "ID"))
        plain    (into {} (remove (fn [[k _]] (let [n (rdf-name k)] (and n (core-syntax n)))) a))
        object
        (cond
          ptype
          (do (when (or resource nodeid datatype)
                (syntax-error! r "rdf:parseType with rdf:resource, rdf:nodeID or rdf:datatype"))
              (case ptype
                "Resource"   (let [b (fresh-bnode! st)]
                               (loop [n 1]
                                 (case (skip-to-tag! r)
                                   :start (recur (long (property-element!
                                                        st r b base lang n (deeper! r depth))))
                                   :end   b
                                   nil (syntax-error! r "document ended inside parseType=Resource"))))
                "Collection" (collection! st r base lang (deeper! r depth))
                ;; "Literal", and per the spec any unrecognised value, is an XML literal
                (ttl/literal (read-xml-literal! r) (rdf-iri "XMLLiteral") nil)))

          (and resource nodeid)
          (syntax-error! r "a property element takes at most one of rdf:resource, rdf:nodeID")

          (or resource nodeid)
          (let [o (if resource (ttl/iri (ttl/resolve-iri base resource)) (named-bnode nodeid))]
            (property-attributes! st r plain o base lang)
            (when-not (= :end (skip-to-tag! r))
              (syntax-error! r "content in a property element that already names its object"))
            o)

          (seq plain)
          ;; attributes but no rdf:resource: they describe a fresh blank node, which is the
          ;; abbreviation for `<p><rdf:Description …/></p>`
          (let [b (fresh-bnode! st)]
            (property-attributes! st r plain b base lang)
            (when-not (= :end (skip-to-tag! r))
              (syntax-error! r "content in a property element that already has attributes"))
            b)

          datatype
          ;; `xml:lang` in scope is deliberately not passed on: RDF has no literal that
          ;; carries a datatype and a language tag at once, and the datatype is the one
          ;; the document asked for
          (ttl/literal (let [e (.next r)]
                         (condp = e
                           XMLStreamConstants/END_ELEMENT ""
                           XMLStreamConstants/CHARACTERS  (str (.getText r) (text-content! r))
                           XMLStreamConstants/CDATA       (str (.getText r) (text-content! r))
                           (syntax-error! r "a typed literal's content must be text")))
                       (ttl/iri (ttl/resolve-iri base datatype))
                       nil)

          :else
          ;; the general case: one nested node element, or text.  Which it is is not known
          ;; until the next event, so both come out of one lookahead.
          (let [e (loop []
                    (let [ev (.next r)]
                      (condp = ev
                        XMLStreamConstants/START_ELEMENT :start
                        XMLStreamConstants/END_ELEMENT   :end
                        XMLStreamConstants/CHARACTERS    (if (str/blank? (.getText r)) (recur) :text)
                        XMLStreamConstants/CDATA         :text
                        XMLStreamConstants/END_DOCUMENT
                        (syntax-error! r "document ended inside a property element")
                        (recur))))]
            (case e
              :start (let [s (node-element! st r base lang (deeper! r depth))]
                       (when-not (= :end (skip-to-tag! r))
                         (syntax-error! r "a property element holds at most one node element"))
                       s)
              :end   (ttl/literal "" nil lang)
              :text  (ttl/literal (str (.getText r) (text-content! r)) nil lang))))]
    (emit! st subj pred object)
    (when stmt-id (reify! st r stmt-id subj pred object base))
    (if li? (inc li) li)))

;;; ── entry points ──────────────────────────────────────────────────────

(defn- make-factory
  "A StAX factory configured for RDF/XML: namespace-aware, coalescing (so a text run is
  one event), internal entities honoured, external entities refused."
  ^XMLInputFactory []
  (doto (XMLInputFactory/newInstance)
    (.setProperty XMLInputFactory/IS_NAMESPACE_AWARE true)
    (.setProperty XMLInputFactory/IS_COALESCING true)
    ;; internal entity declarations are ordinary in RDF/XML and have to work; external
    ;; ones are how an XML parser becomes a file-read primitive, and no document this
    ;; reads needs them
    (.setProperty XMLInputFactory/SUPPORT_DTD true)
    (.setProperty XMLInputFactory/IS_SUPPORTING_EXTERNAL_ENTITIES false)))

(defn triples
  "Every triple `rdr` holds, as a lazy seq of `{:s :p :o}` maps — the same shape
  `vaelii.foreign.turtle/triples` returns, so a caller need not know which syntax it got.

  One *top-level node element* is parsed per pull, which is what keeps a large ontology
  streaming: the queue holds one class's description, not the document.

  `opts` may carry `:base`, for a document whose own `xml:base` is absent and whose
  relative IRIs are meant to resolve against where it was fetched from."
  ([rdr] (triples rdr {}))
  ([^Reader rdr opts]
   (let [^XMLStreamReader r (.createXMLStreamReader (make-factory) rdr)
         st   (->state)
         out  (:out st)
         ;; `rdf:RDF` wraps a list of node elements, but a document whose root *is* a node
         ;; element is legal too — the tests call that rdf-element-not-mandatory — so the
         ;; root is consumed as a wrapper only when it is rdf:RDF, and parsed as content
         ;; otherwise.  `more?` is what tells the pump which case it is in.
         [base lang more?]
         (if (= :start (skip-to-tag! r))
           (let [x (attrs r)
                 [b l] (scope x (:base opts) nil)]
             (if (= (element-uri r) (str rdf "RDF"))
               ;; the wrapper's own `xml:base` *and* `xml:lang` scope the whole
               ;; document — an ontology header's `xml:lang` tags every plain
               ;; literal below it, and dropping it would hand the language
               ;; filter untagged literals in a tagged document
               [b l true]
               (do (node-element! st r b l 0) [b l false])))
           [(:base opts) nil false])
         more? (volatile! more?)]
     ((fn step []
        (lazy-seq
         (loop []
           (if (seq @out)
             (let [t (first @out)]
               (vswap! out subvec 1)
               (cons t (step)))
             (when @more?
               (case (skip-to-tag! r)
                 :start (do (node-element! st r base lang 0) (recur))
                 (do (vreset! more? false) nil)))))))))))

(defn with-triples
  "Open the RDF/XML file at `path`, call `(f triples)` on a lazy seq of its triples, and
  close the file after.  `.gz` is decompressed on the way through — the OpenCyc OWL
  export ships that way and is a tenth the size for it."
  ([path f] (with-triples path f {}))
  ([path f opts]
   (with-open [in (io/input-stream path)]
     (let [in (if (str/ends-with? (str/lower-case (str path)) ".gz")
                (java.util.zip.GZIPInputStream. in)
                in)]
       (with-open [r (io/reader in)]
         (f (triples r opts)))))))
