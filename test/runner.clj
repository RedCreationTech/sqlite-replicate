(ns runner
  (:require [clojure.test :as test]
            myapp.db-test
            myapp.service-test
            myapp.standby-test))

(defn -main [& args]
  (println "Running tests via test runner...")
  (let [summary (test/run-all-tests #"myapp.*-test")]
    (println "Test run complete.")
    (System/exit (if (test/successful? summary) 0 1))))
