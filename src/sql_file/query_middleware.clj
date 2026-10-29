;; Copyright (c) 2015-2026 Michael Schaeffer
;;
;; Licensed as below.
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

(ns sql-file.query-middleware
  "yesql query middleware for databases managed by sql-file.

  Queries may declare how their results are returned with a
  query-mode info value in the query file:

      -- name: count-points
      -- info-query-mode: :scalar
      SELECT COUNT(*) FROM point

  The modes are :direct (the default: the rows as returned), :scalar
  (the single value of a single-column result, or nil), :row (the
  first row, or nil) and :column (the values of a single-column
  result)."
  (:require [clojure.tools.logging :as log]
            [yesql.middleware :as middleware]
            [sql-file.middleware :as sql-file-middleware]
            [sql-file.sql-util :as sql-util]))

(defn log-query-middleware
  "Log the start and end (with elapsed time) of each query, at debug
  level."
  [query-fn]
  (fn [args call-options]
    (let [begin-t (System/currentTimeMillis)
          query-name (get-in call-options [:query :name])]
      (log/debug [:begin query-name args])
      (let [result (query-fn args call-options)]
        (log/debug [:end query-name (- (System/currentTimeMillis) begin-t)])
        result))))

(defn query-mode-middleware
  "Shape each query's result according to its query-mode info value."
  [query-fn]
  (fn [args call-options]
    (let [result (query-fn args call-options)
          query-mode (get-in call-options [:query :info :query-mode] :direct)]
      (case query-mode
        :direct result
        :scalar (sql-util/scalar-result result)
        :row (first result)
        :column (sql-util/columnar-result result)
        (throw (RuntimeException.
                (str "Invalid query mode: " query-mode
                     " for query: " (get-in call-options [:query :name]))))))))

(def current-connection-middleware
  "Run each query on the current connection (see sql-file.middleware),
  unless the call supplies a :connection of its own."
  (middleware/set-connection sql-file-middleware/current-db-connection))

(def query-middleware
  "The standard middleware for queries run against the current
  connection: connection, query mode, and logging. Pass it as the
  :middleware option of yesql's defqueries."
  (comp current-connection-middleware
        query-mode-middleware
        log-query-middleware))
