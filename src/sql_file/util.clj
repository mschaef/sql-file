; Copyright (c) 2014 KSM Technology Partners
;
; Licensed under the Apache License, Version 2.0 (the "License");
; you may not use this file except in compliance with the License.
; You may obtain a copy of the License at
;
;       http://www.apache.org/licenses/LICENSE-2.0
;
; The license is also includes at the root of the project in the file
; LICENSE.
;
; Unless required by applicable law or agreed to in writing, software
; distributed under the License is distributed on an "AS IS" BASIS,
; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
; See the License for the specific language governing permissions and
; limitations under the License.
;
; You must not remove this notice, or any other, from this software.

(ns sql-file.util
  (:require [clojure.tools.logging :as log]
            [clojure.java.jdbc :as jdbc]))

(defn query-all [ db-connection query-spec ]
  (log/debug "query-all:" query-spec)
  (jdbc/query db-connection query-spec))

(defn query-first [ db-connection query-spec ]
  (log/debug "query-first:" query-spec)
  (first (jdbc/query db-connection query-spec)))

(defn query-scalar [ db-connection query-spec ]
  (log/debug "query-scalar:" query-spec)
  (let [first-row (first (jdbc/query db-connection query-spec))
        row-keys (keys first-row)]
    (when (> (count row-keys) 1)
      (log/warn "Queries used for query-scalar should only return one field per row:" query-spec))
    (get first-row (first row-keys))))

(defn do-statements [ conn stmts ]
  "Execute a sequence of statements against the given DB connection."
  (jdbc/with-db-connection [ cdb conn ]
    (doseq [ stmt stmts ]
      (log/debug "db-do-prepared:" stmt)
      (jdbc/db-do-prepared cdb stmt))))

(defmacro unless [ guard & body ]
  "Evaluate the body of the form, only if the guard evaluates to
false. The return value is the return value of the last body form, or
nil if the body is left unevaluated due to the guard."
  `(when (not ~guard)
     ~@body))
