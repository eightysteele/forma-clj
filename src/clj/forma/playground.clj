;; I'll put some stuff on a guide to clojure for the guys in
;; here. Know how to switch namespaces -- know what they mean, and
;; know that you can look up documentation for any function, the way
;; it's called inside of the current namespace. This makes looking up
;; the way a function works TRIVIAL! I think we can do this inside of
;; textmate clojure as well. See the swannodette documentation on
;; github for some nice examples, on getting started.

(ns forma.playground
  (:use cascalog.api
        forma.hadoop
        forma.modis
        forma.reproject)
  (:require [incanter.core :as i]
            [cascalog.ops :as c]
            [clojure.string :as s])
  (:gen-class))

(def small-tiles
  (memory-source-tap [[1 28]
                      [4 33]
                      [4 40]
                      [2 25]
                      [1 25]]))

(def age
  (memory-source-tap [["alice" 28 34 34]
                      ["luanne" 36 5 4]
                      ["alice" 40 34 34]
                      ["david" 25 234 12]
                      ["gary" 28 2 3]
                      ["alice" 33 34 34]
                      ["emily" 25 12 1]
                      ["george" 31 2 2]
                      ["kumar" 27 4 3]]))

(def test-source
  (memory-source-tap [["a" 1] 
                      ["b" 2] 
                      ["a" 3]]))

(defn file-count
  "Prints the total count of files in a given directory to stdout."
  [dir]
  (let [files (all-files dir)]
    (?<- (stdout) [?count]
         (files ?filename ?file)
         (c/count ?count))))

(defn add-floats
  "Returns a float array, for the purpose of stacking on to the end of
  a tuple. If the returned field works as a subquery result, we're in
  business."
  [file]
  (float-array 23 3.2))

(defn get-floats
  "Subquery to test float array serialization."
  [dir]
  (let [files (all-files dir)]
    (<- [?filename ?floats]
        (files ?filename ?file)
        (add-floats ?file :> ?floats)
        (:distinct false))))

(defn files-with-floats
  "Test of float array serialization."
  [dir]
  (let [stuff (get-floats dir)]
    (?<- (stdout) [?filename ?floats ?num]
         (stuff ?filename ?floats)
         (last ?floats :> ?num))))

(def tiles (memory-source-tap (vec valid-tiles)))

(defmapop checker [h-tile] (list (vec (range 50 60))))

(defmapop fives [arg]
  (vector [8 7]))

(defmapop fancyvec [coll indices]
  [(vec (map (vec coll) indices))])

(defmapop fancy [coll indices]
  [(map (vec coll) indices)])

(defn rain-tester []
  (?<- (stdout) [?name ?period ?h-tile ?v-tile ?checked ?mapped]
       (age ?name ?period)
       (small-tiles ?h-tile ?v-tile)
       (checker ?h-tile :> ?checked)
       (fives ?v-tile :> ?fives)
       (fancyvec ?checked ?fives :> ?mapped)
       (identity 1 :> ?_)))

(defn text->num
  "converts a text line of numbers to a float vectors; 
    skips the variable #skip of elements"
  [txtln]
  [(map #(Float. %)
        (drop 1 (s/split txtln #" ")))])

(defn time-cofactors
  "creates a pd x 2 matrix of ones and incremental timeseries"
  [pd]
  (let [ones (i/trans (repeat pd 1))
        ind (i/trans (range 1 (inc pd)))]
    (i/bind-columns ones ind)))

(defn ols-coefficient
  "OLS timeseries regression on a sequence m, with intercept"
  [m pd]
  (let [y-col (i/trans [m])
        X (time-cofactors pd)
        ssX (i/solve (i/mmult (i/trans X) X))]
    [(i/sel (i/mmult ssX (i/trans X) y-col) 1 0)]))

(defn wordcount 
    [path pd]
    (?<- (stdout) [?sum]
        ((hfs-textline path) ?line)
        (text->num ?line :> ?vector)
        (ols-coefficient ?vector pd :> ?sum)))

;; MORE PLAYING WITH GLOB EXPRESSIONS

(defn tiles->globstring
  [& tiles]
  {:pre [(not (some false? (map #(contains? valid-tiles %) tiles)))]}
  (let [hv-seq (interpose "," (for [[th tv] tiles] (format "h%02dv%02d" th tv)))]
    (format "*{%s}*" (apply str hv-seq))))

(defn globfile-test
  [pattern]
  (let [source (globhfs-wholefile pattern)]
    (?<- (stdout) [?filename]
         (source ?filename ?file))))

(defn -main []
  (+ 4 5))
