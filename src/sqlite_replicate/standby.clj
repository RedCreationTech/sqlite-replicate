(ns sqlite-replicate.standby
  (:require [sqlite-replicate.db :as db]
            [sqlite-replicate.service :as service]
            [clojure.java.shell :as shell]
            [org.httpkit.client :as client]
            [next.jdbc :as jdbc]
            [clojure.string :as str]
            [clojure.tools.logging :as log]))

(def health-url "http://localhost:3011/health")

(defn set-sqlite-pragmas!
  "设置SQLite的PRAGMA参数，优化Litestream性能"
  [db-path]
  (try
    (let [db-spec {:dbtype "sqlite" :dbname db-path}]
      (with-open [conn (jdbc/get-connection (jdbc/get-datasource db-spec))]
        ;; 1. 启用WAL模式，Litestream需要
        (jdbc/execute! conn ["PRAGMA journal_mode=WAL;"])
        ;; 2. 设置busy超时，避免检查点期间的锁定问题
        (jdbc/execute! conn ["PRAGMA busy_timeout=5000;"])
        ;; 3. WAL模式下，NORMAL同步模式安全且更快
        (jdbc/execute! conn ["PRAGMA synchronous=NORMAL;"])
        ;; 4. 禁用自动检查点，由Litestream控制
        (jdbc/execute! conn ["PRAGMA wal_autocheckpoint=0;"])
        (log/info "已应用Litestream所需的PRAGMA设置")
        {:status :success :message "已应用Litestream所需的PRAGMA设置"}))
    (catch Exception e
      (log/error e "设置SQLite PRAGMA失败")
      {:status :error :message (str "设置SQLite PRAGMA失败: " (.getMessage e))})))

(defn port-active? []
  (try
    (let [{:keys [status]} @(client/get health-url {:timeout 2000})]
      (= 200 status))
    (catch Exception _ false)))

(defn restore-db
  "从S3/MinIO恢复数据库，支持配置参数"
  ([] (restore-db {}))
  ([config]
   (try
     (let [output-path (or (:output-path config) "./app-data.db") ; 默认恢复到项目根目录
           s3-access-key (or (:s3-access-key config) (System/getenv "LITESTREAM_ACCESS_KEY_ID"))
           s3-secret-key (or (:s3-secret-key config) (System/getenv "LITESTREAM_SECRET_ACCESS_KEY"))
           s3-endpoint (or (:s3-endpoint config) (System/getenv "LITESTREAM_ENDPOINT"))
           s3-bucket (or (:s3-bucket config) "clojure-db-replica")
           s3-path (or (:s3-path config) "database")
           s3-url (format "s3://%s/%s" s3-bucket s3-path)
           env-vars (merge {"LITESTREAM_ACCESS_KEY_ID" s3-access-key
                            "LITESTREAM_SECRET_ACCESS_KEY" s3-secret-key}
                           (into {} (System/getenv)))
           cmd-args ["litestream" "restore" "-o" output-path]
           cmd-args (if s3-endpoint (conj cmd-args "-endpoint" s3-endpoint) cmd-args)
           cmd-args (conj cmd-args "-if-replica-exists" "-if-db-not-exists" s3-url)
           _ (log/info (str "Restoring SQLite database via Litestream to " output-path))
           result (shell/with-sh-env env-vars
                    (apply shell/sh cmd-args))]
       (if (zero? (:exit result))
         (do
           (log/info "数据库恢复成功:" output-path)
           (set-sqlite-pragmas! output-path)
           {:status :success :message (str "数据库恢复成功: " output-path)})
         (do
           (log/error "数据库恢复失败:" (:err result))
           {:status :error :message (str "数据库恢复失败: " (:err result))})))
     (catch Exception e
       (log/error e "数据库恢复过程中发生异常")
       {:status :error :message (str "数据库恢复过程中发生异常: " (.getMessage e))}))))

(defn start-service []
  (db/initialize-database!)
  (service/start-server)
  (service/start-writer)
  (println "Service running as primary. Press Ctrl+C to exit.")
  ;; 在非测试环境中阻塞，在测试环境中不阻塞
  (when-not (= "test" (System/getProperty "clojure.main.test"))
    @(promise)))

(defn check-primary-and-failover!
  "检查主节点健康状态，如果不健康则执行故障转移"
  ([] (check-primary-and-failover! {}))
  ([config]
   (if (port-active?)
     (do
       (log/info "主节点健康状态正常")
       false) ; 返回false表示未尝试故障转移
     (do
       (log/info "主节点不可达，开始执行故障转移...")
       (let [restore-result (restore-db config)]
         (if (= :success (:status restore-result))
           (do
             (log/info "数据库恢复成功，启动服务")
             ;; 调用 start-service 并忽略返回值
             ;; 在生产环境中，start-service 会阻塞，但在测试环境中不会
             (start-service)
             true) ; 返回true表示故障转移尝试成功
           (do
             (log/error "故障转移失败：" (:message restore-result))
             false)))))))

(defn -main [& _]
  (println "Standby instance started. Monitoring active service...")
  (loop []
    (when-not (check-primary-and-failover!) ; If failover didn't happen or failed
      (Thread/sleep 5000)
      (recur))))
