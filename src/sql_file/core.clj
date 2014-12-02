(ns sql-file.core
  (:use sql-file.util)
  (:require [clojure.tools.logging :as log]
            [clojure.java.jdbc :as jdbc]
            [sql-file.script :as script]))

(defn- locate-resource-script [ resource-name ]
  "Locates the resource with the given name, either returning a URL to
the resource if it's found or throwing an exception if not."
  (or (clojure.java.io/resource resource-name)
      (throw (Exception. (str "Cannot find resource script: " resource-name)))))

;;; TODO: Add a baikal/ -like prefix that allows schema files to be confined to directories.

(defn schema-install-script [ schema-name schema-version ]
  "Locate the schema script to install the given schema name and
version. If there is no such script, throws an exception."
  (locate-resource-script
   (format "schema-%s-%s.sql" schema-name schema-version)))

(defn schema-migrate-script [ schema-name schema-to-version ]
  "Locate the schema migration script for the given schema name and
to-version. If there is no such script, throws an exception. The
migration script is expected to migrate the schema from version n-1 to
version n, where n=to-version."
  (locate-resource-script
   (format "schema-%s-migrate-to-%s.sql" schema-name schema-to-version)))

(defn run-script [ conn script-url ]
  "Run the database script at the given URL against a specific
database connection."
  (log/info "Run script" script-url)
  (let [ script-text (slurp script-url) ]
    (do-statements conn (script/sql-statements script-text))))

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
        (log/error ex "Error while attempting to identify version of schema: "
                   schema-name)) 
      nil)))

(defn set-schema-version [ conn schema-name req-schema-version ]
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

(defn install-schema [ conn schema-name schema-version ]
  "Locate and run the script necessary to install the specified
schema in the target database instance."
  (log/info "Installing schema" schema-name "version" schema-version)
  (run-script conn (schema-install-script schema-name schema-version))
  (set-schema-version conn schema-name schema-version))

(defn migrate-schema [ conn schema-name cur-schema-version req-schema-version ]
  "Locate and run the script necessary to migrate the currently installed version
of a database schema to the requested version."
  (log/info "Upgrading schema" schema-name "from version" cur-schema-version "to" req-schema-version)
  (doseq [ from-version (range cur-schema-version req-schema-version )]
    (let [ to-version (+ from-version 1) ]
      (run-script conn (schema-migrate-script schema-name to-version))
      (set-schema-version conn schema-name to-version))))

(defn ensure-schema [ conn schema-name req-schema-version ]
  "Locate and run the scripts necessary to install the specified
schema in the target database instance."
  (log/debug "Ensuring schema" schema-name req-schema-version)
  (let [ cur-schema-version (get-schema-version conn schema-name) ]
    (cond
     (nil? cur-schema-version)
     (install-schema conn schema-name req-schema-version)

     (= cur-schema-version req-schema-version)
     (log/debug "Schema" schema-name "version" req-schema-version "confirmed present.")

     (< cur-schema-version req-schema-version)
     (migrate-schema conn schema-name cur-schema-version req-schema-version)

     :else
     (throw (Exception. (str "Cannot downgrade schema " schema-name " from version " cur-schema-version " to " req-schema-version))))))

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

(defn conn-assoc-schema [ conn schema-name schema-version ]
  "Add a sql-file schema to the connection map."
  (assoc conn
    :sql-file-schemas
    (conj (get conn :sql-file-schemas [["sql-file" 0]])
          [schema-name schema-version])))

(defn conn-ensure-schemas [ conn ]
  "Ensure that the sql-file schemas defined within the connection map
are present in the target database."
  (let [ req-schemas (:sql-file-schemas conn)]
    (log/debug "Ensuring requested schemas:" req-schemas)
    (reduce (fn [ conn [ req-schema-name req-schema-version ] ]
              (ensure-schema conn req-schema-name req-schema-version)
              conn)
            conn
            req-schemas)))

(defn open-hsqldb-file-conn [ filename schema-name schema-version ]
  "Open an HSQLDB database file with the given filename, and ensure
that the specified schema is available within that database."
  (let [ conn (conn-assoc-schema (hsqldb-file-conn filename)
                                 schema-name schema-version) ]
    (log/info "Opening sql-file:" conn)
    (conn-ensure-schemas conn)))

(defn open-hsqldb-memory-conn [ aname schema-name schema-version ]
  "Open an in-memory HSQLDB database with the given aname, and ensure
that the specified schema is available within that database."
  (let [ conn (conn-assoc-schema (hsqldb-memory-conn aname)
                                 schema-name schema-version) ]
    (log/info "Opening sql-file:" conn)
    (conn-ensure-schemas conn)))
