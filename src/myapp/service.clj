(ns myapp.service
  (:require [myapp.db :as db]
            [org.httpkit.server :as http]))

(defonce server (atom nil))
(defonce writer (atom nil))

(defn health-handler [_]
  {:status 200
   :headers {"Content-Type" "text/plain"}
   :body "OK"})

(defn start-server []
  (reset! server (http/run-server #'health-handler {:port 3000}))
  (println "HTTP server started on port 3000"))

(defn stop-server []
  (when-let [stop-fn @server]
    (stop-fn)
    (reset! server nil)))

(defn start-writer []
  (reset! writer
          (future
            (while true
              (db/add-event! (System/currentTimeMillis))
              (Thread/sleep 1000))))
  (println "Background writer started."))

(defn stop-writer []
  (when-let [f @writer]
    (future-cancel f)
    (reset! writer nil)))

(defn -main [& _]
  (db/initialize-database!)
  (start-server)
  (start-writer)
  (println "Service running. Press Ctrl+C to exit.")
  @(promise))
