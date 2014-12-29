; Copyright (c) KSM Technology Partners.
; All rights reserved.
;
; The use and distribution terms for this software are covered by the
; Apache License, version 2.0 (http://opensource.org/licenses/Apache-2.0)
; which can be found in the file LICENSE at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

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
