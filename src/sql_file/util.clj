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
