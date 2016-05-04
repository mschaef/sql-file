;; Copyright (c) 2015-2016 Michael Schaeffer
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
            (map (fn [ prefix ]
                   (let [ script-path (format "%s%s" prefix basename)]
                     (log/debug "Checking for script:" script-path)
                     (clojure.java.io/resource script-path)))
                 (conj (get conn :script-prefixes []) "")))
      (throw (Exception. (str "Cannot find resource script: " basename)))))

(defn- schema-install-script [ conn schema ]
  "Locate the schema script to install the given schema name and
version. If there is no such script, throws an exception."
  (let [[ schema-name schema-version ] schema]
    (locate-schema-script conn (format "schema-%s-%s.sql" schema-name schema-version))))

(defn- schema-migrate-script [ conn schema ]
  "Locate the schema migration script for the given schema name and
to-version. If there is no such script, throws an exception. The
migration script is expected to migrate the schema from version n-1 to
version n, where n=to-version."
  (let [ [ schema-name schema-to-version ] schema ]
    (locate-schema-script conn (format "schema-%s-migrate-to-%s.sql" schema-name schema-to-version))))

(defn- run-script [ conn script-url ]
  "Run the database script at the given URL against a specific
database connection."
  (log/debug "Run script" script-url)
  (let [ script-text (slurp script-url) ]
    (do-statements conn (script/sql-statements script-text))))

(defn- safe-run-script [ conn script-url ]
  "Safely run the database script at the given URL against a specific
database connection. Exceptions will be logged and a boolean value
will be returned that indicates whether or not the script execution
was successful."
  (log/debug "Safe run script" script-url)
  (try
    (run-script conn script-url)
    true
    (catch Exception ex
      (log/error ex "Error running script: " script-url)
      false)))

(defn- get-schema-version [ conn schema-name ]
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
        (log/error ex "Error while attempting to identify version of schema: "
                   schema-name)) 
      nil)))

(defn- set-schema-version! [ conn schema-name req-schema-version ]
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
    (safe-run-script conn (schema-install-script conn schema))
    (set-schema-version! conn schema-name schema-version)))

(defn- migrate-schema [ conn schema-name cur-schema-version req-schema-version ]
  "Locate and run the script necessary to migrate the currently installed version
of a database schema to the requested version."
  (log/info "Upgrading schema" schema-name "from version" cur-schema-version "to" req-schema-version)
  (doseq [ from-version (range cur-schema-version req-schema-version )]
    (let [ to-version (+ from-version 1) ]
      (safe-run-script conn (schema-migrate-script conn [ schema-name to-version ]))
      (set-schema-version! conn schema-name to-version))))

(defn- ensure-schema [ conn schema ]
  "Locate and run the scripts necessary to install the specified
schema in the target database instance."
  (log/debug "Ensuring schema:" schema)
  (let [[req-schema-name req-schema-version] schema
        cur-schema-version (get-schema-version conn req-schema-name)]
      (cond
        (nil? cur-schema-version)
        (install-schema conn schema)

        (= cur-schema-version req-schema-version)
        (log/debug "Schema" req-schema-name "version" req-schema-version "confirmed present.")

        (< cur-schema-version req-schema-version)
        (migrate-schema conn req-schema-name cur-schema-version req-schema-version)

        :else
        (throw (Exception. (str "Cannot downgrade schema " req-schema-name " from version " cur-schema-version " to " req-schema-version))))))


(defn- conn-assoc-schema [ conn schema ]
  "Add a sql-file schema to the connection map."
  (assoc conn
    :sql-file-schemas
    (conj (get conn :sql-file-schemas [["sql-file" 0]])
          schema)))

(defn- conn-ensure-schemas [ conn ]
  "Ensure that the sql-file schemas defined within the connection map
are present in the target database."
  (let [ req-schemas (:sql-file-schemas conn)]
    (log/debug "Ensuring requested schemas:" req-schemas)
    (reduce (fn [ conn schema ]
              (ensure-schema conn schema)
              conn)
            conn
            req-schemas)))

;; Public Entry points

(defn open-sql-file [ conn schema ]
  "Open a database file with the given connection map, and ensure that
the specified schema is available within that database."
  (let [ conn (conn-assoc-schema conn schema) ]
    (log/info "Opening sql-file:" conn)
    (conn-ensure-schemas conn)))

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
