(ns runner
  (:require [clojure.test :as test]
            sqlite-replicate.db-test
            sqlite-replicate.service-test
            sqlite-replicate.standby-test
            sqlite-replicate.litestream-performance-test)) ; Added new performance test namespace

(defn -main [& args]
  (println "Running tests via test runner...")
  (let [summary (test/run-all-tests #"sqlite-replicate.*-test")]
    (println "Test run complete.")
    (System/exit (if (test/successful? summary) 0 1))))
