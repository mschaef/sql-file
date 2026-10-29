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

(ns sql-file.middleware-test
  (:use clojure.test
        sql-file.middleware)
  (:require [clojure.java.jdbc :as jdbc]))

(defn- counting-db
  "A db-spec whose data source counts the connections it opens in
  opens. The first failures connection attempts fail."
  ([opens]
   (counting-db opens 0))
  ([opens failures]
   (Class/forName "org.hsqldb.jdbc.JDBCDriver")
   (let [failures (atom failures)]
     {:datasource
      (reify javax.sql.DataSource
        (getConnection [_]
          (if (pos? @failures)
            (do
              (swap! failures dec)
              (throw (java.sql.SQLException. "No connection available")))
            (do
              (swap! opens inc)
              (java.sql.DriverManager/getConnection "jdbc:hsqldb:mem:middleware-test" "SA" "")))))})))

(defn- query-one []
  (jdbc/query (current-db-connection) ["SELECT 1 AS one FROM INFORMATION_SCHEMA.SYSTEM_USERS"]))

(deftest unused-database-opens-no-connection
  (let [opens (atom 0)]
    (is (= :done (with-db-connection (counting-db opens) :done)))
    (is (= 0 @opens))))

(deftest connection-opened-on-first-use
  (let [opens (atom 0)
        conn (with-db-connection (counting-db opens)
               (query-one)
               (query-one)
               (:connection (current-db-connection)))]
    (testing "One connection serves every use in the body"
      (is (= 1 @opens)))
    (testing "The connection is closed when the body ends"
      (is (.isClosed conn)))))

(deftest connection-closed-on-exception
  (let [conn (atom nil)]
    (is (thrown-with-msg? RuntimeException #"body failed"
                          (with-db-connection (counting-db (atom 0))
                            (reset! conn (:connection (current-db-connection)))
                            (throw (RuntimeException. "body failed")))))
    (is (.isClosed @conn))))

(deftest transactions
  (let [opens (atom 0)]
    (with-db-connection (counting-db opens)
      (with-db-transaction
        (is (= [{:one 1}] (query-one))))
      (is (= [{:one 1}] (query-one))))
    (is (= 1 @opens))))

(deftest failed-connection-is-retried
  (let [opens (atom 0)]
    (with-db-connection (counting-db opens 1)
      (is (thrown? java.sql.SQLException (current-db-connection)))
      (is (= [{:one 1}] (query-one))))
    (is (= 1 @opens))))

(deftest connected-spec-is-left-open
  (with-db-connection (counting-db (atom 0))
    (let [outer (current-db-connection)]
      (with-db-connection outer
        (is (identical? outer (current-db-connection))))
      (is (not (.isClosed (:connection outer)))))))

(deftest no-current-database
  (is (thrown-with-msg? RuntimeException #"No current database connection"
                        (current-db-connection))))

(deftest middleware
  (let [opens (atom 0)
        handler (wrap-db-connection (fn [req]
                                      (when (:use-db req)
                                        (query-one))
                                      :ok)
                                    (counting-db opens))]
    (is (= :ok (handler {})))
    (is (= 0 @opens))
    (is (= :ok (handler {:use-db true})))
    (is (= 1 @opens))))
