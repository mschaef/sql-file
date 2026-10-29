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

(ns sql-file.query-cache-test
  (:use clojure.test
        sql-file.query-cache))

(defn- counting-query
  "A stand-in query, through the cache middleware, declared with the
  given info. Each run increments runs and returns [{:n runs-so-far}]."
  [runs info]
  (let [query (query-cache-middleware
               (fn [_args _call-options]
                 [{:n (swap! runs inc)}]))]
    (fn [args]
      (query args {:query {:name "test-query" :info info}}))))

(deftest uncached-without-a-bound-cache
  (let [runs (atom 0)
        query (counting-query runs {:cache-name ["list" :list_id]})]
    (query {:list_id 1})
    (query {:list_id 1})
    (is (= 2 @runs))))

(deftest cached-results
  (let [runs (atom 0)
        query (counting-query runs {:cache-name ["list" :list_id]})]
    (with-query-cache (make-query-cache)
      (testing "A repeated query is answered from the cache"
        (is (= [{:n 1}] (query {:list_id 1})))
        (is (= [{:n 1}] (query {:list_id 1})))
        (is (= 1 @runs)))

      (testing "Different arguments are cached separately"
        (is (= [{:n 2}] (query {:list_id 2})))
        (is (= 2 @runs))))))

(deftest uncached-queries
  (let [runs (atom 0)
        query (counting-query runs {})]
    (with-query-cache (make-query-cache)
      (query {:list_id 1})
      (query {:list_id 1})
      (is (= 2 @runs)))))

(deftest invalidation
  (let [runs (atom 0)
        query (counting-query runs {:cache-name ["list" :list_id]})
        update! (counting-query (atom 0) {:invalidate-cache-names [["list" :list_id]]})]
    (with-query-cache (make-query-cache)
      (query {:list_id 1})
      (query {:list_id 2})

      (testing "A query's invalidate-cache-names clear what's cached under them"
        (update! {:list_id 1})
        (query {:list_id 1})
        (is (= 3 @runs)))

      (testing "Other names stay cached"
        (query {:list_id 2})
        (is (= 3 @runs)))

      (testing "invalidate-cache! clears a name directly"
        (invalidate-cache! ["list" 2])
        (query {:list_id 2})
        (is (= 4 @runs)))

      (testing "clear-cache! clears everything"
        (clear-cache!)
        (query {:list_id 1})
        (query {:list_id 2})
        (is (= 6 @runs))))))

(deftest missing-cache-name-argument
  (let [query (counting-query (atom 0) {:cache-name ["list" :list_id]})]
    (with-query-cache (make-query-cache)
      (is (thrown-with-msg? RuntimeException #"Cannot find query variable :list_id"
                            (query {}))))))

(deftest no-cache-bound
  (testing "Invalidating with no cache bound does nothing"
    (is (nil? (current-query-cache)))
    (is (nil? (invalidate-cache! ["list" 1])))
    (is (nil? (clear-cache!)))))
