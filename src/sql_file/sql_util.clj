;; Copyright (c) 2015-2021 Michael Schaeffer
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

(ns sql-file.sql-util
  (:require [clojure.tools.logging :as log]
            [clojure.java.jdbc :as jdbc]))

(defn query-all [ db-connection query-spec ]
  (log/debug "query-all:" query-spec)
  (jdbc/query db-connection query-spec))

(defn query-first [ db-connection query-spec ]
  (log/debug "query-first:" query-spec)
  (first (jdbc/query db-connection query-spec)))

(defn scalar-result
  ([ query-result default ]
   (or
    (let [first-row (first query-result)
          row-keys (keys first-row)]
      (when (> (count row-keys) 1)
        (log/debug "Queries used for query-scalar should only return one field per row."))
      (get first-row (first row-keys)))
    default))

  ([ query-result ]
   (scalar-result query-result nil)))

(defn query-scalar
  ([ db-connection query-spec default ]
   (log/debug "query-scalar:" query-spec)
   (scalar-result (jdbc/query db-connection query-spec) default))

  ([ db-connection query-spec ]
   (query-scalar db-connection query-spec nil)))

