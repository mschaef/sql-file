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

(defn sql-skip-whitespace [ in ]
  "Skip to the next non-whitespace character in the given input
  stream and return that character"
  (while (let [ch (rdr/peek-char in)]
           (and ch (Character/isWhitespace ch)))
    (rdr/read-char in))
  (rdr/peek-char in))

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

(defn- ensure-whitespace [ buf ]
  "Ensure there's at least a single character of whitespace at the end
  of the given buffer."
  (if (and (> (.length buf) 0)
           (not (Character/isWhitespace (.charAt buf (- (.length buf) 1)))))
    (.append buf \space)
    buf))

(defn- get-input-location [ in ]
  { :line (rdr/get-line-number in) :column (rdr/get-column-number in) })

(defn sql-read-statement [ in ]
  (sql-skip-whitespace in)
  (let [ stmt (get-input-location in) ]
    (loop [stmt-buf (StringBuffer.)]
      (case (rdr/peek-char in) 
        \-
        (do
          (rdr/read-char in)
          (if (optional-char in \-)
            (do
              (sql-skip-comment in)
              (recur stmt-buf))
            (recur (.append stmt-buf \-))))
          
        (nil \;)
        (do
          (rdr/read-char in)
          (if (> (.length stmt-buf) 0)
            (assoc stmt :statement (.toString stmt-buf))
            nil))

        \'
        (recur (.append stmt-buf (sql-read-string in)))

        (let [ ch (rdr/read-char in) ]
          (if (Character/isWhitespace ch)
            (recur (ensure-whitespace stmt-buf))
            (recur (.append stmt-buf ch))))))))

(defn sql-statements [ sql ]
  "Given a sequence of characters corresponding to a SQL script,
return a sequence of the SQL statements contained in that script."
  (let [in (rdr/source-logging-push-back-reader sql)]
    (loop [stmts []]
      (sql-skip-whitespace in)
      (if (nil? (rdr/peek-char in))
        stmts
        (recur (if-let [ stmt (sql-read-statement in)]
                 (conj stmts stmt)
                 stmts))))))

