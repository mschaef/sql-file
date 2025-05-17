;; Copyright (c) 2015-2024 Michael Schaeffer
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

(defn query-all
  "Query, returning a sequence of all rows in a given result set."

  ([db-connection query-spec]
   (log/debug "query-all:" query-spec)
   (jdbc/query db-connection query-spec)))

(defn- scalar-row-key
  "Return the key for the value column of a scalar row. (A scalar row is
  a row with only a single column.) Rows with more than one column result
  in an exception being thrown."

  ([row]
   (let [row-keys (keys row)]
     (when (not= (count row-keys) 1)
       (throw
        (Exception. (str "Query must have only one column: "
                         (sort row-keys)))))
     (first row-keys))))

(defn columnar-result
  "Return a sequence consisting of the values from a result set composed
  of scalar (single column) rows."

  ([query-result]
   (if-let [first-row (first query-result)]
     (map (scalar-row-key first-row) query-result)
     [])))

(defn query-column
  "Issue a query for a result set containing a single column and return
  a sequence of those values."

  ([db-connection query-spec]
   (columnar-result (query-all db-connection query-spec))))

(defn query-first
  "Issue a query and return the first row of the result set, or nil if
  the result set is empty."

  ([db-connection query-spec]
   (log/debug "query-first:" query-spec)
   (first (jdbc/query db-connection query-spec))))

(defn scalar-result
  "Return a scalar value from the given query result set, or the default
  value if the result set is empty. This can return nil in the event
  the result set contains a row, but the column value is null."

  ([query-result default]
   (if-let [first-row (first query-result)]
     (get first-row (scalar-row-key first-row))
     default))

  ([query-result]
   (scalar-result query-result nil)))

(defn query-scalar
  "Issue a database query and return the scalar result, if there is
  one. Return the specified default otherwise."

  ([db-connection query-spec default]
   (log/debug "query-scalar:" query-spec)
   (scalar-result (jdbc/query db-connection query-spec) default))

  ([db-connection query-spec]
   (query-scalar db-connection query-spec nil)))

(defn query-scalar-required
  "Issue a database query and return the scalar result if there is
  one. Throws an exception in the event the query does not return a
  result."

  ([db-connection query-spec]
   ;; Queries cannot return keywords, so :missing-row will only be
   ;; returned if the row is missing.
   (let [scalar (query-scalar db-connection query-spec :missing-row)]
     (if (not= scalar :missing-row)
       scalar
       (throw (Exception. (str "No value returned when required by query: " query-spec)))))))
