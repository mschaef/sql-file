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

(ns sql-file.middleware
  "A current database connection for a body of code, opened only when
  something first uses it.

  with-db-connection (and wrap-db-connection, for a Ring-style handler)
  makes a database available to its body without opening a
  connection. The connection is opened the first time
  current-db-connection is called (by a query, a transaction, or
  anything else that needs it), kept for the rest of the body, and
  closed (returned to its pool, for a pooled database) when the body
  ends. A body that never uses the database never opens a connection."
  (:require [clojure.tools.logging :as log]
            [clojure.java.jdbc :as jdbc]))

(def ^:dynamic *db* nil)

;;; A database that hasn't been connected to yet: the db-spec to
;;; connect with, and the connected db-spec (with its :connection)
;;; once it has been.
(deftype ^:private LazyConnection [db-spec connected])

(defn- lazy-connection [db-spec]
  (LazyConnection. db-spec (atom nil)))

(defn- realize!
  "The connected db-spec for lazy, connecting first if need be. If
  connecting fails (say, a pool timeout), nothing is kept, so the next
  use tries again."
  [^LazyConnection lazy]
  (let [connected (.-connected lazy)]
    (locking connected
      (or @connected
          (let [db-spec (.-db-spec lazy)]
            (reset! connected (jdbc/add-connection db-spec (jdbc/get-connection db-spec))))))))

(defn- close!
  "Close lazy's connection, if it was ever opened. A failure to close
  is logged rather than thrown, so it can't hide an exception from the
  body that used the connection."
  [^LazyConnection lazy]
  (let [connected (.-connected lazy)]
    (locking connected
      (when-let [db-spec @connected]
        (reset! connected nil)
        (try
          (.close ^java.sql.Connection (:connection db-spec))
          (catch Exception ex
            (log/error ex "Error closing database connection")))))))

(defn call-with-db-connection
  "Call f with db-connection (a db-spec, such as a pool from
  sql-file.core/open-pool) available as the current database. A
  connection is opened only if f uses it, and is closed when f returns
  or throws. A db-spec that already has an open :connection is used as
  it is, and left open."
  [db-connection f]
  (if (:connection db-connection)
    (binding [*db* db-connection]
      (f))
    (let [lazy (lazy-connection db-connection)]
      (try
        (binding [*db* lazy]
          (f))
        (finally
          (close! lazy))))))

(defmacro with-db-connection [db-connection & body]
  `(call-with-db-connection ~db-connection (fn [] ~@body)))

(defn current-db-connection
  "The current database, as a db-spec with an open :connection,
  connecting first if this is its first use. Fails if there is no
  current database."
  []
  (let [db *db*]
    (cond
      (nil? db) (throw (RuntimeException. "No current database connection for query."))
      (instance? LazyConnection db) (realize! db)
      :else db)))

(defn db []
  (current-db-connection))

(defn wrap-db-connection
  "Ring-style middleware: run each request with db-connection as the
  current database (see call-with-db-connection). Requests that don't
  use the database don't open a connection."
  [app db-connection]
  (fn [req]
    (call-with-db-connection db-connection (fn [] (app req)))))

(defn call-with-db-transaction [fn]
  (jdbc/with-db-transaction [txn (current-db-connection)]
    (binding [*db* txn]
      (fn))))

(defmacro with-db-transaction [& body]
  `(call-with-db-transaction (fn [] ~@body)))
