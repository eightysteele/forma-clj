(defproject forma "0.1.0-SNAPSHOT"
  :description "[FORMA](http://goo.gl/4YTBw) gone Functional."
  :source-path "src/clj"
  :java-source-path "src/jvm"
  :warn-on-reflection true
  :marginalia {:javascript ["mathjax/MathJax.js"]}
  :javac-options {:debug "true" :fork "true"}
  :repositories {"conjars" "http://conjars.org/repo/"
                 "sonatype" "http://oss.sonatype.org/content/repositories/releases"}
  :dependencies [[org.clojure/clojure "1.2.0"]
                 [org.clojure/clojure-contrib "1.2.0"]
                 [incanter "1.2.3" :exclusions [swank-clojure]]
                 [cascalog "1.7.0"]
                 [clj-time "0.3.0"]
                 [backtype/cascading-thrift "0.1.0"]
                 [org.clojars.sritchie09/gdal-java-native "1.8.0"]]
  :dev-dependencies [[org.apache.hadoop/hadoop-core "0.20.2-dev"]
                     [swank-clojure "1.4.0-SNAPSHOT"]
                     [clojure-source "1.2.0"]
                     [marginalia "0.5.0"]
                     [midje "1.1"]]
  :namespaces [forma.source.static])
