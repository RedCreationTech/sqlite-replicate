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
(defonce ^{:doc "测试生成的数据库文件名。"}
  test-db-filename "test-perf-app-data.db")

(defonce ^{:doc "恢复后数据库的文件名。"}
  restored-db-filename "restored-perf-app-data.db")

(defonce ^{:doc "Litestream 配置文件的路径 (假设在项目根目录)。"}
  litestream-config-path "./litestream.yml")

(defonce ^{:doc "要测试的数据库大小列表 (MB)。完整列表：[10 50 100 200 300 500 1000]"}
  db-sizes-mb [0.1 0.25 0.5]) ; 初始测试使用较小的值以加快速度

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
  (utils/clear-db-file test-db-filename) ; 测试专用的、动态生成的数据库文件
  (utils/clear-db-file utils/default-db-filename) ; litestream.yml 实际监控的文件
  (utils/clear-db-file restored-db-filename) ; 恢复测试用的文件
  (f) ; 执行测试主体
  (timbre/info "=== 测试 Fixture 清理 (已调整) ===")
  (utils/clear-db-file test-db-filename)
  (utils/clear-db-file utils/default-db-filename)
  (utils/clear-db-file restored-db-filename))

;; 使用 :once Fixture，它会在当前命名空间所有测试运行前执行一次 setup，所有测试结束后执行一次 teardown。
;; 如果每个 deftest 需要更严格的隔离，可以使用 :each。
(use-fixtures :once test-fixture-adjusted)

;; --- 测试场景 ---

(deftest ^:performance static-db-backup-restore-performance-adjusted
  (timbre/info "\n--- 开始测试：静态数据库备份与恢复性能 (已调整) ---")
  (doseq [target-size-mb db-sizes-mb]
    (testing (str "数据库大小: " target-size-mb " MB")
      (timbre/info (format "--- 测试场景：静态数据库，目标大小 %.2f MB ---" target-size-mb))
      (utils/clear-minio-bucket-path) ; 确保每次测试大小前 MinIO 存储桶是干净的
      (utils/clear-db-file test-db-filename)
      (utils/clear-db-file utils/default-db-filename)
      (utils/clear-db-file restored-db-filename)

      (let [original-size-mb (utils/populate-db-to-size test-db-filename target-size-mb) ; 生成测试数据库
            _ (is (some? original-size-mb) (str "原始数据库 " test-db-filename " 应当成功创建"))
            _ (timbre/info (format "原始数据库 '%s' 已创建，实际大小: %.2f MB" test-db-filename original-size-mb))]

        ; 将生成的测试数据库复制到 litestream 监控的路径
        (copy-file test-db-filename utils/default-db-filename)
        (is (.exists (io/file utils/default-db-filename)) "数据库文件应当已复制到 Litestream 的监控路径")

        (timbre/info "启动 Litestream replicate 进行备份...")
        (let [ls-process (utils/start-litestream-replicate litestream-config-path)]
          (is (some? ls-process) "Litestream replicate 进程应当成功启动")
          (timbre/info "等待 Litestream 完成初始同步 (根据 sync-interval 和 snapshot-interval 调整等待时间)...")
          (Thread/sleep 5000) ; 等待 5 秒，确保至少一次同步和快照（如果配置较快）
          (utils/stop-litestream-process ls-process)
          (timbre/info "Litestream replicate 进程已停止。"))

        ; 删除本地被监控的数据库文件，模拟数据丢失场景
        (utils/clear-db-file utils/default-db-filename)
        (is (not (.exists (io/file utils/default-db-filename))) "被 Litestream 监控的数据库文件应当在恢复前被删除")

        (timbre/info "正在从 Litestream 恢复数据库...")
        (let [restore-result (utils/litestream-restore restored-db-filename litestream-config-path) ; 将备份恢复到 restored-db-filename
              restored-size-mb (utils/get-file-size-mb restored-db-filename)]
          (is (:success restore-result) "Litestream restore 命令应当成功执行。")
          (is (some? restored-size-mb) (str "恢复后的数据库 " restored-db-filename " 应当存在"))
          (timbre/info (format "数据库恢复完成，耗时 %.2f ms。恢复后的文件大小: %.2f MB"
                               (:duration-ms restore-result)
                               (or restored-size-mb 0)))

          ; 验证恢复后的文件大小与原始文件大小是否大致相符
          (when (and original-size-mb restored-size-mb)
            (is (> restored-size-mb (* original-size-mb 0.8)) ; 恢复后的文件大小应至少是原始大小的80%
                (format "恢复后的文件大小 %.2f MB 与原始文件大小 %.2f MB 相差过大"
                        restored-size-mb original-size-mb))))
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
        _ (timbre/info (format "为动态测试准备基础数据库，目标大小 %.2f MB" base-db-size-mb))
        _ (utils/clear-db-file test-db-filename)
        _ (utils/clear-db-file utils/default-db-filename)
        _ (utils/populate-db-to-size test-db-filename base-db-size-mb)
        _ (copy-file test-db-filename utils/default-db-filename) ; 初始复制到 Litestream 监控路径
        db-spec (utils/get-db-spec utils/default-db-filename) ; 写入操作将发生在这个被监控的数据库上
        ]

    (doseq [freq write-frequencies-hz]
      (testing (str "写入频率: " freq " Hz")
        (timbre/info (format "--- 测试场景：动态数据库，写入频率 %d Hz ---" freq))
        (utils/clear-minio-bucket-path) ; 为每个频率测试清理 MinIO

        ; 为确保每个频率测试的独立性，重新生成和复制基础数据库
        (timbre/info "重新生成基础数据库以进行新的频率测试...")
        (utils/populate-db-to-size test-db-filename base-db-size-mb)
        (copy-file test-db-filename utils/default-db-filename)

        (let [initial-rows (:count (sql/query (jdbc/get-datasource db-spec) ["SELECT COUNT(*) as count FROM records"]))
              _ (timbre/info (format "数据库 '%s' (被 Litestream 监控) 中的初始行数: %d" utils/default-db-filename initial-rows))]

          (timbre/info (str "启动 Litestream replicate 进行动态测试 (频率: " freq " Hz)..."))
          (let [ls-process (utils/start-litestream-replicate litestream-config-path)]
            (is (some? ls-process) "Litestream replicate 进程应当为动态测试成功启动")

            (timbre/info (format "开始以 %d Hz 的频率向数据库 '%s' 持续写入 %d 秒..." freq utils/default-db-filename continuous-write-duration-seconds))
            (let [writes-performed (perform-writes db-spec freq continuous-write-duration-seconds)]
              (timbre/info (format "在数据库 '%s' 中执行了 %d 次写入操作。" writes-performed utils/default-db-filename))
              (timbre/info "等待 Litestream 同步最后的更改...")
              (Thread/sleep 2000) ; 等待 2 秒，让 Litestream 同步最后的数据
              (utils/stop-litestream-process ls-process)
              (timbre/info "Litestream replicate 进程已为动态测试停止。")

              (let [rows-after-writes (:count (sql/query (jdbc/get-datasource db-spec) ["SELECT COUNT(*) as count FROM records"]))]
                (timbre/info (format "数据库 '%s' 在写入操作后的总行数: %d" utils/default-db-filename rows-after-writes))
                (is (= (+ initial-rows writes-performed) rows-after-writes) "被监控数据库的行数应当准确反映所有写入操作"))

              ; 删除本地被监控的数据库文件
              (utils/clear-db-file utils/default-db-filename)
              (is (not (.exists (io/file utils/default-db-filename))) "被监控的数据库文件应当在恢复前被删除 (动态测试)")

              (timbre/info "正在从 Litestream 恢复动态写入后的数据库...")
              (let [restore-result (utils/litestream-restore restored-db-filename litestream-config-path)
                    restored-ds (jdbc/get-datasource (utils/get-db-spec restored-db-filename))
                    rows-in-restored-db (:count (sql/query restored-ds ["SELECT COUNT(*) as count FROM records"]))]
                (is (:success restore-result) "Litestream restore 命令应当成功执行 (动态测试)。")
                (timbre/info (format "数据库恢复 (动态测试) 完成，耗时 %.2f ms。恢复后的数据库中的行数: %d"
                                     (:duration-ms restore-result)
                                     rows-in-restored-db))
                (is (= (+ initial-rows writes-performed) rows-in-restored-db)
                    "恢复后的数据库应当包含所有基础数据和动态写入的数据行。"))))
          (timbre/info (format "--- 完成动态数据库测试 (写入频率 %d Hz) ---" freq)))))))

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
