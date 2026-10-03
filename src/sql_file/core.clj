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

(ns sql-file.core
  (:use sql-file.sql-util)
  (:require [clojure.tools.logging :as log]
            [clojure.java.jdbc :as jdbc]
            [sql-file.script :as script]
            [hikari-cp.core :as hikari-cp]))

(defn- schema-path [conn]
  (conj (get conn :schema-path []) ""))

(defn- locate-schema-script [conn schema-name schema-version]
  "Locate the schema script to install the given schema name and
version. If there is no such script, throws an exception."
  (let [basename (format "schema-%s-%s.sql" schema-name schema-version)]
    (or (some identity
              (map #(clojure.java.io/resource (format "%s%s" % basename))
                   (schema-path conn)))
        (throw (Exception. (str "Cannot find schema script: " basename " in search path " (schema-path conn)))))))

(defn do-statements [conn stmts]
  "Execute a sequence of statements against the given DB connection."
  (jdbc/with-db-connection [cdb conn]
    (doseq [stmt stmts]
      (log/debug "Executing SQL:" (str (:url stmt) "(" (:line stmt) ":" (:column stmt) ")") (:statement stmt))
      (try
        (jdbc/db-do-prepared cdb (:statement stmt))
        (catch Exception ex
          (throw (Exception. (str "Error running statement: " stmt) ex)))))))

(defn- run-script [conn script-url script-text]
  "Run the database script with the given text (read from the given
URL) against a specific database connection."
  (log/info "Run DB script:" (str script-url))
  (do-statements conn (map #(assoc % :url script-url)
                           (script/sql-statements script-text))))

;;; Script directives
;;;
;;; A schema script can carry directives to sql-file in its leading
;;; comment block, one per line, of the form:
;;;
;;;   -- sql-file: <directive> <arguments...>
;;;
;;; The only directive at the moment is:
;;;
;;;   -- sql-file: replaces <schema-name> <schema-version>
;;;
;;; This declares that the script's schema version reproduces the
;;; given version of another schema. In a database that already has
;;; that other schema, the script is not run; the new schema version
;;; is recorded as present instead. This is how one schema chain is
;;; renamed, split into several, or merged with another.

(def ^:private directive-regex #"^--\s*sql-file:\s*(.*?)\s*$")

(def ^:private replaces-regex #"replaces\s+(\S+)\s+(\d+)")

(defn- leading-comment-lines [script-text]
  (->> (clojure.string/split-lines script-text)
       (map clojure.string/trim)
       (take-while #(or (= "" %) (.startsWith ^String % "--")))))

(defn script-directives [script-text]
  "Parse the sql-file directives in the leading comment block of a
schema script, returning a map. Currently the only key is :replaces,
a sequence of [schema-name schema-version] pairs. Unrecognized
directives are an error, so that a misspelled directive can't be
silently ignored."
  (reduce (fn [directives line]
            (if-let [[_ directive] (re-matches directive-regex line)]
              (if-let [[_ schema-name schema-version] (re-matches replaces-regex directive)]
                (update directives :replaces conj [schema-name (Integer/parseInt schema-version)])
                (throw (Exception. (str "Unrecognized sql-file directive: " line))))
              directives))
          {:replaces []}
          (leading-comment-lines script-text)))

(defn get-schema-version [conn schema-name]
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

(defn set-schema-version! [conn schema-name req-schema-version]
  "Sets the version of a schema within a database managed by
sql-file."
  (if-let [cur-schema-version (get-schema-version conn schema-name)]
    (when (not= cur-schema-version req-schema-version)
      (jdbc/update! conn :sql_file_schema
                    {:schema_version req-schema-version}
                    ["schema_name=?" schema-name]))
    (jdbc/insert! conn :sql_file_schema
                  {:schema_name schema-name
                   :schema_version req-schema-version})))

(declare ensure-schema)

(defn- replaced-schemas-present [conn schema replaces]
  "Given the schemas a script declares it replaces, determine whether
they're already present in the database. Returns true if all of them
are present (after bringing any older versions up to the declared
version), false if none are, and throws if only some are, or if any
is present at a later version than declared. (A later version has
changes the replacing script doesn't reproduce.)"
  (let [present (filter #(get-schema-version conn (first %)) replaces)]
    (cond
      (empty? present)
      false

      (not= (count present) (count replaces))
      (throw (Exception. (str "Schema " schema " replaces " (vec replaces)
                              ", but only " (vec present) " are present in the database.")))

      :else
      (do
        (doseq [[old-name old-version] replaces]
          (let [cur-version (get-schema-version conn old-name)]
            (when (> cur-version old-version)
              (throw (Exception. (str "Schema " schema " replaces " [old-name old-version]
                                      ", but the database has " old-name " at version " cur-version "."))))
            (ensure-schema conn [old-name old-version])))
        true))))

(defn- install-schema [conn schema]
  "Locate and run the script necessary to install the specified
schema in the target database instance. If the script declares that it
replaces schemas already present in the database, the script is not
run and the schema version is just recorded."
  (log/info "Installing schema:" schema)
  (let [[schema-name schema-version] schema]
    (try
      (let [script-url (locate-schema-script conn schema-name schema-version)
            script-text (slurp script-url)
            replaces (:replaces (script-directives script-text))]
        (if (replaced-schemas-present conn schema replaces)
          (log/info "Schema" schema "replaces" replaces
                    "which are already present. Recording it without running" (str script-url))
          (run-script conn script-url script-text)))
      (catch Exception ex
        (throw (Exception. (str "Error installing schema: " schema) ex))))
    (set-schema-version! conn schema-name schema-version)))

;; Public Entry points

(defn hsqldb-conn [desc]
  "Construct a connection map for an HSQLDB database with the given
filename and schema. A name with a \"mem:\" prefix may be used to
request a memory database."
  (cond-> {:classname "org.hsqldb.jdbc.JDBCDriver"
           :subprotocol  "hsqldb"
           :subname (:name desc)}
    (:schema-path desc) (assoc :schema-path (:schema-path desc))))

(defn ensure-schema [conn schema]
  "Locate and run the scripts necessary to install the specified
schema in the target database instance."
  (log/debug "Ensuring schema:" schema)
  (let [[req-schema-name req-schema-version] schema]
    (loop []
      (let [cur-schema-version (or (get-schema-version conn req-schema-name) -1)]
        (if (= cur-schema-version req-schema-version)
          (log/debug "Schema" schema "confirmed present.")
          (do
            (if (< cur-schema-version req-schema-version)
              (install-schema conn [req-schema-name (+ cur-schema-version 1)])
              (throw (Exception. (str "Cannot downgrade schema " req-schema-name " from version " cur-schema-version " to " req-schema-version))))
            (recur)))))
    conn))

(defn checkpoint-defragment [conn]
  (jdbc/db-do-prepared conn "CHECKPOINT DEFRAG"))

(defn backup-to-file-blocking [conn output-path]
  (jdbc/db-do-prepared conn (str "BACKUP DATABASE TO '" output-path "' BLOCKING")))

(defn backup-to-file-online [conn output-path]
  (jdbc/db-do-prepared conn (str "BACKUP DATABASE TO '" output-path "' NOT BLOCKING")))

(defn start-sqltool-shell [conn]
  (.flush *out*)
  (doto (org.hsqldb.cmdline.SqlFile. *in* "stdin" System/out
                                     "UTF-8" true
                                     (java.net.URL. "file:."))
    (.setConnection (:connection conn))
    (.execute)))

(defn open-local [desc]
  (log/info "Opening sql-file:" desc)
  (let [conn (-> (hsqldb-conn desc)
                 (ensure-schema ["sql-file" 0]))]
    (doseq [schema (get desc :schemas [])]
      (ensure-schema conn schema))
    conn))

(defn open-pool [desc]
  (log/info "Opening sql-file (pooled):" desc)
  (let [conn (open-local desc)
        datasource (hikari-cp/make-datasource (merge {:driver-class-name (:classname conn)
                                                      :jdbc-url (str "jdbc:hsqldb:" (:subname conn))
                                                      :maximum-pool-size (get desc :pool-size 4)}
                                                     (get desc :pool {})))]
    (assoc conn :datasource datasource)))

(defn close-pool [pool]
  (hikari-cp/close-datasource (:datasource pool)))

(defn call-with-pool [f desc]
  (let [pool (open-pool desc)]
    (try
      (f pool)
      (finally
        (close-pool pool)))))

(defmacro with-pool [[var desc] & body]
  `(call-with-pool (fn [~var] ~@body) ~desc))
