(ns myapp.standby
  (:require [myapp.db :as db]
            [myapp.service :as service]
            [clojure.java.shell :as shell]
            [org.httpkit.client :as client]))

(def health-url "http://localhost:3001/health")

(defn port-active? []
  (try
    (let [{:keys [status]} @(client/get health-url {:timeout 2000})]
      (= 200 status))
    (catch Exception _ false)))

(defn restore-db []
  (println "Restoring SQLite database via Litestream...")
  (let [{:keys [exit err]} (shell/sh "litestream" "restore" "-o" "app-data.db" "s3://clojure-db-replica/database")]
    (if (zero? exit)
      (println "Restore completed.")
      (println "Restore failed:" err))
    (zero? exit)))

(defn start-service []
  (db/initialize-database!)
  (service/start-server)
  (service/start-writer)
  (println "Service running as primary. Press Ctrl+C to exit.")
  @(promise))

(defn -main [& _]
  (println "Standby instance started. Monitoring active service...")
  (loop []
    (if (port-active?)
      (do (Thread/sleep 5000) (recur))
      (do
        (println "Active service unreachable. Initiating failover...")
        (when (restore-db)
          (start-service))))))
