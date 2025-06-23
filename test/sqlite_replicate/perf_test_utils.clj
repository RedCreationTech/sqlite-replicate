(ns sqlite-replicate.perf-test-utils
  (:require [clojure.java.io :as io]
            [next.jdbc :as jdbc]
            [next.jdbc.sql :as sql]
            [clojure.string :as str]
            [clj-time.core :as t]
            [clj-time.coerce :as tc]
            [cheshire.core :as json]
            [clojure.java.shell :as shell]
            [taoensso.timbre :as timbre]) ; timbre 日志库
  (:import [java.io File]
           [java.util Random]))

(timbre/set-level! :info) ; 设置默认日志级别，可以根据需要调整 :debug, :info, :warn, :error

(defonce ^{:doc "测试用的默认数据库文件名。"}
  default-db-filename "app-data.db")

(defonce ^{:doc "通过 mc 配置的 MinIO 别名。"}
  minio-alias "myminio")

(defonce ^{:doc "用于 Litestream 备份的 MinIO 存储桶。"}
  minio-bucket "clojure-db-replica")

(defonce ^{:doc "数据库备份在 MinIO 存储桶中的路径。"}
  minio-path "database")

(defn get-db-spec
  "根据数据库文件名生成 JDBC 连接规范。"
  [db-filename]
  {:dbtype "sqlite"
   :dbname db-filename})

(defn get-current-timestamp-for-db
  "获取当前时间戳，用于数据库记录。"
  []
  (-> (t/now) tc/to-timestamp))

(defn create-test-table
  "在指定的数据库连接 (DataSource) 中创建测试表 (如果不存在)。"
  [ds]
  (jdbc/execute! ds ["
CREATE TABLE IF NOT EXISTS records (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  data TEXT,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
)"]))

(defn get-file-size-mb
  "获取指定文件的体积 (MB)。"
  [^String filename]
  (let [file (io/file filename)]
    (when (.exists file)
      (/ (double (.length file)) (* 1024 1024)))))

(defn generate-random-string
  "生成指定长度的随机字符串。"
  [length]
  (let [random (Random.)
        sb (StringBuilder. length)]
    (dotimes [_ length]
      (.append sb (char (+ (int \a) (.nextInt random 26)))))
    (.toString sb)))

(defn generate-json-like-string
  "生成一个 JSON 格式的字符串，用于填充数据。"
  [complexity]
  (json/generate-string
   (into {}
         (for [i (range complexity)]
           [(keyword (str "key" i)) (generate-random-string (+ 10 (rand-int 20)))]))))

(defn populate-db-to-size
  "向数据库中填充数据，直到达到目标大小 (MB)。"
  [db-filename target-size-mb]
  (let [ds (jdbc/get-datasource (get-db-spec db-filename))
        row-size-bytes 1024 ; 估算每行数据的大小 (字节)，JSON 可能更大，需要根据实际情况调整
        bytes-per-mb (* 1024 1024)
        target-bytes (* target-size-mb bytes-per-mb)
        estimated-rows (max 1 (long (/ target-bytes row-size-bytes)))] ; 估算需要的行数
    (io/delete-file db-filename true) ; 如果文件已存在，则删除
    (create-test-table ds)
    (timbre/info (format "正在填充数据库 %s 至约 %.2f MB..." db-filename target-size-mb))
    (loop [inserted-count 0
           current-size-bytes 0]
      (if (or (>= current-size-bytes target-bytes)
              (>= inserted-count (* estimated-rows 1.5))) ; 安全中止条件，防止无限循环，可调整乘数
        (do
          (let [final-size (get-file-size-mb db-filename)]
            (timbre/info (format "数据库 %s 填充完成。目标大小: %.2f MB, 实际大小: %.2f MB, 总行数: %d"
                                 db-filename target-size-mb (or final-size 0) inserted-count))
            final-size))
        (let [text-data (generate-json-like-string (+ 5 (rand-int 10)))] ; 生成不同复杂度的文本数据
          (sql/insert! ds :records {:data text-data :created_at (get-current-timestamp-for-db)})
          (if (= (mod (inc inserted-count) 1000) 0) ; 每插入1000行检查一次文件大小
            (let [new-size-bytes (.length (io/file db-filename))]
              (recur (inc inserted-count) new-size-bytes))
            (recur (inc inserted-count) current-size-bytes)))))))


(defn clear-db-file
  "删除指定的数据库文件。"
  [db-filename]
  (timbre/info (str "正在删除数据库文件: " db-filename))
  (io/delete-file db-filename true))

(defn execute-shell-command
  "执行 shell 命令并返回结果 map，包含 :out, :err, :exit。"
  [cmd-str]
  (timbre/info (str "正在执行命令: " cmd-str))
  (let [result (apply shell/sh (str/split cmd-str #"\s+"))]
    (when (not (str/blank? (:out result)))
      (timbre/debug "命令输出 (stdout):" (:out result)))
    (when (not (str/blank? (:err result)))
      (timbre/error "命令错误输出 (stderr):" (:err result)))
    result))

(defn clear-minio-bucket-path
  "使用 MinIO CLI (mc) 清空测试存储桶中的指定路径。"
  []
  (let [full-path (format "%s/%s/%s" minio-alias minio-bucket minio-path)
        cmd (format "mc rm --recursive --force %s" full-path)]
    (timbre/info (str "正在清空 MinIO 路径: " full-path))
    (execute-shell-command cmd)))

(defn start-litestream-replicate
  "启动 'litestream replicate' 进程。"
  [config-path]
  (let [cmd (str "litestream replicate -config " config-path)
        process (-> (ProcessBuilder. (str/split cmd #"\s+"))
                    (.redirectErrorStream true) ; 合并 stdout 和 stderr
                    (.start))]
    (timbre/info (str "Litestream replicate 进程已启动，命令: " cmd))
    ;; 在单独的线程中消费输出流，防止阻塞
    (future (with-open [reader (io/reader (.getInputStream process))]
              (doseq [line (line-seq reader)]
                (timbre/info (str "[litestream-out] " line)))))
    process))

(defn stop-litestream-process
  "停止指定的 Litestream 进程。"
  [^Process process]
  (when process
    (timbre/info "正在停止 Litestream 进程...")
    (.destroy process) ; 尝试正常关闭
    (let [exit-code (.waitFor process 30 java.util.concurrent.TimeUnit/SECONDS)] ; 等待最多30秒
      (if exit-code
        (timbre/info (str "Litestream 进程已退出，退出码: " (.exitValue process)))
        (do
          (timbre/warn "Litestream 进程未能正常退出，正在强制终止...")
          (.destroyForcibly process)
          (timbre/info "Litestream 进程已强制终止。")))
      true)))

(defn litestream-restore
  "执行 'litestream restore' 命令并记录时间。"
  [output-db-path config-path]
  (let [cmd (format "litestream restore -config %s -o %s %s"
                    config-path
                    output-db-path
                    default-db-filename) ; 假设 default-db-filename 是 litestream.yml 中配置的源数据库名
        start-time (System/nanoTime)
        result (execute-shell-command cmd)
        duration-ms (/ (double (- (System/nanoTime) start-time)) 1000000.0)]
    (if (= 0 (:exit result))
      (timbre/info (format "Litestream 恢复操作完成，耗时 %.2f ms。输出文件: %s" duration-ms output-db-path))
      (timbre/error (format "Litestream 恢复操作失败。退出码: %s" (:exit result))))
    {:success (= 0 (:exit result))
     :duration-ms duration-ms
     :output-path output-db-path}))

(comment
  ;; 用于手动测试工具函数
  (let [db-file "test-utils.db"]
    (populate-db-to-size db-file 0.1)
    (timbre/info "数据库大小:" (get-file-size-mb db-file))
    (clear-db-file db-file))

  (clear-minio-bucket-path)

  ;; 启动和停止 replicate 的示例 (手动)
  ;; (def litestream-process (start-litestream-replicate "./litestream.yml"))
  ;; (Thread/sleep 10000)
  ;; (stop-litestream-process litestream-process)

  ;; (litestream-restore "./restored-from-comment.db" "./litestream.yml")
  )
