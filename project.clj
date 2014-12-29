; Copyright (c) 2014 KSM Technology Partners
;
; Licensed under the Apache License, Version 2.0 (the "License");
; you may not use this file except in compliance with the License.
; You may obtain a copy of the License at
;
;       http://www.apache.org/licenses/LICENSE-2.0
;
; The license is also includes at the root of the project in the file
; LICENSE.
;
; Unless required by applicable law or agreed to in writing, software
; distributed under the License is distributed on an "AS IS" BASIS,
; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
; See the License for the specific language governing permissions and
; limitations under the License.
;
; You must not remove this notice, or any other, from this software.

(defproject com.ksmpartners/sql-file "0.1.0"

  :description "Library for managing data files used by embedded databases."

  :url "http://www.ksmpartners.com/"
  :license {:name "The Apache Software License, Version 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0"}

  :scm {:name "git"
        :url "https://github.com/ksmpartners/sql-file.git"}

  :main sql-file.main
  :aot [sqi-file.main]

  :jvm-opts ["-Djava.util.logging.config.file=logging.properties" ]

  :dependencies [[org.clojure/clojure "1.6.0"]
                 [org.hsqldb/hsqldb "2.3.2"]
                 [org.clojure/java.jdbc "0.3.5"]
                 [org.clojure/tools.logging "0.3.1"]])
