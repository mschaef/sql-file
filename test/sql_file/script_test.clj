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

(ns sql-file.script-test
  (:use clojure.test)
  (:require [sql-file.script :as script]
            [clojure.tools.reader.reader-types :as rdr]))

(defn- sql-remaining [sql]
  (let [in (rdr/source-logging-push-back-reader sql)
        buf (StringBuffer.)]
    (script/sql-read-string in)
    (while (not (nil? (rdr/peek-char in)))
      (.append buf (rdr/read-char in)))
    (.toString buf)))

(defn- sql-string-literal [sql]
  (script/sql-read-string (rdr/source-logging-push-back-reader sql)))

(deftest sql-read-string
  (testing "Can read SQL strings and produce correct literal tokens"
    (is (= "''" (sql-string-literal "''*")))
    (is (= "'test'" (sql-string-literal "'test'*")))
    (is (= "'-- embedded comment'" (sql-string-literal "'-- embedded comment'*")))
    (is (= "'partial" (sql-string-literal "'partial")))
    (is (= "'quot''d'" (sql-string-literal "'quot''d'*")))
    (is (= "''''''" (sql-string-literal "''''''*"))))

  (testing "Can read SQL strings and produce correct remaining text"
    (is (= "*" (sql-remaining "''*")))
    (is (= "*" (sql-remaining "'test'*")))
    (is (= "*" (sql-remaining "'-- embedded comment'*")))
    (is (= "" (sql-remaining "'partial")))
    (is (= "*" (sql-remaining "'quot''d'*")))
    (is (= "*" (sql-remaining "''''''*")))))

(deftest sql-statements
  (testing "Empty script"
    (is (empty? (script/sql-statements "")))
    (is (empty? (script/sql-statements "   ")))
    (is (empty? (script/sql-statements " \n \n")))
    (is (empty? (script/sql-statements "--comments only\n\n--foo\n"))))

  (testing "Statement delimiter processing"
    (is (= [{:line 1, :column 1, :statement "1"}]
           (script/sql-statements "1;")))
    (is (= [{:line 1, :column 3, :statement "1"}]
           (script/sql-statements "  1;")))
    (is (= [{:line 1, :column 1, :statement "1"} {:line 1, :column 3, :statement "2"}]
           (script/sql-statements "1;2")))
    (is (= [{:line 1, :column 1, :statement "1"} {:line 1, :column 3, :statement "2"}]
           (script/sql-statements "1;2;")))
    (is (= [{:line 1, :column 1, :statement "1"}]
           (script/sql-statements "1;--2;")))
    (is (=  [{:line 1, :column 1, :statement "1"} {:line 1, :column 3, :statement "2';'3"}]
            (script/sql-statements "1;2';'3;"))))

  (testing "newline processing"
    (is (= [{:line 3, :column 1, :statement "1"}]
           (script/sql-statements "\n\n1;")))
    (is (= [{:line 3, :column 1, :statement "1"}]
           (script/sql-statements "-- comment \n -- comment \n1;")))
    (is (= [{:line 1, :column 1, :statement "1"}]
           (script/sql-statements "1;\n;")))
    (is (= [{:line 1, :column 1, :statement "1 2"}]
           (script/sql-statements "1\n2;")))
    (is (= [{:line 1, :column 1, :statement "1 2"}]
           (script/sql-statements "1\n\n2;"))))
  (is (= [{:line 1, :column 1, :statement "1 2"} {:line 2, :column 3, :statement "3 4"}]
         (script/sql-statements "1\n2;3\n4;"))))

(defn- script-first-non-whitespace [script-name]
  (let [text (script/normalize (slurp (clojure.java.io/resource script-name)))
        in (rdr/source-logging-push-back-reader text)]
    (script/sql-skip-whitespace in)
    (rdr/read-char in)))

(deftest sql-skip-whitespace
  (testing "no whitespace to skip"
    (is (= \1 (script-first-non-whitespace "no-whitespace.txt"))))

  (testing "skip leading whitespace"
    (is (= \1 (script-first-non-whitespace "leading-whitespace.txt")))
    (is (= \1 (script-first-non-whitespace "leading-newlines.txt")))))

(defn- test-script-statements [script-name]

  (script/sql-statements
   (script/normalize
    (slurp (clojure.java.io/resource script-name)))))

(deftest script-parser
  (testing "empty script"
    (is (= 0 (count (test-script-statements "empty-script.sql")))))

  (testing "single statement script"
    (is (= [{:line 1, :column 1, :statement "CREATE CACHED TABLE sql_file_schema ( schema_name VARCHAR(32) NOT NULL PRIMARY KEY, schema_version INT NOT NULL )"}]
           (test-script-statements "single-statement.sql"))))

  (testing "multiple statement script"
    (is (= [{:line 1, :column 1, :statement "CREATE CACHED TABLE sql_file_schema ( schema_name VARCHAR(32) NOT NULL PRIMARY KEY, )"}
            {:line 5, :column 1, :statement "CREATE CACHED TABLE sql_file_schema ( schema_version INT NOT NULL )"}]
           (test-script-statements "multi-statement.sql"))))

  (testing "multiple statement script w/whitespace"
    (is (= [{:line 3, :column 1, :statement "CREATE CACHED TABLE sql_file_schema ( schema_name VARCHAR(32) NOT NULL PRIMARY KEY, )"}
            {:line 7, :column 1, :statement "CREATE CACHED TABLE sql_file_schema ( schema_version INT NOT NULL )"}]
           (test-script-statements "multi-statement-whitespace.sql"))))

  (testing "multiple statement script w/comments"
    (is (= [{:line 2, :column 1, :statement "CREATE CACHED TABLE sql_file_schema ( schema_name VARCHAR(32) NOT NULL PRIMARY KEY, )"}
            {:line 6, :column 1, :statement "CREATE CACHED TABLE sql_file_schema ( schema_version INT NOT NULL )"}]
           (test-script-statements "multi-statement-comment.sql")))))
