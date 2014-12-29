(defproject com.ksmpartners/sql-file "0.1.0-SNAPSHOT"

  :description "Library for managing data files used by embedded databases."

  :url "http://www.ksmpartners.com/"
  :license {:name "The Apache Software License, Version 2.0"}

  :main sql-file.main
  :aot [sqi-file.main]

  :jvm-opts ["-Xms1g" "-Xmx1g"
             "-XX:+HeapDumpOnOutOfMemoryError"
             "-Djava.util.logging.config.file=logging.properties" ]

  :dependencies [[org.clojure/clojure "1.6.0"]
                 [org.hsqldb/hsqldb "2.3.2"]
                 [org.clojure/java.jdbc "0.3.5"]
                 [org.clojure/tools.logging "0.3.1"]])
