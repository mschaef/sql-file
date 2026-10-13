# Proposal: external SqlTool connections to a running application

Status: idea, not scheduled. Written after the 0.6.0 release.

## Motivation

`start-sqltool-shell` runs SqlTool on a connection inside the
application's own process, so it's only useful from a REPL or a
dedicated command-line entry point. There's no way to point SqlTool (or
any JDBC client) at an application that's already running, and an
embedded HSQLDB file database can't be opened by a second process while
the application holds its lock.

The goal is to let a running sql-file application optionally accept
network connections to its database, so it can be inspected and
queried from outside while it runs.

## Mechanism

HSQLDB ships a network listener, `org.hsqldb.server.Server`, in the jar
sql-file already depends on. Started inside the application's JVM, it
serves the same database the application already has open: HSQLDB
keeps one instance per database path per process, so the server and the
application share it rather than contending for the lock. File and
`mem:` databases both work.

A client connects with:

    java -jar sqltool.jar --inlineRc=url=jdbc:hsqldb:hsql://localhost:9001/app,user=SA

## API sketch

A new `:server` option on the descriptor passed to `open-local` and
`open-pool`:

```clojure
{:name "toto-db"
 :schemas ["todo-0"]
 :server {:port 9001            ; default 9001
          :address "127.0.0.1"  ; default loopback
          :alias "app"}}        ; the name in the hsql:// URL
```

- The listener starts only after schemas are installed and all checks
  have passed, so nothing outside sees a half-installed database.
- It's configured for embedding: no `System.exit` on shutdown, its own
  logging silenced or routed through `clojure.tools.logging`.
- The running server is kept in the connection map (e.g. under
  `::server`). `close-pool` (and therefore `with-pool` /
  `call-with-pool`) stops it. A `stop-server` function covers
  `open-local` users.
- To verify during implementation: that stopping the listener leaves
  the database open for the application (`Server.stop` vs `shutdown`,
  and `server.no_system_exit`), and that the server resolves a relative
  database path the same way the in-process connection does.
- ectcommon would pass `:db :server` from config through `start-app`.

Estimated size: 50–80 lines in core.clj, plus tests that open a
database with `:server`, connect over `hsql://`, and query it.

## Authentication

This is the real problem. sql-file currently uses HSQLDB's default
user, `SA` with an empty password. With a listener open, anyone who can
reach the port is a database administrator: they can read and change
everything, drop tables, or run `SHUTDOWN`.

Layers, roughly in order of value:

1. **Loopback only, by default.** Binding to `127.0.0.1` limits access
   to the local machine. Binding elsewhere requires setting `:address`
   explicitly.
2. **SSH tunnel for remote use.** With the listener on loopback, remote
   access goes through SSH, which already handles authentication and
   encryption:

       ssh -L 9001:localhost:9001 server

   For production this is the recommended setup, and it needs no new
   code.
3. **An SA password.** Read it from creds, not config.edn. On open,
   if a password is configured and SA has none, run
   `ALTER USER SA SET PASSWORD ...` once; afterwards connect with it.
   Lockout needs care: changing or losing the password must not leave
   the application unable to open its own database, and the
   unauthenticated open path (`start-sqltool-shell`, tests) has to keep
   working.
4. **A read-only network user.** `CREATE USER` plus `GRANT SELECT` (or a
   role) for the account used over the network, so routine inspection
   can't modify data or hold write locks. sql-file could create it from
   `:server {:user ... :password ...}`.
5. **HSQLDB's own controls.** An IP access-control file
   (`server.acl`) and TLS (`hsqls://` with a keystore). Both exist in
   HSQLDB; they mostly duplicate what an SSH tunnel provides.

## Risks and notes

- A long-running transaction opened from SqlTool can block the
  application's writes. A read-only user (4) reduces this.
- `SHUTDOWN` from a client closes the database out from under the
  application. Only an admin user can issue it, which is another reason
  for (3) and (4).
- `BACKUP DATABASE` works over the network connection, which is useful
  for ad-hoc backups of a running server.
- The listener is one more open port and thread pool. Off unless
  `:server` is given.

## Phasing

1. **Development only.** The `:server` option, loopback by default, SA
   with no password. Refuse to start unless `:development-mode` is set
   or an explicit override (e.g. `:server {:allow-unauthenticated
   true}`) is given.
2. **Production.** Credentials from creds, the SA password step, an
   optional read-only user, and README guidance on SSH tunnelling.
   ACL and TLS only if a need appears.
