;; This file isolates all direct interaction with Hadoop, and provides
;; access to any custom schemes used by FORMA. Any new schemes, or
;; low-level additions to our hadoop interaction capabilities should
;; go here, allowing our other components to work at a high level of abstraction.

(ns forma.hadoop.io
  (:use cascalog.api
        [forma.date-time :only (jobtag)]
        [clojure.contrib.math :only (abs)])
  (:import [forma WholeFile]
           [cascading.tuple Fields]
           [cascading.tap TemplateTap SinkMode GlobHfs]
           [org.apache.hadoop.io BytesWritable])
  (:require [cascalog.workflow :as w]))

;; ## Custom File Input
;;
;; Hadoop is optimized for massive files -- enormous website logs,
;; huge bodies of text, lists of friend relations on twitter, that
;; sort of stuff. Hadoop jobs require a defined input format for each
;; job, [as described here](http://goo.gl/YX2Ol). The input format
;; generates a number of [Input Splits](http://goo.gl/0UWKd); these
;; input splits are byte streams, and require a custom [record
;; reader](http://goo.gl/AmVJB) for conversion to key-value pairs. The
;; text record reader, for example, would convert a 64MB chunk of
;; lines into <k, v> pairs of <byte-offset, line> within the actual
;; input split. Most word counting examples ignore this key.
;;
;; The data from each input split goes to a single mapper. From the
;; wonderful [Yahoo!  tutorial](http://goo.gl/u2hOe); "By default, the
;; FileInputFormat and its descendants break a file up into 64 MB
;; chunks (the same size as blocks in HDFS)." By splitting a file on
;; block size, Hadoop allows processing on huge documents to scale
;; linearly. Twice as many bytes flows into twice as many mappers, so
;; doubling the nodes should keep processing time the same.
;;
;; Now, this is all well and good for massive text files; as long as
;; one doesn't split in the middle of a line, the sizes of the chunks
;; are arbitrary. With certain compressed file formats, the picture
;; changes a bit. NASA's MODIS files are compressed using HDF4. A cut
;; in the middle of one of these files would result in meaningless
;; data, as neither half can be decompressed without the other.
;;
;; To deal with this issue, we've written a custom WholeFile input
;; format, to guarantee that input splits will break along file
;; boundaries. The custom WholeFileRecordReader takes one of these
;; input splits and converts it into a <k, v> pair of <filename,
;; file-bytes>. The file's bytes are wrapped in a Hadoop
;; [BytesWritable](http://goo.gl/GVP3e) object. (It should be noted
;; that for small files, this method will be very inefficient, as
;; we're overriding Hadoop's ability to leap across files and stick to
;; its optimal 64MB input split size.)
;;
;;MODIS files range from 24 to 48 MB, so all is well, but a more
;; efficient general solution can be obtained by deriving
;; WholeFileInputFormat from
;; [CombineFileInputFormat](http://goo.gl/awr4T). In this scenario, An
;; input split would still break along file boundaries, but might
;; include multiple files. The record reader would have to be adjusted
;; to produce multiple <k,v> pairs for every input split.
;;
;; As [Cascalog](http://goo.gl/SRmDh) allows us to write cascading
;; jobs (rather than straight MapReduce), we had to define a custom
;; WholeFile [scheme](http://goo.gl/Doggg) to be able to take
;; advantage of [cascading's Tap abstraction](http://goo.gl/RNMLT) for
;; data sources. Once we had WholeFile.java, the clojure wrapper
;; became trivial:

(defn whole-file
  "Custom scheme for dealing with entire files."
  [field-names]
  (WholeFile. (w/fields field-names)))

;; Another helpful feature provided by cascading is the ability to
;; pair a scheme with a special [HFS class](http://goo.gl/JHpNT), that
;; allows access to a number of different file systems. A tap
;; acts like a massive vacuum cleaner. Open up a tap in a directory,
;; and the tap inhales everything inside of it, passing it in as food
;; for whatever scheme it's associated with. HFS can deal with HDFS,
;; local fileystem, and Amazon S3 bucket paths; A path prefix of, respectively,
;; hdfs://, file://, and s3:// forces the proper choice.

(defn hfs-wholefile
  "Creates a tap on HDFS using the wholefile format. Guaranteed not to
   chop files up! Required for unsupported compression formats like
   HDF."
  [path]
  (w/hfs-tap (whole-file Fields/ALL) path))

(defn globhfs-wholefile
  [pattern]
  (GlobHfs. (whole-file Fields/ALL) pattern))

(defn all-files
  "Subquery to return all files in the supplied directory. Files will
  be returned as 2-tuples, formatted as (filename, file) The filename
  is a text object, while the file is encoded as a Hadoop
  BytesWritable."
  [dir]
  (let [source (hfs-wholefile dir)]
    (<- [?filename ?file]
        (source ?filename ?file))))

(defn globbed-files
  "Same as all-files, but takes a pattern."
  [pattern]
  (let [source (globhfs-wholefile pattern)]
    (<- [?filename ?file]
        (source ?filename ?file))))

;; ## Intermediate Taps

(defn template-seqfile
  "Opens up a Cascading [TemplateTap](http://goo.gl/Vsnm5) that sinks
tuples into the supplied directory, using the format specified by
`pathstr`."
  [path pathstr]
  (TemplateTap. (w/hfs-tap (w/sequence-file Fields/ALL) path) pathstr))

;; TODO: -- update documentation, here@
(defn globhfs-seqfile
  [pattern]
  (GlobHfs. (w/sequence-file Fields/ALL) pattern))

;; ## BytesWritable Interaction
;;
;; For schemes that specifically deal with Hadoop BytesWritable
;; objects, we provide the following methods to abstract away the
;; confusing java details. (For example, while a BytesWritable object
;; wraps a byte array, not all of the bytes returned by the getBytes
;; method are valid. As mentioned in the
;; [documentation](http://goo.gl/3qzyc), "The data is only valid
;; between 0 and getLength() - 1.")

(defn hash-str
  "Generates a unique identifier for the supplied BytesWritable
  object. Useful as a filename, when worried about clashes."
  [^BytesWritable bytes]
  (str (abs (.hashCode bytes))))

(defn get-bytes
  "Extracts a byte array from a Hadoop BytesWritable object. As
  mentioned in the [BytesWritable javadoc](http://goo.gl/cjjlD), only
  the first N bytes are valid, where N = `(.getLength byteswritable)`."
  [^BytesWritable bytes]
  (byte-array (.getLength bytes)
              (.getBytes bytes)))


;; TODO: update docs

;; ## Backend Data Processing Queries
;;
;; The following cascalog queries provide us with a way to process raw
;; input files into bins based on various datafields, and read them
;; back out again into a hadoop cluster for later analysis.
;;
;; ### Data to Bucket
;;
;; The idea of this portion of the project is to process all input
;; data into chunks in of data MODIS sinusoidal projection, tagged
;; with metadata to identify the chunk's position and spatial and
;; temporal resolutions. We sink these tuples into Hadoop
;; SequenceFiles binned into a custom directory structure on S3,
;; designed to facilitate easy access to subsets of the data. The
;; data bins are formatted as:
;;
;;     s3n://<dataset>/<s-res>-<t-res>/<tileid>/<jobtag>/seqfile
;;     ex: s3n://ndvi/1000-32/008006/20110226T234402Z/part-00000
;;
;; `s-res` is the spatial resolution of the data, limited to `1000`,
;; `500`, and `250`. `t-res` is the temporal resolution, keyed to the
;; MODIS system of monthly, 16-day, or 8-day periods (`t-res` = `32`,
;; `16` or `8`). `tileid` is the MODIS horizontal and vertical tile
;; location, formatted as `HHHVVV`.
;;
;; As discussed in [this thread](http://goo.gl/jV4ut) on
;; cascading-user, Hadoop can't append to existing
;; SequenceFiles. Rather than read in every file, append, and write
;; back out, we decided to bucket our processed chunks by
;; `jobid`. This is the date and time, down to seconds, at which the
;; run was completed. The first run we complete will be quite large,
;; containing over 100 time periods. Subsequent runs will be monthly,
;; and will be quite small. On a yearly basis, we plan to read in all
;; tuples from every `jobid` directory, and bin them into a new
;;`jobid`. This is to limit the number of small files in the sytem.
;;
;; Note that the other way to combat the no-append issue would have
;; been to sink tuples into a deeper directory structure, based on
;; date. The downside here is that every sequencefile would be 5MB, at
;; 1km resolution. Hadoop becomes efficient when mappers are allowed
;;to deal with splits of 64MB. By keeping our sequencefiles large, we
;;take advantage of this property.


;; TODO: -- check docs for comment on jobtag, and update this
;; section. We need to change this into a chunk-tap. Check the
;; tracker project for how to do this!

(defn modis-seqfile
  "Cascading tap to sink MODIS tuples into a directory structure based
  on dataset, temporal and spatial resolution, tileid, and a custom
  `jobtag`. Makes use of Cascading's
  [TemplateTap](http://goo.gl/txP2a)."
  [out-dir]
  (template-seqfile out-dir
                    (str "%s/%s-%s/%s/" (jobtag) "/")))