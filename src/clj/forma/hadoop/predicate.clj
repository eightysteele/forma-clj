(ns forma.hadoop.predicate
  (:use cascalog.api
        clojure.contrib.java-utils
        [forma.matrix.utils :only (sparse-expander matrix-of)]
        [forma.source.modis :only (pixels-at-res)])
  (:require [clojure.string :as s]
            [forma.hadoop.io :as io]
            [cascalog.ops :as c]
            [cascalog.vars :as v])
  (:import [org.apache.hadoop.mapred JobConf]
           [cascalog Util]))

;; ### Cascalog Helpers

(defn swap-syms
  "Returns a vector of dynamic variable outputs from a cascalog
  generator, with `in-syms` swapped for `out-syms`."
  [gen in-syms out-syms]
  (replace (zipmap in-syms out-syms)
           (get-out-fields gen)))

;; ### Generators

(defn lazy-generator
  "Returns a cascalog generator on the supplied sequence of
  tuples. `lazy-generator` serializes each item in the lazy sequence
  into a sequencefile located at the supplied temporary directory, and
  returns a tap into its guts.

I recommend wrapping queries that use this tap with
`cascalog.io/with-fs-tmp`; for example,

    (with-fs-tmp [_ tmp-dir]
      (let [lazy-tap (pixel-generator tmp-dir lazy-seq)]
      (?<- (stdout)
           [?field1 ?field2 ... etc]
           (lazy-tap ?field1 ?field2)
           ...)))"
  [tmp-path lazy-seq]
  {:pre [(coll? (first lazy-seq))]}
  (let [tap (hfs-seqfile tmp-path)]
    (with-open [collector (.openForWrite tap (JobConf.))]
      (doseq [item lazy-seq]
        (.add collector (Util/coerceToTuple item))))
    tap))

(defn pixel-generator
  "Returns a cascalog generator that produces every pixel combination
  for the supplied sequence of tiles, given the supplied
  resolution. `pixel-generator` stages each tuple into a sequence file
  located at `tmp-dir`. See `forma.hadoop.predicate/lazy-generator`
  for usage advice."
  [tmp-path res tileseq]
  (lazy-generator tmp-path
                  (for [[h v] tileseq
                        s (range (pixels-at-res res))
                        l (range (pixels-at-res res))]
                    [h v s l])))

;; ### Operations

;; #### Defmapops

(defn add-fields
  "Adds an arbitrary number of fields to tuples in a cascalog query."
  [& fields]
  (vec fields))

(defmapop
  ^{:doc "Splits textlines using the supplied regex."}
  [mangle [re]]
  [line]
  (s/split line re))

(defn liberate
  "Takes a line with an index as the first value and numbers as the
  rest, and converts it into a 2-tuple formatted as `[idx, row-vals]`,
  where `row-vals` are sealed inside an instance of
  `forma.schema.IntArray`.

  Example usage:

    (liberate \"1 12 13 14 15\")
    ;=> [1 #<IntArray IntArray(ints:[12, 13, 14, 15])>"
  [line]
  (let [[idx & row-vals] (map #(Integer. %)
                              (s/split line #" "))]
    [idx (io/int-struct row-vals)]))

(defmapop
  ^{:doc "Converts nested clojure vectors into an array of the
  supplied type. For example:

    (window->array [Integer/TYPE] ?chunk :> ?int-chunk)

  flattens the chunk and returns an integer array."}
  [window->array [type]]
  [window]
  [(into-array type (flatten window))])

(defmapop
  ^{:doc "Converts a window of nested clojure vectors into an Thrift
  struct object designed to hold numbers of the supplied
  type. Supported types are `:int` and `:double`. For example:

    (window->struct [:int] ?chunk :> ?int-chunk)

  flattens the chunk and returns an instance of
  `forma.schema.IntArray`."}
  [window->struct [type]]
  [window]
  (let [wrap (case type
                   :double io/double-struct
                   :int io/int-struct)]
    [(-> window flatten wrap)]))

;; #### Defmapcatops

(defmapcatop
  ^{:doc "splits a sequence of values into nested 2-tuples formatted
  as `<idx, val>`. Indexing is zero based."}
  index
  [xs]
  (map-indexed vector xs))

;; #### Aggregators

(defbufferop
  ^{:doc "Returns a string representation of the tuples input to this
  buffer. Useful for testing!"}
  tuples->string
  [tuples]
  [(apply str (map str tuples))])

(defbufferop
  ^{:doc "Receives 2-tuple pairs of the form `<idx, val>`, inserts each
  `val` into a sparse vector at the corresponding `idx`. The `idx` of
  the first tuple will be treated as the zero value. The first tuple
  will `missing-val` will be substituted for any missing value."}
  [sparse-expansion [start length missing-val]]
  [tuples]
  [[(sparse-expander missing-val
                     tuples
                     :start start
                     :length length)]])

(defmacro defpredsummer
  "Generates cascalog defaggregateops for counting items that satisfy
  some custom predicate. Defaggregateops don't allow anonymous
  functions, so we went this route instead."
  [name vals pred]
  `(defaggregateop ~name
     ([] 0)
     ([count# ~@vals] (if (~pred ~@vals)
                      (inc count#)
                      count#))
     ([count#] [count#])))

(defpredsummer [filtered-count [limit]]
  [val] #(> % limit))

(defpredsummer [bi-filtered-count [lim1 lim2]]
  [val1 val2] #(and (> %1 lim1)
                    (> %2 lim2)))

(defpredsummer full-count
  [val] identity)

;; ### Predicate Macros

(defmapcatop struct-index
  [idx-0 struct]
  (map-indexed (fn [idx val]
                 [(+ idx idx-0) val])
               (io/get-vals struct)))

(def
  ^{:doc "Takes a source of textlines representing rows of a gridded
  dataset (with indices prepended onto each row), and generates a
  source of `row`, `col` and `val`."}
  break
  (<- [?line :> ?row ?col ?val]
      (liberate ?line :> ?row ?row-struct)
      (struct-index 0 ?row-struct :> ?col ?val)))

(defn vals->sparsevec
  "Returns an aggregating predicate macro that stitches values into a
  sparse vector with all `?val`s at `?idx`, and `empty-val` at all
  other places. Lines are divided into `splits` based on that input
  parameter. Currently, we require that `splits` divide evenly into
  `final-length`."
  ([empty-val]
     (vals->sparsevec 0 nil empty-val))
  ([length empty-val]
     (vals->sparsevec 0 length empty-val))
  ([start length empty-val]
     (<- [?idx ?val :> ?split-idx ?split-vec]
         (:sort ?idx)
         (- ?idx start :> ?start-idx)
         ((c/juxt #'mod #'quot) ?start-idx length :> ?sub-idx ?split-idx)
         (sparse-expansion [0 length empty-val] ?sub-idx ?val :> ?split-vec))))

;; ### Special Functions

(defn sparse-windower
  "Aggregates cascalog generator values up into multidimensional
  windows of nested vectors of `split-length` based on the supplied
  vector of spatial variables. Any missing values will be filled with
  `sparse-val`. This is useful for aggregating spatial data into
  windows or chunks suitable for storage, or to scan across for
  nearest neighbors.

  `in-syms` should be a vector containing the name of each spatial
  dimension variable, plus the name of the value to be aggregated. For
  example, with some source `s` that would generate

    (s ?mod-h ?mod-v ?col ?row ?val)

  one could wrap s like so:

    (sparse-windower s [\"?sample\" \"?line\"] 3000 \"?val\" 0)

  to produce a new generator that would create

    (s ?mod-h ?mod-v ?window-col ?window-row ?window)"
  [gen in-syms dim-vec val sparse-val]
  (let [[outpos outval] (v/gen-non-nullable-vars 2)
        dim-vec (if (coll? dim-vec) dim-vec [dim-vec])]
    (reduce #(%2 %1)
            gen
            (for [[dim inpos] (map-indexed vector in-syms)
                  :let [length (try (dim-vec dim)
                                    (catch Exception e (last dim-vec)))
                        empty (matrix-of sparse-val dim length)
                        aggr (vals->sparsevec length empty)]]
              (fn [src]
                (construct (swap-syms gen [inpos val] [outpos outval])
                           [[src :>> (get-out-fields gen)]
                            [aggr inpos val :> outpos outval]]))))))
