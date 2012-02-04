(ns forma.postprocess.select_test
  (:use [forma.postprocess.select] :reload)
  (:use [midje sweet cascalog]
        [cascalog.api]
        [clojure.test]
        [midje.cascalog]
        [forma.matrix.utils :only (transpose)]
        [forma.utils :only (positions)]
        [forma.date-time :only (period->datetime datetime->period)]
        [forma.trends.data :only (ndvi rain reli)])
  (:require [incanter.core :as i]
            [forma.testing :as t]
            [cascalog.ops :as c]))

(def sample-output-map
  [{:cntry       "IDN"
    :admin       23456
    :lat         45
    :lon         90
    :pixel       5
    :prob-series [0.1 0.2 0.4 0.7 0.9]}
   {:cntry       "IDN"
    :admin       23456
    :lat         46
    :lon         91
    :pixel       6
    :prob-series [0.1 0.1 0.1 0.1 0.1]}
   {:cntry       "IDN"
    :admin       23456
    :lat         45.2
    :lon         90.1
    :pixel       7
    :prob-series [0.1 0.2 0.4 0.7 0.9]}
   {:cntry       "MYS"
    :admin       12345
    :lat         32
    :lon         89
    :pixel       5
    :prob-series [0.1 0.2 0.4 0.7 0.9]}])

;; TODO: Move the next function to date-time namespace, maybe, talk
;; about this.

;; NOTE ON relative periods
;; (period->datetime "16" (+ 693 134)) => "2005-12-19"

;; (+ 693 134) => 827 defines the final period in the training data
;; set for 16-day resolution.  This period, then, defines the *first*
;; element in the probability series.  That is, the first probability
;; in the series indicates the likelihood of deforestation during the
;; training period.

(defn end-training
  [res]
  (cond (= res "32") (datetime->period "32" "2005-12-01")
        (= res "16") (datetime->period "16" "2005-12-19")))

(defmapcatop grab-sig-pixels
  "return a vector of three-tuples, with the country iso code, the
  index of the period with the alert (> 0.5), and the probability of
  the alert.  If the probability of clearing never exceeds the
  threshold, then a stand-in three-tuple is returned."
  [res out-m]
  (let [series (:prob-series out-m)
        idx (positions #(> % 0.5) series)
        offset (end-training res)]
    (if (empty? idx)
      [["nil" 0 0]]
      (vec (transpose
            [(repeat (count idx) (:cntry out-m))
             (map (partial + offset) idx)
             (map (partial nth series) idx)])))))

(deftest agg-probs
  "test the aggregation by country and time period to simulate the
final, final step of forma, which is the country time series of
clearing activity."
  (fact
   (let [agg-pix (<- [?cntry ?pd ?tot-prob]
                     (sample-output-map ?m)
                     (grab-sig-pixels "16" ?m :> ?cntry ?pd ?prob)
                     (c/sum ?prob :> ?tot-prob))]
     agg-pix => (produces [["IDN" 830 1.4]
                           ["IDN" 831 1.8]
                           ["MYS" 830 0.7]
                           ["MYS" 831 0.9]
                           ["nil" 0 0]]))))
