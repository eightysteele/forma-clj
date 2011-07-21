(ns forma.hadoop.jobs.timeseries
  (:use cascalog.api
        [forma.utils :only (defjob)]
        [forma.matrix.utils :only (sparse-expander)])
  (:require [cascalog.ops :as c]
            [forma.date-time :as date]
            [forma.hadoop.pail :as pail]
            [forma.utils :as utils]
            [forma.source.modis :as m]
            [forma.hadoop.io :as io]
            [forma.hadoop.predicate :as p]))

(defbufferop [timeseries [missing-val]]
  "Takes in a number of `<t-period, modis-chunk>` tuples,
  sorted by time period, and transposes these into (n = chunk-size)
  4-tuples, formatted as `<pixel-idx, start, end, t-series>`, where
  the `t-series` field is represented by an instance of
  `forma.schema.DoubleArray`.

  Entering chunks should be sorted by `t-period` in ascending
  order. `modis-chunk` tuple fields must be vectors or instances of
  `forma.schema.DoubleArray` or `forma.schema.IntArray`, as dictated
  by the Thriftable interface in `forma.hadoop.io`."
  [tuples]
  (let [[periods [val]] (apply map vector tuples)
        [fp lp] ((juxt first peek) periods)
        missing-struct (io/to-struct (repeat (io/count-vals val) missing-val))
        chunks (sparse-expander missing-struct tuples :start fp)
        tupleize (comp (partial vector fp lp)
                       io/to-struct
                       vector)]
    (->> chunks
         (map io/get-vals)
         (apply map tupleize)
         (map-indexed cons))))

(defn form-tseries
  "Returns a predicate macro aggregator that generates a timeseries,
  given `?chunk`, `?temporal-resolution` and `?date`. Currently only
  functions when `?chunk` is an instance of
  `forma.schema.DoubleArray`."
  [missing-val]
  (<- [?temporal-res ?date ?chunk :> ?pix-idx ?t-start ?t-end ?tseries]
      (date/datetime->period ?temporal-res ?date :> ?tperiod)
      (:sort ?tperiod)
      (timeseries [missing-val] ?tperiod ?chunk :> ?pix-idx ?t-start ?t-end ?tseries)))

(defn extract-tseries
  "Given a source of chunks, this subquery extracts the proper
  position information and outputs new datachunks containing
  timeseries."
  [chunk-source missing-val]
  (let [mk-tseries (form-tseries missing-val)
        series-src (<- [?name ?location ?pix-idx ?series-struct]
                       (chunk-source _ ?chunk)
                       (io/extract-location ?chunk :> ?location)
                       (io/extract-timeseries-data ?chunk :> ?name ?t-res ?date ?datachunk)
                       (mk-tseries ?t-res ?date ?datachunk :> ?pix-idx ?start ?end ?tseries)
                       (io/mk-array-value ?tseries :> ?series-val)
                       (io/timeseries-value ?start ?end ?series-val :> ?series-struct))]
    (<- [?final-chunk]
        (chunk-source _ ?chunk)
        (io/extract-location ?chunk :> ?location)
        (series-src ?name ?location ?pix-idx ?series-struct)
        (io/chunkloc->pixloc ?location ?pix-idx :> ?pix-location)
        (io/massage-ts-chunk ?chunk ?series-struct ?pix-location :> ?final-chunk))))

(def *missing-val* -9999)

(defn tseries-query [pail-path]
  (-> pail-path
      (pail/split-chunk-tap ["ndvi"] ["precl"] ["reli"] ["qual"] ["evi"])
      (extract-tseries *missing-val*)))

(defjob DynamicTimeseries
  "TODO: Process a pattern, here."
  [source-pail-path ts-pail-path]
  (->> (tseries-query source-pail-path)
       (pail/to-pail ts-pail-path)))

;; #### Fire Time Series Processing

(defaggregateop merge-firetuples
  " Aggregates a number of firetuples by adding up the values
  of each `FireTuple` property."
  ([] [0 0 0 0])
  ([state tuple] (map + state (io/extract-fields tuple)))
  ([state] [(apply io/fire-tuple state)]))

;; Special case of `running-sum` for `FireTuple` thrift objects.
(defmapop running-fire-sum
  [start tseries]
  (let [empty (io/fire-tuple 0 0 0 0)]
    (->> tseries
         (utils/running-sum [] empty io/add-fires)
         (io/fire-series start))))

(defn update-chunk
  [chunk t-res data-val]
  (->  chunk
       (io/set-date nil)
       (io/set-temporal-res t-res)
       (io/swap-data data-val)))

(defn aggregate-fires
  "Converts the datestring into a time period based on the supplied
  temporal resolution."
  [src t-res]
  (let [mash (c/juxt #'io/extract-location
                     #'io/extract-date
                     (c/comp merge-firetuples #'io/extract-chunk-value))]
    (<- [?datestring ?location ?tuple]
        (src _ ?chunk)
        (mash ?chunk :> ?location ?date ?tuple)
        (date/beginning t-res ?date :> ?datestring))))

(defn create-fire-series
  "Aggregates fires into timeseries."
  [src t-res start end]
  (let [[start end]     (map (partial date/datetime->period t-res) [start end])
        length          (inc (- end start))
        mk-fire-tseries (p/vals->sparsevec start length (io/fire-tuple 0 0 0 0))
        series-src      (aggregate-fires src t-res)
        query (<- [?location ?fire-series]
                  (series-src ?datestring ?location ?tuple)
                  (date/datetime->period t-res ?datestring :> ?tperiod)
                  (mk-fire-tseries ?tperiod ?tuple :> _ ?tseries)
                  ((c/comp #'io/mk-data-value
                           running-fire-sum) start ?tseries :> ?fire-series))]
    (<- [?final-chunk]
        (src _ ?chunk)
        (io/extract-location ?chunk :> ?location)
        (query ?location ?fire-series)
        (update-chunk ?chunk t-res ?fire-series :> ?final-chunk))))

(defn fire-query
  [source-pail-path t-res start end]
  (-> source-pail-path
      (pail/split-chunk-tap ["fire"])
      (create-fire-series t-res start end)))

(defjob FireTimeseries
  "TODO: Note that currently, as of july 21st, we have fires data
through July 9th or so. So, our ending date should by 2011-06-01."
  ([source-pail-path ts-pail-path]
     (FireTimeseries-main source-pail-path ts-pail-path "32" "2011-06-01"))
  ([source-pail-path ts-pail-path t-res end]
     (-> source-pail-path
         (fire-query t-res "2000-11-01" end)
         (pail/to-pail ts-pail-path))))
