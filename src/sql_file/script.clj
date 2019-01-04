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

(ns sql-file.script
  "Utility functions for processing SQL scripts."
  (:require [clojure.tools.reader.reader-types :as rdr]))

(defn at-sql-comment? [ sql ]
  "Determine if a sequence of characters is located at a SQL
comment (identified by two leading hypens')"
  (and (= \- (first sql)) (= \- (second sql))))

(defn sql-skip-comment [ in ]
  "Skip a SQL comment, returning a the sequence of characters
following the comment text."
  (loop []
    (let [ch (rdr/peek-char in)]
      (if (and ch (not (= ch \newline)))
        (do
          (rdr/read-char in)
          (recur))))))

(defn- optional-char [ in ch ]
  (and (= (rdr/peek-char in) ch)
       (rdr/read-char in)))

(defn sql-read-string [ in ]
  "Read a literal string from a SQL script, returning a two element
vector composed of the remaining SQL text and the text of the
literal. If the SQL script ends within a literal, there's no guarantee
that the returned literal is well-formed."
  (loop [lit-buf (.append (StringBuffer.) (rdr/read-char in))]
    (if (nil? (rdr/peek-char in))
      (.toString lit-buf)
      (let [ ch (rdr/read-char in)]
        (if (= \' ch)
          (if (optional-char in \')
            (recur (.append lit-buf "''"))
            (.toString (.append lit-buf ch)))
          (recur (.append lit-buf ch)))))))

(defn sql-statements [ sql ]
  "Given a sequence of characters corresponding to a SQL script,
return a sequence of the SQL statements contained in that script."
  (let [in (rdr/source-logging-push-back-reader sql)]
    (remove #(= 0 (.length %))
            (loop [stmt-buf (StringBuffer.)
                   stmts []]
              (if (nil? (rdr/peek-char in))
                (conj stmts (.trim (.toString stmt-buf)))
                (let [ ch (rdr/peek-char in) ]
                  (case ch
                    \'
                    (recur (.append stmt-buf (sql-read-string in)) stmts)
                    
                    \;
                    (do
                      (rdr/read-char in)
                      (recur (StringBuffer.) (conj stmts (.trim (.toString stmt-buf)))))

                    \-
                    (do
                      (rdr/read-char in)
                      (if (optional-char in \-)
                        (do
                          (sql-skip-comment in)
                          (recur (StringBuffer.) (conj stmts (.trim (.toString stmt-buf)))))
                        (recur (.append stmt-buf \-) stmts)))
                    
                    \newline
                    (do
                      (rdr/read-char in)
                      (recur (.append stmt-buf \space) stmts))
                    
                    (recur (.append stmt-buf (rdr/read-char in)) stmts))))))))

