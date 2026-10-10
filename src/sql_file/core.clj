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

;;; Install tracking
;;;
;;; sql-file records each installed schema script as a row in
;;; sql_file_installed, keyed by schema id. A numbered ("legacy")
;;; schema [name n] has the id "name-n", and implicitly requires
;;; "name-(n-1)".
;;;
;;; how is 'run' (the script was executed), 'recorded' (recorded under
;;; a replaces directive, or by set-schema-version!, without running),
;;; or 'migrated' (carried over from the older sql_file_schema table).
;;; A 'run' row is written before its script starts, and completed_on
;;; set when it finishes, so a script that failed or was interrupted
;;; partway through can be detected later.
;;;
;;; The older sql_file_schema table (one row per numbered schema, with
;;; its version) is still kept up to date, so that an application can
;;; be rolled back to an older sql-file without re-running scripts.

(defn- now []
  (java.sql.Timestamp. (System/currentTimeMillis)))

(defn- table-exists? [conn table-name]
  (pos? (query-scalar conn [(str "SELECT COUNT(*) FROM information_schema.tables"
                                 " WHERE table_name = ?")
                            (.toUpperCase ^String table-name)])))

(defn legacy-schema-id [schema-name schema-version]
  "The schema id of version schema-version of the numbered schema
schema-name."
  (str schema-name "-" schema-version))

(defn- legacy-schema-version [schema-name schema-id]
  "If schema-id is a version of the numbered schema schema-name, return
the version number, otherwise nil."
  (when-let [[_ version] (re-matches (re-pattern (str (java.util.regex.Pattern/quote schema-name)
                                                      "-(\\d+)"))
                                     schema-id)]
    (Integer/parseInt version)))

(defn- legacy-requires [schema-name schema-version]
  (when (pos? schema-version)
    (legacy-schema-id schema-name (dec schema-version))))

(defn- all-schema-ids [conn]
  (query-column conn ["SELECT schema_id FROM sql_file_installed"]))

(defn- completed-schema-ids [conn]
  (query-column conn [(str "SELECT schema_id FROM sql_file_installed"
                           " WHERE NOT (how = 'run' AND completed_on IS NULL)")]))

(defn installed-schemas [conn]
  "Return the rows of sql_file_installed: one map per installed schema,
with :schema_id, :how, :requires, :replaces, :digest, :started_on and
:completed_on."
  (query-all conn ["SELECT * FROM sql_file_installed ORDER BY schema_id"]))

(defn- insert-installed! [conn row]
  (jdbc/insert! conn :sql_file_installed row))

(defn- mirror-legacy-version! [conn schema-name schema-version]
  "Keep the older sql_file_schema table in step, for rollback to an
older sql-file. sql-file's own schema stays at version 0 there, which
is all an older sql-file knows how to handle."
  (when (and (not= schema-name "sql-file")
             (table-exists? conn "sql_file_schema"))
    (if (some? (query-scalar conn ["SELECT schema_version FROM sql_file_schema WHERE schema_name = ?"
                                   schema-name]))
      (jdbc/update! conn :sql_file_schema
                    {:schema_version schema-version}
                    ["schema_name = ?" schema-name])
      (jdbc/insert! conn :sql_file_schema
                    {:schema_name schema-name
                     :schema_version schema-version}))))

(defn get-schema-version [conn schema-name]
  "Retrieves the current version of a numbered schema within a database
managed by sql-file: the highest version recorded as installed. If
there is no such schema, this function returns nil. If the version
cannot be identified due to an exception an error message is logged
with the stack trace and the function returns nil."
  (try
    (let [versions (keep #(legacy-schema-version schema-name %)
                         (completed-schema-ids conn))]
      (when (seq versions)
        (apply max versions)))
    (catch Exception ex
      (when (log/enabled? :debug)
        (log/error ex "Error while attempting to identify version of schema:" schema-name))
      nil)))

(defn set-schema-version! [conn schema-name req-schema-version]
  "Sets the version of a numbered schema within a database managed by
sql-file, without running any scripts: versions up to and including
req-schema-version are recorded as installed, and any later versions
are removed."
  (let [ids (all-schema-ids conn)
        installed (set ids)
        timestamp (now)]
    (doseq [id ids]
      (when-let [version (legacy-schema-version schema-name id)]
        (when (> version req-schema-version)
          (jdbc/delete! conn :sql_file_installed ["schema_id = ?" id]))))
    (doseq [version (range (inc req-schema-version))]
      (let [id (legacy-schema-id schema-name version)]
        (when-not (installed id)
          (insert-installed! conn {:schema_id id
                                   :how "recorded"
                                   :requires (legacy-requires schema-name version)
                                   :started_on timestamp
                                   :completed_on timestamp}))))
    (mirror-legacy-version! conn schema-name req-schema-version)))

(declare ensure-schema)

(defn- replaced-schemas-present [conn schema replaces]
  "Given the schemas a script declares it replaces, determine whether
they're already present in the database. Returns true if all of them
are present (after bringing any older versions up to the declared
version), false if none are, and throws if only some are, or if any
is present at a later version than declared. (A later version has
changes the replacing script doesn't reproduce.) All of the checks are
made before any replaced schema is brought up to date."
  (let [current (map (fn [[old-name old-version]]
                       [old-name old-version (get-schema-version conn old-name)])
                     replaces)
        present (filter #(some? (nth % 2)) current)]
    (cond
      (empty? present)
      false

      (not= (count present) (count replaces))
      (throw (Exception. (str "Schema " schema " replaces " (vec replaces)
                              ", but only " (vec (map #(vec (take 2 %)) present))
                              " are present in the database.")))

      :else
      (do
        (doseq [[old-name old-version cur-version] current]
          (when (> cur-version old-version)
            (throw (Exception. (str "Schema " schema " replaces " [old-name old-version]
                                    ", but the database has " old-name " at version " cur-version ".")))))
        (doseq [replaced replaces]
          (ensure-schema conn replaced))
        true))))

(defn- install-schema [conn schema]
  "Locate and run the script necessary to install the specified
schema in the target database instance. If the script declares that it
replaces schemas already present in the database, the script is not
run and the schema is just recorded as installed."
  (log/info "Installing schema:" schema)
  (let [[schema-name schema-version] schema
        schema-id (legacy-schema-id schema-name schema-version)
        requires (legacy-requires schema-name schema-version)]
    (try
      (let [script-url (locate-schema-script conn schema-name schema-version)
            script-text (slurp script-url)
            replaces (:replaces (script-directives script-text))]
        (if (replaced-schemas-present conn schema replaces)
          (let [timestamp (now)]
            (log/info "Schema" schema "replaces" replaces
                      "which are already present. Recording it without running" (str script-url))
            (insert-installed! conn {:schema_id schema-id
                                     :how "recorded"
                                     :requires requires
                                     :replaces (clojure.string/join "\n" (map #(apply legacy-schema-id %) replaces))
                                     :started_on timestamp
                                     :completed_on timestamp}))
          (do
            (insert-installed! conn {:schema_id schema-id
                                     :how "run"
                                     :requires requires
                                     :replaces (when (seq replaces)
                                                 (clojure.string/join "\n" (map #(apply legacy-schema-id %) replaces)))
                                     :started_on (now)})
            (run-script conn script-url script-text)
            (jdbc/update! conn :sql_file_installed
                          {:completed_on (now)}
                          ["schema_id = ?" schema-id]))))
      (catch Exception ex
        (throw (Exception. (str "Error installing schema: " schema) ex))))
    (mirror-legacy-version! conn schema-name schema-version)))

;;; sql-file's own tables

(defn- run-internal-script [conn version]
  (let [script-url (clojure.java.io/resource (format "schema-sql-file-%s.sql" version))]
    (run-script conn script-url (slurp script-url))))

(defn- migrate-legacy-schema-rows! [conn]
  "Copy the older sql_file_schema table into sql_file_installed: a row
(name, n) becomes rows name-0 through name-n. Rows already present are
left alone, so this can safely be repeated."
  (let [installed (set (all-schema-ids conn))]
    (doseq [{schema-name :schema_name
             schema-version :schema_version} (query-all conn ["SELECT * FROM sql_file_schema"])]
      (log/info "Migrating installed schema records:" schema-name "versions 0 to" schema-version)
      (doseq [version (range (inc schema-version))]
        (let [id (legacy-schema-id schema-name version)]
          (when-not (installed id)
            (insert-installed! conn {:schema_id id
                                     :how "migrated"
                                     :requires (legacy-requires schema-name version)})))))))

(defn- ensure-sql-file-tables [conn]
  "Install or upgrade sql-file's own tables. A database from an older
sql-file has only sql_file_schema; its rows are copied into
sql_file_installed. The sql-file-1 row is written last, so if this is
interrupted it's simply repeated on the next open."
  (when-not (table-exists? conn "sql_file_schema")
    (log/info "Installing schema: [sql-file 0]")
    (run-internal-script conn 0)
    (jdbc/insert! conn :sql_file_schema {:schema_name "sql-file"
                                         :schema_version 0}))
  (when-not (and (table-exists? conn "sql_file_installed")
                 (some #{"sql-file-1"} (all-schema-ids conn)))
    (let [started-on (now)]
      (when-not (table-exists? conn "sql_file_installed")
        (log/info "Installing schema: [sql-file 1]")
        (run-internal-script conn 1))
      (migrate-legacy-schema-rows! conn)
      (insert-installed! conn {:schema_id "sql-file-1"
                               :how "run"
                               :requires "sql-file-0"
                               :started_on started-on
                               :completed_on (now)}))))

(defn- check-incomplete-installs [conn]
  "Fail if any schema script started but didn't finish. The database
may have been partly changed by it, and running it again could fail
or do damage."
  (when-let [incomplete (seq (query-all conn [(str "SELECT schema_id, started_on FROM sql_file_installed"
                                                   " WHERE how = 'run' AND completed_on IS NULL"
                                                   " ORDER BY started_on")]))]
    (throw (Exception. (str "Schema installation did not complete: "
                            (clojure.string/join ", " (map #(str (:schema_id %) " (started " (:started_on %) ")")
                                                           incomplete))
                            ". The database may have been partly changed. Restore it from a backup,"
                            " or repair it by hand and delete the schema's row from sql_file_installed.")))))

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
  (let [conn (hsqldb-conn desc)]
    (ensure-sql-file-tables conn)
    (check-incomplete-installs conn)
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
