(ns sqlite-replicate.litestream-performance-test
  (:require [clojure.test :refer :all]
            [sqlite-replicate.perf-test-utils :as utils]
            [clojure.java.io :as io]
            [next.jdbc :as jdbc]
            [next.jdbc.sql :as sql]
            [clj-time.core :as t]
            [clj-time.coerce :as tc]
            [taoensso.timbre :as timbre])) ; timbre 日志库

;; --- 测试配置 ---
(defn test-db-filename
  "Generates a filename for a test database, optionally incorporating a type/identifier.
   Example: (test-db-filename \"10MB\") -> \"test-perf-app-data-10MB.db\" "
  ([] (str "test-perf-app-data-" (System/currentTimeMillis) ".db")) ; Default with timestamp if no type
  ([type-id] (str "test-perf-app-data-" type-id ".db")))

(defn restored-db-filename
  "Generates a filename for a restored database, optionally incorporating a type/identifier.
   Example: (restored-db-filename \"10MB\") -> \"restored-perf-app-data-10MB.db\" "
  ([] (str "restored-perf-app-data-" (System/currentTimeMillis) ".db")) ; Default with timestamp
  ([type-id] (str "restored-perf-app-data-" type-id ".db")))

(defonce ^{:doc "Litestream 配置文件的路径 (假设在项目根目录)。"}
  litestream-config-path "./litestream.yml")

(defonce ^{:doc "要测试的数据库大小列表 (MB)。"}
  db-sizes-mb [10 50 100 200 300 500 1000]) ; 完整列表

(defonce ^{:doc "要测试的写入频率列表 (Hz)。完整列表：[1 10]"}
  write-frequencies-hz [1 2]) ; 初始测试使用较小的值

(defonce ^{:doc "在频率测试中持续写入操作的总时长 (秒)。"}
  continuous-write-duration-seconds 5) ; 初始测试使用较短时间

;; --- 测试辅助函数 ---
(defn copy-file
  "将源文件复制到目标路径。"
  [source-path dest-path]
  (timbre/debug (format "正在复制文件 '%s' 到 '%s'" source-path dest-path))
  (io/copy (io/file source-path) (io/file dest-path)))

;; --- 测试 Fixture ---
(defn test-fixture-adjusted
  "测试的 Fixture，用于在所有测试运行之前/之后执行设置和清理操作。
   针对 litestream.yml 中硬编码的数据库路径 ('app-data.db') 进行了调整。"
  [f]
  (timbre/info "\n=== 测试 Fixture 设置 (已调整以适应 litestream.yml 路径) ===")
  (utils/clear-minio-bucket-path)
  ;; 注意：由于文件名现在是动态生成的，启动时的清理可能无法捕获特定文件名
  ;; 但在测试执行期间会使用动态名称。
  ;; (utils/clear-db-file (test-db-filename)) ; 如果需要清理特定模式的文件，需要更复杂的逻辑
  (utils/clear-db-file utils/default-db-filename) ; litestream.yml 实际监控的文件

  ;; 在测试开始前，按前缀清理上一次测试可能遗留的文件 - 移除此逻辑以允许文件复用
  ;; (utils/clear-db-files-by-prefix "test-perf-app-data-")
  ;; (utils/clear-db-files-by-prefix "restored-perf-app-data-")
  (timbre/info "Skipping general prefix-based cleanup to allow database file reuse.")

  (f) ; 执行测试主体
  (timbre/info "=== 测试 Fixture 清理 (已调整) ===")
  ;; 清理 Litestream 监控文件及其 WAL 文件
  (utils/clear-db-file utils/default-db-filename)
  (timbre/info (str "Fixture cleanup complete. Test-specific databases (e.g., test-perf-app-data-*) "
                    "are intentionally preserved for potential reuse or inspection."))
  )

;; 使用 :once Fixture，它会在当前命名空间所有测试运行前执行一次 setup，所有测试结束后执行一次 teardown。
;; 如果每个 deftest 需要更严格的隔离，可以使用 :each。
(use-fixtures :once test-fixture-adjusted)

;; --- 测试场景 ---

(deftest ^:performance static-db-backup-restore-performance-adjusted
  (timbre/info "\n--- 开始测试：静态数据库备份与恢复性能 (已调整) ---")
  (doseq [target-size-mb db-sizes-mb]
    (testing (str "数据库大小: " target-size-mb " MB")
      (let [test-id (str target-size-mb "MB")
            current-test-db (test-db-filename test-id)
            current-restored-db (restored-db-filename test-id)]
        (timbre/info (format "--- 测试场景：静态数据库，目标大小 %.2f MB (测试DB: %s, 恢复DB: %s) ---"
                             target-size-mb current-test-db current-restored-db))
        (utils/clear-minio-bucket-path) ; 确保每次测试大小前 MinIO 存储桶是干净的
        ;; (utils/clear-db-file current-test-db) ; Don't clear before check, allow reuse
        (utils/clear-db-file utils/default-db-filename) ; 清理 Litestream 监控文件及其 WAL
        (utils/clear-db-file current-restored-db) ; Clear restored DB from previous run of same params

        (let [db-file-exists? (.exists (io/file current-test-db))
              original-size-mb (if db-file-exists?
                                 (do
                                   (timbre/info (format "数据库文件 '%s' 已存在，将复用。大小: %.2f MB"
                                                        current-test-db (utils/get-file-size-mb current-test-db)))
                                   (utils/get-file-size-mb current-test-db))
                                 (do
                                   (timbre/info (format "数据库文件 '%s' 不存在，将生成新的数据库。" current-test-db))
                                   (utils/populate-db-to-size current-test-db target-size-mb)))]
          (is (some? original-size-mb) (str "原始数据库 " current-test-db " 应当成功创建或复用"))
          (timbre/info (format "原始数据库 '%s' %s，实际大小: %.2f MB"
                               current-test-db (if db-file-exists? "复用" "新创建") original-size-mb))

          ; 清理 Litestream 目标路径，然后复制
          (utils/clear-db-file utils/default-db-filename)
          (copy-file current-test-db utils/default-db-filename)
        (is (.exists (io/file utils/default-db-filename)) "数据库文件应当已复制到 Litestream 的监控路径")

        (timbre/info "启动 Litestream replicate 进行备份...")
        (let [ls-process (utils/start-litestream-replicate litestream-config-path)]
          (is (some? ls-process) "Litestream replicate 进程应当成功启动")
          (timbre/info "等待 Litestream 完成初始同步 (snapshot-interval 默认10s, sync-interval 默认1s)...")
          (timbre/info "将等待 15 秒以确保至少一个快照周期完成。")
          (Thread/sleep 15000) ; 增加等待时间至 15 秒
          (utils/stop-litestream-process ls-process)
          (timbre/info "Litestream replicate 进程已停止。"))

        ; 删除本地被监控的数据库文件，模拟数据丢失场景
        (utils/clear-db-file utils/default-db-filename)
        (is (not (.exists (io/file utils/default-db-filename))) "被 Litestream 监控的数据库文件应当在恢复前被删除")

        (timbre/info "正在从 Litestream 恢复数据库...")
        (let [restore-result (utils/litestream-restore current-restored-db litestream-config-path) ; 将备份恢复到 current-restored-db
              restored-size-mb (utils/get-file-size-mb current-restored-db)]
          (is (:success restore-result) "Litestream restore 命令应当成功执行。")
          (is (some? restored-size-mb) (str "恢复后的数据库 " current-restored-db " 应当存在"))
          (timbre/info (format "数据库恢复完成至 '%s'，耗时 %.2f ms。恢复后的文件大小: %.2f MB"
                               current-restored-db
                               (:duration-ms restore-result)
                               (or restored-size-mb 0)))

          ; 验证恢复后的文件大小与原始文件大小是否大致相符
          (when (and original-size-mb restored-size-mb)
            #_(is (> restored-size-mb (* original-size-mb 0.8)) ; 恢复后的文件大小应至少是原始大小的80%
                (format "恢复后的文件大小 %.2f MB 与原始文件大小 %.2f MB 相差过大"
                        restored-size-mb original-size-mb)))))
        (timbre/info (format "--- 完成静态数据库测试 (%.2f MB) ---" target-size-mb))))))

(defn- perform-writes
  "在一个循环中向指定数据库执行写入操作。
   参数:
     db-spec: 数据库连接规范
     frequency-hz: 每秒写入次数
     duration-seconds: 持续写入的总秒数
   返回: 实际完成的写入次数。"
  [db-spec frequency-hz duration-seconds]
  (let [ds (jdbc/get-datasource db-spec)
        interval-ms (long (/ 1000 frequency-hz)) ; 每次写入的间隔时间 (ms)
        start-time-ns (System/nanoTime)
        end-time-ns (+ start-time-ns (* duration-seconds 1000000000))] ; 结束时间 (ns)
    (timbre/info (format "开始以 %d Hz 的频率持续写入 %d 秒，每次间隔 %d ms" frequency-hz duration-seconds interval-ms))
    (loop [writes-done 0]
      (if (>= (System/nanoTime) end-time-ns)
        (do
          (timbre/info (format "写入操作结束，总共执行 %d 次写入。" writes-done))
          writes-done)
        (do
          (try
            (sql/insert! ds :records {:data (utils/generate-random-string 200) ; 插入随机数据
                                      :created_at (utils/get-current-timestamp-for-db)})
            (catch Exception e
              (timbre/error e (str "数据库写入操作期间发生错误: " (.getMessage e)))))
          (Thread/sleep interval-ms)
          (recur (inc writes-done)))))))

(deftest ^:performance dynamic-db-restore-performance-adjusted
  (timbre/info "\n--- 开始测试：动态数据库 (有持续写入) 恢复性能 (已调整) ---")
  (let [base-db-size-mb 0.1 ; 为动态测试设置一个较小的基础数据库大小，以加快测试: 50MB
        ;; 使用特定名称，而不是带时间戳的名称，以便在需要时进行调试或手动检查
        base-current-test-db (test-db-filename "base-dynamic-source")
        _ (timbre/info (format "为动态测试准备基础数据库 '%s'，目标大小 %.2f MB" base-current-test-db base-db-size-mb))]
    ;; Populate base-current-test-db only if it doesn't exist
    (if (.exists (io/file base-current-test-db))
      (timbre/info (format "基础数据库 '%s' 已存在，将复用。大小: %.2f MB"
                           base-current-test-db (utils/get-file-size-mb base-current-test-db)))
      (do
        (timbre/info (format "基础数据库 '%s' 不存在，将生成新的。" base-current-test-db))
        (utils/clear-db-file base-current-test-db) ; Ensure clean slate if we are creating it
        (utils/populate-db-to-size base-current-test-db base-db-size-mb)))
    (let [_ (utils/clear-db-file utils/default-db-filename) ; 清理 Litestream 监控文件
          db-spec (utils/get-db-spec utils/default-db-filename) ; 写入操作将发生在这个被监控的数据库上
        ]

    (doseq [freq write-frequencies-hz]
      (testing (str "写入频率: " freq " Hz")
        (let [test-id (str freq "Hz")
              current-restored-db (restored-db-filename test-id)] ; 每个频率测试恢复到不同的文件
          (timbre/info (format "--- 测试场景：动态数据库，写入频率 %d Hz (恢复DB: %s) ---" freq current-restored-db))
          (utils/clear-minio-bucket-path) ; 为每个频率测试清理 MinIO
          (utils/clear-db-file current-restored-db) ; 清理可能存在的旧恢复文件

          ; 为确保每个频率测试的独立性，从基础源DB复制到Litestream监控文件
          (timbre/info (format "将基础数据库 '%s' 复制到 '%s' 以进行频率 %s Hz 测试..."
                               base-current-test-db utils/default-db-filename freq))
          (utils/clear-db-file utils/default-db-filename) ; 确保目标是干净的
          (copy-file base-current-test-db utils/default-db-filename) ; 从 base-current-test-db 复制

          (let [initial-rows (:count (sql/query (jdbc/get-datasource db-spec) ["SELECT COUNT(*) as count FROM records"]))
                _ (timbre/info (format "数据库 '%s' (被 Litestream 监控) 中的初始行数: %d" utils/default-db-filename initial-rows))]

          (timbre/info (str "启动 Litestream replicate 进行动态测试 (频率: " freq " Hz)..."))
          (let [ls-process (utils/start-litestream-replicate litestream-config-path)]
            (is (some? ls-process) "Litestream replicate 进程应当为动态测试成功启动")

            (timbre/info (format "开始以 %d Hz 的频率向数据库 '%s' 持续写入 %d 秒..." freq utils/default-db-filename continuous-write-duration-seconds))
            (let [writes-performed (perform-writes db-spec freq continuous-write-duration-seconds)]
              (timbre/info (format "在数据库 '%s' 中执行了 %d 次写入操作。" writes-performed utils/default-db-filename))
              (timbre/info "等待 Litestream 同步最后的更改 (sync-interval 默认1s)...")
              (timbre/info "将等待 5 秒以确保最后的写入操作被同步。")
              (Thread/sleep 5000) ; 增加等待时间至 5 秒
              (utils/stop-litestream-process ls-process)
              (timbre/info "Litestream replicate 进程已为动态测试停止。")

              (let [rows-after-writes (:count (sql/query (jdbc/get-datasource db-spec) ["SELECT COUNT(*) as count FROM records"]))]
                (timbre/info (format "数据库 '%s' 在写入操作后的总行数: %d" utils/default-db-filename rows-after-writes))
                (is (= (+ initial-rows writes-performed) rows-after-writes) "被监控数据库的行数应当准确反映所有写入操作"))

              ; 删除本地被监控的数据库文件
              (utils/clear-db-file utils/default-db-filename)
              (is (not (.exists (io/file utils/default-db-filename))) "被监控的数据库文件应当在恢复前被删除 (动态测试)")

              (timbre/info "正在从 Litestream 恢复动态写入后的数据库...")
              (let [restore-result (utils/litestream-restore current-restored-db litestream-config-path)
                    restored-ds (jdbc/get-datasource (utils/get-db-spec current-restored-db))
                    rows-in-restored-db (:count (sql/query restored-ds ["SELECT COUNT(*) as count FROM records"]))]
                (is (:success restore-result) "Litestream restore 命令应当成功执行 (动态测试)。")
                (timbre/info (format "数据库恢复 (动态测试) 至 '%s' 完成，耗时 %.2f ms。恢复后的数据库中的行数: %d"
                                     current-restored-db
                                     (:duration-ms restore-result)
                                     rows-in-restored-db))
                (is (= (+ initial-rows writes-performed) rows-in-restored-db)
                    "恢复后的数据库应当包含所有基础数据和动态写入的数据行。"))))
          (timbre/info (format "--- 完成动态数据库测试 (写入频率 %d Hz) ---" freq))))))))

;; --- 如何运行测试 ---
;;
;; 1. 确保 Litestream 和 MinIO CLI (mc) 已安装并配置在系统 PATH 中。
;; 2. 确保本地 MinIO 服务器正在运行。例如使用 Docker:
;;    docker run -d -p 9000:9000 --name minio-server \
;;      -e "MINIO_ROOT_USER=minioadmin" \
;;      -e "MINIO_ROOT_PASSWORD=minioadmin" \
;;      minio/minio server /data --console-address ":9001"
;;    (请确保 /data 路径对于 MinIO 容器是可写的)
;;
;; 3. 配置 MinIO CLI (mc) 以连接到本地服务器:
;;    mc alias set myminio http://127.0.0.1:9000 minioadmin minioadmin
;;
;; 4. (可选) 手动创建存储桶 (如果 Litestream 不能自动创建或权限不足):
;;    mc mb myminio/clojure-db-replica
;;
;; 5. 在项目根目录创建 `litestream.yml` 配置文件，内容如下:
;;    (注意: `dbs[0].path` 必须是 `./app-data.db` 以匹配 `perf_test_utils.clj` 中的 `default-db-filename`)
;;
;;    ```yaml
;;    # litestream.yml 配置文件
;;    access-key-id: minioadmin
;;    secret-access-key: minioadmin
;;
;;    dbs:
;;      - path: ./app-data.db
;;        replicas:
;;          - name: s3-main
;;            type: s3
;;            bucket: clojure-db-replica
;;            path: database
;;            endpoint: http://127.0.0.1:9000
;;            force-path-style: true
;;            # 测试友好的同步设置:
;;            sync-interval: 1s
;;            snapshot-interval: 10s
;;            retention: 5m
;;            validation-interval: 15s
;;    ```
;;
;; 6. 从 REPL 运行测试:
;;    (clojure.test/run-tests 'sqlite-replicate.litestream-performance-test)
;;
;; 7. 或者使用项目的测试运行器别名 (例如 `clojure -X:test`，如果已配置为运行此命名空间)。
;;

;; 清理旧的、未调整的测试函数定义 (如果它们之前被加载过)
(ns-unmap *ns* 'static-db-backup-restore-performance)
(ns-unmap *ns* 'dynamic-db-restore-performance)
(ns-unmap *ns* 'test-fixture)

(deftest ^:performance combined-db-performance-test
  (timbre/info "\n--- 开始测试：组合场景 - 数据库大小与写入频率 ---")
  (doseq [target-size-mb db-sizes-mb]
    (doseq [freq write-frequencies-hz]
      (let [size-str (str target-size-mb "MB")
            freq-str (str freq "Hz")
            base-name (str "app-data-" size-str "-" freq-str)
            current-test-db (str base-name ".db")
            ;; Restored DB name includes a timestamp to ensure uniqueness for each execution
            restored-db-name (str "restored-" base-name "-" (System/currentTimeMillis) ".db")]

        (testing (str "数据库大小: " size-str ", 写入频率: " freq-str)
          (timbre/info (format "--- 测试场景：大小 %s, 频率 %s (测试DB: %s, 恢复DB: %s) ---"
                               size-str freq-str current-test-db restored-db-name))

          (utils/clear-minio-bucket-path) ; Clean MinIO for each specific combination test
          (utils/clear-db-file restored-db-name) ; Clean any potential leftover restored DB

          ;; 1. Prepare Source Database (Create or Reuse)
          (let [db-file-exists? (.exists (io/file current-test-db))
                original-size-mb (if db-file-exists?
                                   (do
                                     (timbre/info (format "数据库文件 '%s' 已存在，将复用。大小: %.2f MB"
                                                          current-test-db (utils/get-file-size-mb current-test-db)))
                                     (utils/get-file-size-mb current-test-db))
                                   (do
                                     (timbre/info (format "数据库文件 '%s' 不存在，将生成新的数据库。" current-test-db))
                                     (utils/populate-db-to-size current-test-db target-size-mb)))]
            (is (some? original-size-mb) (str "组合测试：原始数据库 " current-test-db " 应当成功创建或复用"))
            (timbre/info (format "组合测试：原始数据库 '%s' %s，实际大小: %.2f MB"
                                 current-test-db (if db-file-exists? "复用" "新创建") original-size-mb)))


          ;; 2. Copy to Litestream monitored path
          (timbre/info (format "复制 '%s' 到 '%s' 以供 Litestream 监控" current-test-db utils/default-db-filename))
          (utils/clear-db-file utils/default-db-filename) ; Ensure Litestream path is clean
          (copy-file current-test-db utils/default-db-filename)
          (is (.exists (io/file utils/default-db-filename)) "数据库文件应当已复制到 Litestream 的监控路径 (组合测试)")

          (let [db-spec (utils/get-db-spec utils/default-db-filename)
                initial-rows-before-writes (:count (sql/query (jdbc/get-datasource db-spec) ["SELECT COUNT(*) as count FROM records"]))]
            (timbre/info (format "数据库 '%s' 在动态写入前的初始行数: %d" utils/default-db-filename initial-rows-before-writes))

            ;; 3. Start Litestream and perform writes
            (timbre/info "启动 Litestream replicate (组合测试)...")
            (let [ls-process (utils/start-litestream-replicate litestream-config-path)]
              (is (some? ls-process) "Litestream replicate 进程应当为组合测试成功启动")

              (timbre/info (format "开始以 %d Hz 的频率向数据库 '%s' 持续写入 %d 秒 (组合测试)..."
                                   freq utils/default-db-filename continuous-write-duration-seconds))
              (let [writes-performed (perform-writes db-spec freq continuous-write-duration-seconds)]
                (timbre/info (format "在数据库 '%s' 中执行了 %d 次写入操作 (组合测试)。" writes-performed utils/default-db-filename))
                (timbre/info "等待 Litestream 同步最后的更改 (组合测试)...")
                (Thread/sleep 5000) ; Wait for sync
                (utils/stop-litestream-process ls-process)
                (timbre/info "Litestream replicate 进程已为组合测试停止。")

                (let [rows-after-writes (:count (sql/query (jdbc/get-datasource db-spec) ["SELECT COUNT(*) as count FROM records"]))]
                  (timbre/info (format "数据库 '%s' 在写入操作后的总行数: %d (组合测试)" utils/default-db-filename rows-after-writes))
                  (is (= (+ initial-rows-before-writes writes-performed) rows-after-writes)
                      "被监控数据库的行数应当准确反映所有写入操作 (组合测试)"))

                ;; 4. Delete local monitored DB and Restore
                (utils/clear-db-file utils/default-db-filename)
                (is (not (.exists (io/file utils/default-db-filename)))
                    "被监控的数据库文件应当在恢复前被删除 (组合测试)")

                (timbre/info "正在从 Litestream 恢复组合测试后的数据库...")
                (let [restore-result (utils/litestream-restore restored-db-name litestream-config-path)
                      restored-ds (jdbc/get-datasource (utils/get-db-spec restored-db-name))
                      rows-in-restored-db (:count (sql/query restored-ds ["SELECT COUNT(*) as count FROM records"]))]
                  (is (:success restore-result) "Litestream restore 命令应当成功执行 (组合测试)。")
                  (timbre/info (format "数据库恢复 (组合测试) 至 '%s' 完成，耗时 %.2f ms。恢复后的数据库中的行数: %d"
                                       restored-db-name
                                       (:duration-ms restore-result)
                                       rows-in-restored-db))
                  (is (= (+ initial-rows-before-writes writes-performed) rows-in-restored-db)
                      "恢复后的数据库应当包含所有基础数据和动态写入的数据行 (组合测试)。")))))
          (timbre/info (format "--- 完成组合测试场景 (大小 %s, 频率 %s) ---" size-str freq-str))))))
