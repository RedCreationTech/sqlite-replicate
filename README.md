# sqlite-replicate

方案概览：Clojure + Litestream + MinIO (可执行文件版)

这个架构的核心思想保持不变：解耦您的应用和数据复制过程。

Clojure 应用: 您的应用将像往常一样与本地 SQLite 数据库文件交互。您无需改动现有的数据访问代码 。   
MinIO (minio.exe): 我们将直接运行 MinIO 的 Windows 可执行文件。它会作为一个本地进程，提供一个与 Amazon S3 完全兼容的对象存储服务，Litestream 将把数据复制到这里。
Litestream: 作为一个独立的 Windows 服务，它会监控您的 SQLite 数据库的预写日志（WAL）文件，并将增量变更近乎实时地流式传输到本地运行的 MinIO 实例 。   
这个组合为您提供了一个强大的灾难恢复（Disaster Recovery）解决方案。

第1步：环境准备

请确保您的 Windows 开发环境中已安装或下载以下软件：

Java 和 Clojure: 一个正常工作的 Clojure 开发环境 (Leiningen 或 Clojure CLI)。
Litestream: 从 Litestream 发布页面下载并运行 .msi 安装程序。这会将其安装为一个 Windows 服务。
MinIO for Windows: 从官方网站下载 minio.exe 可执行文件。
下载链接: https://dl.min.io/server/minio/release/windows-amd64/minio.exe    
下载后，将 minio.exe 放置在一个方便的目录，例如 C:\minio\。
第2步：启动并配置 MinIO (.exe 版)

 我们将直接从命令行启动 MinIO 服务器。

 创建数据存储目录:
为 MinIO 创建一个用于存放数据的文件夹。例如：C:\minio-data。

 启动 MinIO 服务器:
打开您的命令行工具（推荐使用 PowerShell），导航到您存放 minio.exe 的目录（例如 C:\minio\），然后运行以下命令 ：   

 .\minio.exe server C:\minio-data --console-address ":9001"
```

 这个命令会启动一个 MinIO 实例：
*   它将所有数据存储在 `C:\minio-data` 目录中。
*   S3 API 端口默认为 `9000`。
*   `--console-address ":9001"` 指定 Web 管理控制台运行在 `9001` 端口 [5]。

**重要提示:** 首次运行时，MinIO 会在命令行窗口中打印出**RootUser**（Access Key）和**RootPass**（Secret Key）。**请务必记下这些凭据**，默认是 `minioadmin` / `minioadmin` [3]。
创建 S3 存储桶 (Bucket):
在浏览器中打开 http://localhost:9001。
使用上一步中获得的凭据登录。
登录后，点击 "Create Bucket" 按钮，创建一个新的存储桶。我们将其命名为 clojure-db-replica。
 现在，您的 S3 兼容后端已经通过 .exe 文件成功运行。

第3步：配置 Clojure 项目

 这部分与之前的方案完全相同。我们将配置一个 Clojure 项目，使其能够与 SQLite 交互，并为 Litestream 的无缝工作做好准备。

 创建项目并添加依赖:
在您的 deps.edn 文件中添加 next.jdbc 和 SQLite JDBC 驱动的依赖。

Clojure
;; deps.edn
{:paths ["src"]
 :deps  {org.clojure/clojure        {:mvn/version "1.12.0"}
         com.github.seancorfield/next.jdbc {:mvn/version "1.3.925"}
         org.xerial/sqlite-jdbc     {:mvn/version "3.46.0.0"}}}
 编写 Clojure 数据库访问代码:
创建 src/myapp/db.clj 文件。这段代码将包含连接数据库、设置必要的 PRAGMA 指令以及执行基本 CRUD 操作的函数。

Clojure
;; src/myapp/db.clj
(ns myapp.db
  (:require [next.jdbc :as jdbc]
            [next.jdbc.sql :as sql]))

;; 定义数据库规格，指向一个本地文件
(def db-spec {:dbtype "sqlite" :dbname "app-data.db"})

;; 获取数据源
(def ds (jdbc/get-datasource db-spec))

(defn set-pragmas!
  "为 Litestream 设置必要的 PRAGMA。必须在每个新连接上执行。"
  [conn]
  ;; 1. 启用 WAL 模式，这是 Litestream 工作的前提
  (jdbc/execute! conn)
  ;; 2. 设置忙碌超时，防止 Litestream 在检查点时因数据库锁定而失败
  (jdbc/execute! conn)
  ;; 3. 设置同步模式为 NORMAL，在 WAL 模式下是安全的，可以显著提高写入性能
  (jdbc/execute! conn)
  ;; 4. 禁用 SQLite 的自动检查点，将控制权完全交给 Litestream
  (jdbc/execute! conn)
  (println "PRAGMA settings for Litestream applied."))

(defn initialize-database!
  "创建表结构并设置 PRAGMA"

  (with-open [conn (jdbc/get-connection ds)]
    (set-pragmas! conn)
    (jdbc/execute! conn)))

(defn add-user!
  "添加一个新用户"
  [user-map]
  (with-open [conn (jdbc/get-connection ds)]
    (set-pragmas! conn) ; 确保每个写操作的连接都设置了 PRAGMA
    (sql/insert! conn :users user-map)))

(defn get-all-users
  "获取所有用户"

  (sql/query ds))

;; 主函数，用于测试
(defn -main [& args]
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
 关键点: set-pragmas! 函数至关重要。由于 JDBC 连接池可能会创建新连接，最佳实践是在每次从连接池获取连接后都执行这些 PRAGMA 指令，以确保 Litestream 能够正确工作。

第4步：配置并运行 Litestream

 现在，我们将配置 Litestream，使其监控我们的数据库文件并将变更复制到本地运行的 MinIO 实例。

 创建 Litestream 配置文件:
在 Windows 上，Litestream 的默认配置文件路径是 C:\Litestream\litestream.yml。创建或编辑此文件，并填入以下内容：

YAML
# C:\Litestream\litestream.yml

# MinIO 的凭据。也可以通过环境变量设置。
access-key-id: minioadmin
secret-access-key: minioadmin

dbs:
  - path: C:\path\to\your\clojure-project\app-data.db #!重要: 替换为您的数据库文件的绝对路径
    replicas:
      - name: minio-replica
        type: s3
        bucket: clojure-db-replica #!重要: 必须与您在 MinIO 中创建的存储桶名称匹配
        path: database           # 在存储桶内用于存放副本的子目录名
        endpoint: http://127.0.0.1:9000 #!重要: 指向本地 MinIO API 端点
        force-path-style: true          # S3 兼容存储通常需要此项

        # 推荐的调优参数
        snapshot-interval: 1h      # 每小时创建一个完整的数据库快照
        retention: 24h             # 保留过去24小时的所有WAL日志和快照
 请务必将 dbs.path 替换为您 Clojure 项目中 app-data.db 文件的实际绝对路径。

 启动 Litestream 服务:
配置完成后，您需要重启 Litestream 服务以加载新配置。

打开 Windows 的“服务”应用。
找到 "Litestream" 服务，右键点击并选择“重新启动”。
或者，使用管理员权限的 PowerShell： Restart-Service Litestream。
第5步：完整测试流程

 现在，所有组件都已配置完毕，让我们来执行一个端到端的测试。

 运行 Clojure 应用:
在您的 Clojure 项目根目录下，运行主函数来创建数据库并插入一些数据：

Bash
clj -M -m myapp.db
 执行后，您应该会看到 app-data.db 文件出现在项目目录中，并且控制台会打印出已插入的用户信息。

 验证 Litestream 复制:

检查 Litestream 日志: 在 Windows 事件查看器中，查看“应用程序”日志，筛选来源为 "Litestream" 的事件，确认没有错误信息。
检查 MinIO 存储桶: 刷新 http://localhost:9001 上的 MinIO 控制台。在 clojure-db-replica 存储桶中，您应该能看到一个名为 database 的新目录，其中包含了 generations、snapshots 和 wal 等子目录。这证明 Litestream 正在成功复制数据。
 向数据库写入更多数据:
再次运行您的 Clojure 程序，或者在 REPL 中调用 (myapp.db/add-user! {:name "Charlie" :email "charlie@example.com"})。Litestream 会自动检测到新的写入，并将变更的 WAL 页面传输到 MinIO。

 模拟灾难:
现在，模拟主数据库文件被意外删除的场景。

首先，停止您的 Clojure 应用和 Litestream 服务 (Stop-Service Litestream)。
然后，删除项目目录下的 app-data.db, app-data.db-wal, 和 app-data.db-shm 文件。
 从 MinIO 恢复数据库:
打开命令行，执行 Litestream 的 restore 命令。

PowerShell
# 确保命令在您的项目根目录下执行
litestream restore -o app-data.db s3://clojure-db-replica/database
-o app-data.db: 指定恢复后的数据库文件名。
s3://clojure-db-replica/database: 指向您在 MinIO 中的副本路径。
 验证恢复结果:
恢复完成后，您的项目目录中会重新出现 app-data.db 文件。现在，您可以再次运行 Clojure 程序（或在 REPL 中）来查询数据：

Clojure
;; 在 REPL 中
(require '[myapp.db :as db])
(db/get-all-users)
 您应该能看到所有用户的数据，包括 "Charlie"，证明数据库已成功恢复到灾难发生前的最新状态。

第6步：让 MinIO 持久运行 (推荐)

 在命令行窗口中运行 minio.exe 非常适合临时测试，但一旦关闭窗口，服务就会停止。为了进行更长时间的测试，您可以使用 Windows 任务计划程序让它在系统启动时自动运行 。   

 创建批处理文件:
在您的 MinIO 目录 (例如 C:\minio) 中，创建一个名为 start-minio.bat 的文件，内容如下：

Code snippet
@ECHO OFF
C:\minio\minio.exe server C:\minio-data --console-address ":9001"
 创建计划任务:

打开“任务计划程序”。
在右侧“操作”面板中，点击“创建基本任务”。
名称: 输入 Start MinIO Server。
触发器: 选择“计算机启动时”。
操作: 选择“启动程序”。
程序/脚本: 浏览并选择您刚刚创建的 start-minio.bat 文件。
完成向导。
最后，打开该任务的属性，在“常规”选项卡中，勾选“使用最高权限运行”，并选择“不管用户是否登录都运行”。
 现在，每次您的 Windows 机器启动时，MinIO 服务器都会在后台自动运行。

 这个可操作方案为您提供了一个坚实的基础，让您可以充满信心地在 Clojure 和 SQLite 上构建可靠、可恢复的系统。

