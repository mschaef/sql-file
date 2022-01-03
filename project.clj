;; Copyright (c) 2015-2019 Michael Schaeffer
;;
;; Licensed as below.
;;
;; Portions Copyright (c) 2014 KSM Technology Partners
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;       http://www.apache.org/licenses/LICENSE-2.0
;;
;; The license is also includes at the root of the project in the file
;; LICENSE.
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.
;;
;; You must not remove this notice, or any other, from this software.

(defproject com.mschaef/sql-file "0.5.0-SNAPSHOT"

  :description "One stop shopping for embedding an HSQLDB database and managing schemas for same."

  :url "https://github.com/mschaef/sql-file"

  :license {:name "The Apache Software License, Version 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0"}

  :scm {:name "git"
        :url "https://github.com/mschaef/sql-file.git"}

  :profiles {:dev
             {:main sql-file.main
              :aot [sql-file.main]}}

  :jvm-opts ["-Djava.util.logging.config.file=logging.properties" ]

  :dependencies [[org.clojure/clojure "1.10.3"]
                 [org.hsqldb/hsqldb "2.6.1"]
                 [org.clojure/java.jdbc "0.7.12"]
                 [org.slf4j/log4j-over-slf4j "1.7.32"]
                 [org.clojure/tools.logging "1.2.3"]
                 [org.clojure/tools.reader "1.3.6"]
                 [hikari-cp "2.13.0"]]


  :deploy-repositories [["releases" {:url "https://repo.clojars.org"
                                     :sign-releases false}]]

  :release-tasks [["vcs" "assert-committed"]
                  ["change" "version" "leiningen.release/bump-version" "release"]
                  ["vcs" "commit"]
                  ["vcs" "tag" "--no-sign"]
                  ["deploy"]
                  ["change" "version" "leiningen.release/bump-version"]
                  ["vcs" "commit"]
                  ["vcs" "push"]])
