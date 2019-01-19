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

(ns sql-file.core
  (:use sql-file.util
        sql-file.sql-util)
  (:require [clojure.tools.logging :as log]
            [clojure.java.jdbc :as jdbc]
            [sql-file.script :as script]))

(defn- locate-schema-script [ conn basename ]
  (or (some identity
            (map #(clojure.java.io/resource (format "%s%s" % basename))
                 (:schema-path conn)))
      (fail "Cannot find resource script: " basename " in search path " (:schema-path conn))))

(defn- schema-install-script [ conn schema ]
  "Locate the schema script to install the given schema name and
version. If there is no such script, throws an exception."
  (let [[ schema-name schema-version ] schema]
    (locate-schema-script conn (format "schema-%s-%s.sql" schema-name schema-version))))

(defn do-statements [ conn stmts ]
  "Execute a sequence of statements against the given DB connection."
  (jdbc/with-db-connection [ cdb conn ]
    (doseq [ stmt stmts ]
      (log/debug "Executing SQL:" (str (:url stmt) "(" (:line stmt) ":" (:column stmt) ")") (:statement stmt))
      (try
        (jdbc/db-do-prepared cdb (:statement stmt))
        (catch Exception ex
          (throw (Exception. (str "Error running statement: " stmt) ex)))))))

(defn- run-script [ conn script-url ]
  "Run the database script at the given URL against a specific
database connection."
  (log/debug "Run script:" script-url)
  (let [ script-text (slurp script-url) ]
    (do-statements conn (map #(assoc % :url script-url) (script/sql-statements script-text)))))

(defn get-schema-version [ conn schema-name ]
  "Retrieves the current version of a schema within a database managed
by sql-file. If there is no such schema, this function returns nil. If
the version cannot be identified due to an exception an error message
is logged with the stack trace and the function returns nil."
  (try
    (query-scalar conn [(str "SELECT schema_version"
                             "  FROM sql_file_schema"
                             " WHERE schema_name=?")
                        schema-name])
    (catch Exception ex
      (when (log/enabled? :debug)
        (log/error ex "Error while attempting to identify version of schema:" schema-name)) 
      nil)))

(defn set-schema-version! [ conn schema-name req-schema-version ]
  "Sets the version of a schema within a database managed by
sql-file."
  (if-let [ cur-schema-version (get-schema-version conn schema-name) ]
    (unless (= cur-schema-version req-schema-version)
      (jdbc/update! conn :sql_file_schema
                    {:schema_version req-schema-version}
                    ["schema_name=?" schema-name]))
    (jdbc/insert! conn :sql_file_schema
                  {:schema_name schema-name
                   :schema_version req-schema-version})))

(defn- install-schema [ conn schema ]
  "Locate and run the script necessary to install the specified
schema in the target database instance."
  (log/info "Installing schema:" schema)
  (let [ [schema-name schema-version ] schema ]
    (try
      (run-script conn (schema-install-script conn schema))
      (catch Exception ex
        (throw (Exception. (str "Error installing schema: " schema) ex))))
    (set-schema-version! conn schema-name schema-version)))

(defn- conn-assoc-schema [ conn schema ]
  "Add a sql-file schema to the connection map."
  (assoc conn
    :sql-file-schemas
    (conj (get conn :sql-file-schemas [])
          schema)))

(defn- ensure-schema [ conn schema ]
  "Locate and run the scripts necessary to install the specified
schema in the target database instance."
  (log/debug "Ensuring schema:" schema)
  (let [conn (conn-assoc-schema conn schema)
        [req-schema-name req-schema-version] schema]
    (loop []
      (let [cur-schema-version (or (get-schema-version conn req-schema-name) -1)]
        (if (= cur-schema-version req-schema-version)
          (log/debug "Schema" schema "confirmed present.")
          (do
            (if (< cur-schema-version req-schema-version)
              (install-schema conn [req-schema-name (+ cur-schema-version 1)])
              (fail "Cannot downgrade schema " req-schema-name " from version " cur-schema-version " to " req-schema-version))
            (recur)))))
    conn))

(defn normalize-schema-path [ conn ]
  (assoc conn :schema-path
         (conj (get conn :schema-path []) "")))

;; Public Entry points

(defn open-sql-file [ conn schema ]
  "Open a database file with the given connection map, and ensure that
the specified schema is available within that database."
  (log/info "Opening sql-file:" conn "with schema:" schema)
  (-> conn
      normalize-schema-path
      (ensure-schema [ "sql-file" 0 ])
      (ensure-schema schema)))

;; HSQLDB Specific

(defn hsqldb-file-conn [ filename ]
  "Construct a connection map for an HSQLDB database with the given
filename and schema."
  {:classname "org.hsqldb.jdbc.JDBCDriver"
   :subprotocol  "hsqldb"
   :subname filename})

(defn hsqldb-memory-conn [ aname ]
  "Construct a connection map for an in-memory HSQLDB database with
the given aname and schema."
  {:classname "org.hsqldb.jdbc.JDBCDriver"
   :subprotocol  "hsqldb"
   :subname (str "mem:" aname)})
