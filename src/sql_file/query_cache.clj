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

(ns sql-file.query-cache
  "A cache of query results, as yesql query middleware.

  A query is cached by giving it a cache-name info value: a vector of
  strings and keywords, where each keyword is replaced by the query
  argument of that name. A query's results are cached under that name
  (and the query name and arguments), and another query can clear
  everything cached under a name with invalidate-cache-names:

      -- name: get-items-by-list
      -- info-cache-name: [\"list\" :list_id]
      SELECT * FROM item WHERE list_id = :list_id

      -- name: add-item!
      -- info-invalidate-cache-names: [[\"list\" :list_id]]
      INSERT INTO item (list_id, title) VALUES (:list_id, :title)

  The cache itself is an atom, made by make-query-cache, that queries
  use only while it's bound with with-query-cache (or
  call-with-query-cache). With no cache bound, queries run uncached."
  (:require [clojure.tools.logging :as log]))

(def ^:dynamic ^:private *query-cache* nil)

(defn make-query-cache
  "A new, empty query cache."
  []
  (atom {}))

(defn call-with-query-cache
  "Call f with query-cache as the cache for queries it runs."
  [query-cache f]
  (binding [*query-cache* query-cache]
    (f)))

(defmacro with-query-cache
  "Run body with query-cache as the cache for queries it runs."
  [query-cache & body]
  `(call-with-query-cache ~query-cache (fn [] ~@body)))

(defn current-query-cache
  "The query cache bound for the current thread, or nil."
  []
  *query-cache*)

(def ^:private missing-var-marker (gensym))

(defn- lookup-template-var [args varname]
  (let [binding (get args varname missing-var-marker)]
    (when (= binding missing-var-marker)
      (throw (RuntimeException. (str "Cannot find query variable " varname
                                     " in arguments: " (keys args)))))
    binding))

(defn- compute-cache-name [cache-name-template args]
  (vec
   (map #(cond
           (keyword? %) (lookup-template-var args %)
           (string? %) %
           :else (throw (RuntimeException. (str "Invalid query cache key substitution variable: " %
                                                " in cache name template " cache-name-template))))
        cache-name-template)))

(defn- compute-cache-key [cache-name-template query-name args]
  (conj
   (compute-cache-name cache-name-template args)
   query-name
   args))

(defn clear-cache!
  "Clear everything in the current query cache, if one is bound."
  []
  (when-let [query-cache (current-query-cache)]
    (log/warn "Clearing query cache")
    (reset! query-cache {})))

(defn invalidate-cache!
  "Clear what's cached under cache-name (a vector, like a query's
  cache-name with its arguments filled in) in the current query cache,
  if one is bound."
  [cache-name]
  (when-let [query-cache (current-query-cache)]
    (log/debug "Invalidating cache:" cache-name)
    (swap! query-cache assoc-in cache-name {})))

(defn- cache-query-result! [query-cache cache-value-key value]
  (log/debug "Caching" cache-value-key)
  (swap! query-cache assoc-in cache-value-key value)
  value)

(defn query-cache-middleware
  "Query middleware that caches and invalidates query results in the
  current query cache, according to each query's cache-name and
  invalidate-cache-names info values."
  [query-fn]
  (fn [args call-options]
    (if-let [query-cache (current-query-cache)]
      (let [query-name (get-in call-options [:query :name])]
        (doseq [invalidate-cache-name (get-in call-options [:query :info :invalidate-cache-names] [])]
          (invalidate-cache! (compute-cache-name invalidate-cache-name args)))
        (if-let [cache-name (get-in call-options [:query :info :cache-name] false)]
          (let [cache-key (compute-cache-key cache-name query-name args)]
            (or (get-in @query-cache cache-key)
                (cache-query-result! query-cache cache-key (query-fn args call-options))))
          (query-fn args call-options)))
      (query-fn args call-options))))
