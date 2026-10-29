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

(ns sql-file.query-middleware-test
  (:use clojure.test
        sql-file.query-middleware)
  (:require [sql-file.middleware :as sql-file-middleware]))

(defn- run-query
  "Run a stand-in query, returning rows, through middleware, as a
  query declared with the given info would be."
  [middleware info rows]
  ((middleware (fn [_args _call-options] rows))
   {}
   {:query {:name "test-query" :info info}}))

(deftest query-modes
  (let [rows [{:a 1} {:a 2}]]
    (testing "Results are returned as they are by default"
      (is (= rows (run-query query-mode-middleware {} rows)))
      (is (= rows (run-query query-mode-middleware {:query-mode :direct} rows))))

    (testing "Row mode returns the first row"
      (is (= {:a 1} (run-query query-mode-middleware {:query-mode :row} rows)))
      (is (nil? (run-query query-mode-middleware {:query-mode :row} []))))

    (testing "Column mode returns the column's values"
      (is (= [1 2] (run-query query-mode-middleware {:query-mode :column} rows)))
      (is (= [] (run-query query-mode-middleware {:query-mode :column} []))))

    (testing "Scalar mode returns the single value"
      (is (= 1 (run-query query-mode-middleware {:query-mode :scalar} [{:a 1}])))
      (is (nil? (run-query query-mode-middleware {:query-mode :scalar} []))))

    (testing "An unknown mode is an error"
      (is (thrown-with-msg? RuntimeException #"Invalid query mode: :bogus for query: test-query"
                            (run-query query-mode-middleware {:query-mode :bogus} rows))))))

(deftest logging-passes-results-through
  (is (= [{:a 1}] (run-query log-query-middleware {} [{:a 1}]))))

(deftest current-connection
  (let [seen (atom nil)
        query (current-connection-middleware
               (fn [_args call-options]
                 (reset! seen (:connection call-options))
                 []))]
    (testing "Queries run on the current connection"
      (binding [sql-file-middleware/*db* :the-current-connection]
        (query {} {}))
      (is (= :the-current-connection @seen)))

    (testing "A connection passed with the call takes precedence"
      (binding [sql-file-middleware/*db* :the-current-connection]
        (query {} {:connection :the-call-connection}))
      (is (= :the-call-connection @seen)))))
