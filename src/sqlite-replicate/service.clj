(ns sqlite-replicate.service
  (:require [sqlite-replicate.db :as db]
            [org.httpkit.server :as http]
            [clojure.tools.logging :as log]))

(defonce server (atom nil))
(defonce writer (atom nil))
(def default-port 3001)

(defn health-handler [_]
  {:status 200
   :headers {"Content-Type" "application/json"}
   :body "{\"status\":\"OK\",\"timestamp\":\"" (System/currentTimeMillis) "\"}"})

(defn start-server
  "启动健康检查HTTP服务器，可配置端口"
  ([] (start-server default-port))
  ([port]
   (try
     (when-not @server
       (reset! server (http/run-server #'health-handler {:port port}))
       (log/info (str "健康检查HTTP服务器已启动，端口: " port))
       {:status :success :message (str "健康检查HTTP服务器已启动，端口: " port)})
     (catch Exception e
       (log/error e "启动健康检查HTTP服务器失败")
       {:status :error :message (str "启动健康检查HTTP服务器失败: " (.getMessage e))}))))

(defn stop-server []
  (try
    (when-let [stop-fn @server]
      (stop-fn)
      (reset! server nil)
      (log/info "健康检查HTTP服务器已停止")
      {:status :success :message "健康检查HTTP服务器已停止"})
    (catch Exception e
      (log/error e "停止健康检查HTTP服务器失败")
      {:status :error :message (str "停止健康检查HTTP服务器失败: " (.getMessage e))})))

(defn record-timestamp-event! []
  (db/add-event! (System/currentTimeMillis)))

(defn start-writer []
  (reset! writer
          (future
            (while true
              (record-timestamp-event!)
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
