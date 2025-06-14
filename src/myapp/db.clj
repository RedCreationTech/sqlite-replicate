(ns myapp.db
  (:require [next.jdbc :as jdbc]
            [next.jdbc.sql :as sql]))

(def db-spec {:dbtype "sqlite" :dbname "app-data.db"})

(def ds (jdbc/get-datasource db-spec))

(defn set-pragmas!
  "Set SQLite pragmas needed for Litestream." [conn]
  ;; 1. Enable WAL mode for Litestream
  (jdbc/execute! conn ["PRAGMA journal_mode=WAL;"])
  ;; 2. Set busy timeout to avoid lock issues during checkpoints
  (jdbc/execute! conn ["PRAGMA busy_timeout=5000;"])
  ;; 3. Normal sync mode is safe in WAL mode and faster
  (jdbc/execute! conn ["PRAGMA synchronous=NORMAL;"])
  ;; 4. Disable automatic checkpoints so Litestream controls them
  (jdbc/execute! conn ["PRAGMA wal_autocheckpoint=0;"])
  (println "PRAGMA settings for Litestream applied."))

(defn initialize-database!
  "Create schema and apply PRAGMAs."
  []
  (with-open [conn (jdbc/get-connection ds)]
    (set-pragmas! conn)
    (jdbc/execute! conn ["CREATE TABLE IF NOT EXISTS users (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, email TEXT);"]))

(defn add-user!
  "Insert a user map." [user-map]
  (with-open [conn (jdbc/get-connection ds)]
    (set-pragmas! conn)
    (sql/insert! conn :users user-map)))

(defn get-all-users []
  (sql/query ds ["SELECT id, name, email FROM users"]))

(defn -main [& _]
  (println "Initializing database...")
  (initialize-database!)
  (println "\nAdding a new user...")
  (add-user! {:name "Alice" :email "alice@example.com"})
  (println "\nCurrent users:")
  (println (get-all-users))
  (println "\nAdding another user...")
  (add-user! {:name "Bob" :email "bob@example.com"})
  (println "\nCurrent users:")
  (println (get-all-users)))

