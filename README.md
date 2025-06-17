# sqlite-replicate

本示例展示如何在 Windows 环境中利用 **Clojure + SQLite + Litestream + MinIO** 实现本地数据库的增量备份与恢复。下文将分步骤说明环境配置、项目结构以及常见操作流程。

## 将本项目用作库 (Using this library)

本项目可以作为 Clojure 库使用，以便将 SQLite 复制（replication）功能集成到您自己的应用程序中。

### 添加依赖 (Add Dependency)

将以下依赖项添加到您的 `deps.edn` 文件中：

```clojure
net.clojars.zhaoyul/sqlite-replicate {:mvn/version "0.1.0-SNAPSHOT"}
; 发布后请替换为实际版本号
```

### 配置 (Configuration)

您的应用程序需要提供以下配置，通常通过环境变量、配置文件或传递给库的函数来实现。

**1. MinIO 配置 (MinIO Configuration):**

该库需要连接到 MinIO S3 兼容存储的详细信息。可以作为映射 (map) 或单独的值提供：

*   `s3-access-key-id`: 您的 MinIO access key。
*   `s3-secret-access-key`: 您的 MinIO secret key。
*   `s3-endpoint`: MinIO 服务器的 URL (例如, `http://localhost:9000`)。
*   `s3-bucket`: 用于复制的 bucket 名称 (例如, `clojure-db-replica`)。
*   `s3-path`: (可选) bucket 内数据库副本存储路径 (例如, `database/my-app`)。默认为 `database`。

**配置示例 (例如，在 EDN 文件中或作为 map 传递):**
```clojure
{:minio-config {:s3-access-key-id "minioadmin"
                :s3-secret-access-key "minioadmin"
                :s3-endpoint "http://localhost:9000"
                :s3-bucket "clojure-db-replica"
                :s3-path "myapp_prod_db"}}
```

**2. SQLite 数据库路径 (SQLite Database Path):**

*   `db-path`: SQLite 数据库文件的本地文件系统路径 (例如, `/path/to/your/app-data.db` 或 `C:\\path\\to\\your\\project\\app-data.db`)。

**3. Litestream 配置文件 (`litestream.yml`):**

该库依赖 Litestream 进行实际的复制操作。您需要提供一个 `litestream.yml` 配置文件。如果您提供此配置文件的路径，该库可以帮助管理 Litestream。

*   `litestream-config-path`: 指向您的 `litestream.yml` 文件的路径。

**`litestream.yml` 结构示例:**

确保您的 `litestream.yml` 配置了 MinIO 的详细信息，并指向您应用程序的数据库。该库将主要使用此文件来了解您的 Litestream 设置。

```yaml
# litestream.yml
access-key-id: ${MINIO_ACCESS_KEY_ID}  # 建议使用环境变量存储敏感信息
secret-access-key: ${MINIO_SECRET_ACCESS_KEY}

dbs:
  - path: /path/to/your/app-data.db # 此路径必须与您应用程序的数据库路径匹配
    replicas:
      - name: s3-main # 或任意名称
        type: s3
        bucket: ${MINIO_BUCKET} # 例如, clojure-db-replica
        path: ${MINIO_DB_PATH_IN_BUCKET} # 例如, myapp_prod_db
        endpoint: ${MINIO_ENDPOINT} # 例如, http://127.0.0.1:9000
        force-path-style: true # 通常对于 MinIO 设置为 true
        # 推荐设置，请根据需要调整
        sync-interval: 1s       # Litestream v0.3.9+ 新增，检查变更的频率
        snapshot-interval: 1h
        retention: 24h
```
**注意:** 强烈建议在 `litestream.yml` 中对敏感信息（如 access key 和 secret key）使用环境变量（如 `${...}` 所示），而不是硬编码。然后，您的应用程序将负责在运行 Litestream 命令之前设置这些环境变量。

###核心函数用法 (Core Functions Usage)

**1. 初始化数据库 (可选):**
如果您的应用程序尚未管理其数据库结构 (schema)，您可以使用：
```clojure
(require '[myapp.db :as app-db])

; 定义您的 db-spec，类似于 myapp.db 中的做法
(def my-db-spec {:dbtype "sqlite" :dbname "/path/to/your/app-data.db"})

(app-db/initialize-database! my-db-spec) ; 传递您的 db-spec
```
此函数会创建必要的表（`users`, `events`）（如果它们不存在），并应用 Litestream 特定的 PRAGMA 设置。许多应用程序会自行处理其数据库结构迁移。

**2. 启动 HTTP 服务 (主节点):**
该库提供了一个简单的 HTTP 服务器 (使用 `http-kit`)，带有一个 `/health` 健康检查端点。
```clojure
(require '[myapp.service :as app-service])

; 服务配置
(def service-config {:port 3001 ; 健康检查服务器的目标端口
                     ; 其他可能的配置，例如服务直接需要的 db-spec
                    })

(app-service/start-server service-config) ; 返回一个停止服务器的函数
```
通常，您会在主应用程序节点上运行此服务。

**3. 启动后台写入程序 (主节点, 可选):**
此函数会启动一个后台线程，定期将时间戳写入 `events` 表。这主要用于演示目的，以确保数据正在被复制。
```clojure
(require '[myapp.service :as app-service])
(require '[myapp.db :as app-db]) ; 如果需要传递 db-spec

(app-service/start-writer {:db-spec my-db-spec}) ; 传递供写入程序使用的 db-spec
```

**4. 启动 Litestream 复制 (主节点):**
该库本身不直接捆绑一个作为守护进程持续运行 `litestream replicate` 的函数，因为这通常由系统服务（如 Linux 上的 systemd 或 Windows 服务）管理。但是，您的应用程序可以调用 Litestream：

*   确保 Litestream 已安装，并已配置好上述的 `litestream.yml`。
*   您的应用程序可以执行 Litestream 命令。为了运行 Litestream 进行数据复制，您需要启动 `litestream replicate` 守护进程。我们提供了示例脚本 `examples/scripts/run_litestream.sh` (Linux/macOS) 和 `examples/scripts/run_litestream.bat` (Windows) 来帮助您开始。请根据您的 `litestream.yml` 文件位置修改脚本中的 `LITESTREAM_CONFIG_PATH` 变量。
    ```bash
    # 例如，在 Linux/macOS 上:
    # ./examples/scripts/run_litestream.sh

    # 或者直接执行 Litestream 命令:
    litestream replicate -config /path/to/your/litestream.yml
    ```
    如果需要，您可以使用 `clojure.java.shell/sh` 从 Clojure 应用程序运行此命令，但将其作为独立的、受管理的进程运行对于生产环境更为稳健。

**5. 备用节点监控与故障转移:**
备用节点功能允许一个节点监控主节点，并在主节点变得不健康时尝试进行故障转移。

```clojure
(require '[myapp.standby :as app-standby])

(def standby-config
  {:primary-health-url "http://primary-node-ip:3001/health" ; 主节点健康检查 URL
   :health-check-interval-ms 5000 ; 每5秒检查一次
   :db-path "/path/to/local/standby-app-data.db" ; 本地备用数据库路径
   :litestream-config-path "/path/to/standby/litestream.yml" ; 用于恢复的 Litestream 配置
   :s3-bucket "clojure-db-replica" ; 用于 litestream restore 命令
   :s3-path "myapp_prod_db"      ; 用于 litestream restore 命令
   :s3-endpoint "http://localhost:9000" ; 用于 litestream restore 命令
   ; 用于 litestream restore 的 MinIO密钥应位于环境变量或 litestream.yml 中
   :post-failover-actions {:start-server? true     ; 恢复后启动本地 HTTP 服务
                           :server-config {:port 3001}
                           :start-writer? true     ; 恢复后启动本地写入程序
                           :writer-db-spec {:dbtype "sqlite" :dbname "/path/to/local/standby-app-data.db"}}})

; 启动监控。这可能会在一个循环或计划线程中运行。
; 精确的函数签名可能需要从当前的 -main 函数调整而来。
; 例如，一个接受配置并返回 future 或控制 atom 的函数。
(app-standby/start-monitoring standby-config)
```
`myapp.standby` 命名空间将需要一个类似 `start-monitoring` 的函数，该函数封装来自 `-main` 的循环并且是可配置的。备用逻辑中的恢复命令将使用配置的 S3 部分构建。

### 重要注意事项 (Important Considerations):
*   **错误处理 (Error Handling):** 应围绕 I/O 操作、服务启动和外部进程调用添加稳健的错误处理。
*   **配置管理 (Configuration Management):** 使用适当的配置库或系统（如环境变量、Integrant、Mount）来管理设置。
*   **幂等性 (Idempotency):** 在可能的情况下，确保操作是幂等的。
*   **安全性 (Security):** 安全地管理密钥（MinIO 密钥），最好在版本控制之外（例如，使用注入到 Litestream 和应用程序中的环境变量）。

### 在 Luminus 项目中使用 (Using in a Luminus Project)

Luminus 项目通常使用 `mount` 来管理应用程序状态和组件生命周期。以下是如何将此库集成到 Luminus 项目中的概念性示例。

**1. 添加依赖:**

在您的 `deps.edn` 文件中添加依赖：
```clojure
;; deps.edn
net.clojars.zhaoyul/sqlite-replicate {:mvn/version "0.1.0-SNAPSHOT"} ; 请替换为最新版本
```

如果您仍在使用 Leiningen (`project.clj`)：
```clojure
;; project.clj
[net.clojars.zhaoyul/sqlite-replicate "0.1.0-SNAPSHOT"] ; 请替换为最新版本
```

**2. 配置:**

Luminus 项目通常将配置存储在 `config.edn` 中，并通过 `myapp.config/env` 访问。您可以在此处定义数据库路径、MinIO 连接信息以及 Litestream 配置文件路径。

例如，在您的 `config.edn` 中：
```clojure
{:database {:path "/path/to/your/luminus-app.db"}
 :health-check-port 3001 ; 或您选择的任何端口
 :litestream-config-path "/path/to/your/litestream.yml"
 :minio-config {:s3-access-key-id "your-minio-access-key"
                :s3-secret-access-key "your-minio-secret-key"
                :s3-endpoint "http://localhost:9000"
                :s3-bucket "your-luminus-bucket"
                :s3-path "luminus_db_replica"}
 ;; ... 其他 Luminus 配置
 }
```

**3. 使用 Mount `defstate` 初始化组件:**

在您的 Luminus 项目的核心命名空间或专门的组件命名空间中，您可以使用 `mount/defstate` 来管理本库提供的组件。

```clojure
(ns myapp.core ; 或例如 myapp.components.database, myapp.components.replication
  (:require [mount.core :as mount]
            [myapp.config :refer [env]] ; Luminus 项目的配置
            ;; 假设本库的命名空间调整为更符合 Clojars 规范
            [net.clojars.zhaoyul.sqlite-replicate.db :as sr-db]
            [net.clojars.zhaoyul.sqlite-replicate.service :as sr-service]
            ;; 如果需要备用节点监控功能:
            ;; [net.clojars.zhaoyul.sqlite-replicate.standby :as sr-standby]
            ))

;; 初始化数据库 (如果需要库来处理 PRAGMA 设置等)
(mount/defstate app-sqlite-db
  :start (sr-db/initialize-database!
           {:dbtype "sqlite" :dbname (-> env :database :path)})
  :stop  nil) ; 您可以根据需要添加停止逻辑，例如关闭连接池（如果适用）

;; 启动健康检查服务 (如果您的 Luminus 应用需要此独立的健康检查服务)
;; 注意: Luminus 自身会创建一个 Web 服务器。如果仅需健康检查，
;;       可以考虑将健康检查逻辑集成到 Luminus 的路由中，
;;       或仅在主节点且不使用 Luminus 自带 Web 服务时启动此服务。
(mount/defstate health-check-server
  :start (let [port (-> env :health-check-port)]
           (println (str "Starting health-check server on port " port))
           (sr-service/start-server {:port port}))
  :stop  (when health-check-server ; 确保 health-check-server 已启动
           (println "Stopping health-check server...")
           (sr-service/stop-server health-check-server))) ; 假设 sr-service/stop-server 接受服务器实例

;; 后台写入程序 (可选, 主要用于测试复制)
(mount/defstate background-writer
  :start (let [db-path (-> env :database :path)]
            (println "Starting background writer for database:" db-path)
            (sr-service/start-writer {:db-spec {:dbtype "sqlite" :dbname db-path}}))
  :stop (when background-writer
          (println "Stopping background writer...")
          (sr-service/stop-writer background-writer))) ; 假设 sr-service/stop-writer 接受 writer 实例


;; 启动应用程序 (示例)
(defn -main [& args]
  (mount/start)
  ;; 应用程序的其他启动逻辑
  )
```

**4. Litestream 进程管理:**

如前所述，`litestream replicate` 进程通常独立于 Clojure 应用程序运行。您应该使用 `examples/scripts/run_litestream.sh` 或 `examples/scripts/run_litestream.bat` （根据您的 `litestream.yml` 路径进行修改）来启动和管理 Litestream 守护进程。

您的 Luminus 应用程序的部署脚本或进程管理器 (如 systemd, Docker Compose) 应负责同时启动您的 Luminus 应用和相关的 Litestream 进程。

**5. 备用节点 (Standby) 功能:**

如果要在 Luminus 应用中使用备用节点监控和自动故障转移功能：
```clojure
;; 在 myapp.core 或相关组件命名空间
;; (require '[net.clojars.zhaoyul.sqlite-replicate.standby :as sr-standby])

;; (mount/defstate standby-monitor
;;   :start (let [config {:primary-health-url (-> env :standby :primary-url)
;;                       :health-check-interval-ms (-> env :standby :check-interval 5000)
;;                       :db-path (-> env :database :path) ; 本地数据库路径
;;                       :litestream-config-path (-> env :litestream-config-path)
;;                       :s3-bucket (-> env :minio-config :s3-bucket)
;;                       :s3-path (-> env :minio-config :s3-path)
;;                       :s3-endpoint (-> env :minio-config :s3-endpoint)
;;                       :post-failover-actions {
;;                         :start-server? true
;;                         :server-config {:port (-> env :health-check-port)}
;;                         :start-writer? (-> env :standby :start-writer-after-failover true)
;;                         :writer-db-spec {:dbtype "sqlite" :dbname (-> env :database :path)}}}]
;;            (println "Starting standby monitoring...")
;;            (sr-standby/start-monitoring config)) ; 假设 start-monitoring 返回控制句柄或 future
;;   :stop (when standby-monitor
;;           (println "Stopping standby monitoring...")
;;           ;; (sr-standby/stop-monitoring standby-monitor) ; 需要实现停止逻辑
;;           ))
```
上述备用节点示例是概念性的。您需要确保 `sr-standby/start-monitoring` 是非阻塞的（例如，在单独的线程中运行其循环），并且可以被 `mount/stop` 停止。`myapp.standby` 中的 `-main` 函数需要重构为一个可由库用户调用的函数，并提供停止机制。

通过这种方式，您可以将 SQLite 数据库的初始化、可选的健康检查服务以及 Litestream 的（间接）管理集成到 Luminus 应用的生命周期中。

## 开发与部署 (Development and Deployment)

本节介绍如何运行单元测试、打包项目以及将其部署到 Clojars。

### 运行单元测试 (Running Unit Tests)

项目包含一套单元测试，可以使用以下命令运行：

```bash
clojure -M:test
```
此命令会执行 `test/runner.clj` 中定义的测试运行器，它将加载并运行所有在 `myapp.*-test` 命名空间模式下的测试。请确保所有测试通过后再进行打包或部署。

### 本地打包 (Local Packaging)

要将此库打包为 JAR 文件以供本地使用或部署，我们现在使用 `clojure.tools.build`。相关的构建任务定义在项目根目录的 `build.clj` 文件中，并通过 `deps.edn` 中的 `:build` alias 调用。

**1. 清理旧的构建产物 (可选):**
```bash
clojure -T:build clean
```
此命令会删除 `target` 目录。

**2. 生成 `pom.xml`:**

`pom.xml` 文件包含了项目的元数据，是打包和部署所必需的。`build.clj` 中的 `pom` 任务会根据 `deps.edn` 文件中的 `:pom-data` 部分自动生成此文件。
```bash
clojure -T:build pom
```
执行此命令后，`pom.xml` 文件将生成在项目根目录 (`pom.xml`)。

**3. 打包 JAR 文件:**

生成 `pom.xml` 后，可以打包 JAR 文件。`jar` 任务会先执行 `clean` 和 `pom`。
```bash
clojure -T:build jar
```
此命令会将编译后的代码和源文件打包。JAR 文件将根据 `deps.edn` 中 `:pom-data` 的 `:lib` 和 `:version` 命名，并输出到 `target/` 目录，例如 `target/sqlite-replicate-0.1.0-SNAPSHOT.jar`。

生成的 JAR 文件位于 `target/` 目录下。

### 部署到 Clojars (Deploying to Clojars)

部署到 Clojars 需要您拥有一个 Clojars 账户，并在本地配置好 Clojars API Token (通常通过 `~/.clojars/credentials.clj` 文件或环境变量 `CLOJARS_USERNAME` / `CLOJARS_TOKEN`)。

本项目使用 `deps-deploy` 工具进行部署。`deps.edn` 中已配置好 `:deploy` alias。

**1. 确保版本号已更新:**

在部署新版本之前，请务必更新 `deps.edn` 文件中 `:pom-data` 下的 `:version` 字段。例如，从 `"0.1.0-SNAPSHOT"` 更新到 `"0.1.0"` (或者其他非快照版本)。同时，您可能也需要更新 `build.clj` 中定义的 `version` 变量（如果它不是动态读取 `deps.edn` 的话 - 当前 `build.clj` 实现是动态读取的）。

**2. 生成最新的 `pom.xml` 和 JAR 文件:**

按照新的 "本地打包" 部分的说明，使用更新后的版本号（已在 `deps.edn` 中修改）通过 `clojure.tools.build` 重新生成 `pom.xml` 和 JAR 文件。

```bash
clojure -T:build clean # 清理旧产物
clojure -T:build jar   # 会自动先生成 pom.xml，然后打包 jar
```
(确保 `deps.edn` 中的 `:version` 已更新为你希望发布的版本。`build.clj` 会读取此版本。)

**3. 执行部署:**

您可以使用以下两种方式之一进行部署：

**方式一：使用部署脚本（推荐）**

项目提供了一个交互式部署脚本，它会提示您输入 Clojars 用户名和 API token：

```bash
./deploy.sh
```

此脚本会提示您输入凭据，设置必要的环境变量，执行部署，然后清除环境变量。

**方式二：直接使用 Clojure CLI**

如果您已经设置了环境变量 `CLOJARS_USERNAME` 和 `CLOJARS_PASSWORD`（实际上是您的 API token），可以直接运行：

```bash
clojure -M:deploy
```
此命令会读取 `pom.xml` 文件，找到对应的 JAR 包 (通常在 `target/` 目录下，名称与 `pom.xml` 中的 artifactId 和 version 一致)，然后将其上传到 Clojars。
注意：我们使用 `clojure -M:deploy` 而不是 `-X` 是因为 `:deploy` alias 在 `deps.edn` 中配置的是 `:main-opts`。

如果您需要对发布进行 GPG 签名，可以在 `:deploy` alias 的 `:main-opts` 中添加相关参数，如 `"--sign-releases" "--gpg-key" "YOUR_GPG_KEY_ID"`，并确保本地 GPG 环境已配置正确。

部署成功后，您的库就可以被其他 Clojure 项目作为依赖引用了。

## 1. 环境准备
1. 安装 Clojure（推荐使用 [Clojure CLI](https://clojure.org/guides/getting_started) 或 Leiningen）。
2. 从 [Litestream 官网](https://litestream.io/) 下载并安装 Windows 版 `.msi`，安装后会在系统服务列表中出现 *Litestream*。
3. 从 [MinIO 官网](https://min.io/) 下载 `minio.exe`，并放置于 `C:\minio\`（或其他目录）。

## 2. 启动 MinIO
```powershell
# 创建数据目录
mkdir C:\minio-data

# 进入 minio.exe 所在目录并启动
cd C:\minio
.\u005cminio.exe server C:\minio-data --console-address ":9001"
```
首次运行会在控制台显示 `RootUser` 和 `RootPass`（默认为 `minioadmin:minioadmin`）。随后在浏览器访问 <http://localhost:9001> 并创建名为 `clojure-db-replica` 的 bucket。

## 3. Clojure 项目结构 (Standalone Example)
本仓库已包含最小化的 deps.edn 与示例代码，用于演示独立运行本项目时的场景：

```
├─deps.edn
└─src
   └─myapp
      └─db.clj
      └─service.clj
      └─standby.clj
```
`deps.edn` 声明依赖 `next.jdbc` 与 `sqlite-jdbc` 等。`src/myapp/db.clj` 实现了简单的用户表读写逻辑并应用 Litestream 所需的 PRAGMA 设置。

### 运行独立示例 (Standalone Example)
**注意:** 以下说明适用于将项目作为独立应用程序运行的情况，主要用于演示或库本身的开发。当将此项目用作库时，请参阅上面的“将本项目用作库”部分。

在项目根目录执行初始化数据库 (会生成 `app-data.db`):
```bash
clojure -M:run
; 此别名通常调用 myapp.db/-main，该函数会初始化并添加一些数据
```
执行后会生成 `app-data.db` 并打印插入与查询结果。

## 4. 启动 HTTP 服务并持续写入数据库 (Standalone Example)

项目还提供了 `myapp.service`，启动后会在 `3000` 端口暴露 `/health` 接口 (健康检查端点)，并每秒向 `events` 表写入当前时间戳。

```bash
clojure -M:service
```

访问 <http://localhost:3000/health> 可得到 `OK`，同时可在数据库中看到不断新增的记录。
(注意: 如库示例中所示，端口可能是3001；如果独立运行，请从 `myapp.service` 中确认。)

### 单机运行两个服务与一个 MinIO (Standalone Example)

若要在单机测试多实例，可复制项目目录两份，例如 `instance-a/` 与 `instance-b/`，分别启动 Litestream 和 `myapp.service`，但共用同一 MinIO。

在各自的 `litestream.yml` 中修改 `dbs.[0].path` 指向对应实例的数据库文件，并将 `path` 字段区分开，例如 `database/a`、`database/b`。随后分别执行：

```powershell
litestream replicate C:\path\to\instance-a\litestream.yml
litestream replicate C:\path\to\instance-b\litestream.yml
```

这样即可在一台机器上验证两个服务写入同一个 MinIO 的情况。

## 5. 配置 Litestream (For Standalone Example or Library Use)
创建 `C:\Litestream\litestream.yml`（路径可自定义）：
```yaml
access-key-id: minioadmin
secret-access-key: minioadmin

dbs:
  - path: C:\path\to\your\project\app-data.db
    replicas:
      - name: minio
        type: s3
        bucket: clojure-db-replica
        path: database
        endpoint: http://127.0.0.1:9000
        force-path-style: true
        snapshot-interval: 1h
        retention: 24h
```
将 `path` 改为项目中数据库文件的绝对路径。修改配置后重启 *Litestream* 服务即可开始复制。

## 6. 验证与恢复
1. 运行 Clojure 程序向数据库写入数据。
2. 打开 MinIO 控制台，可看到 `database/` 目录下出现 `generations`、`snapshots` 等子目录，说明复制成功。
3. 若需要恢复，可在项目目录执行：
   ```powershell
   litestream restore -o app-data.db s3://clojure-db-replica/database
   ```
   然后重新运行程序查看数据是否完整。

## 7. 让 MinIO 持久运行（可选）
创建 `start-minio.bat`：
```bat
@ECHO OFF
C:\minio\minio.exe server C:\minio-data --console-address ":9001"
```
通过“任务计划程序”设置该批处理在系统启动时执行，即可在后台常驻运行。

## 8. 在另一台电脑测试
如果想在另一台机器上验证备份数据，可按下列步骤操作：

1. 在第二台机器同样安装 Clojure、Litestream 以及相同的 Java 环境。
2. 确保第二台机器可以通过网络访问第一台主机上的 MinIO 服务。
3. 将此项目代码拷贝到第二台机，并修改 `litestream.yml` 中的 `endpoint` 为第一台机的 IP 或域名。
4. 在项目目录执行：
   ```powershell
   litestream restore -o app-data.db s3://clojure-db-replica/database
   ```
5. 继续执行：
   ```powershell
   clojure -M:run
   ```
   或者直接调用 `clojure -m myapp.db`。
6. 查看控制台输出和本地数据库内容，确认数据与主机一致。
7. 如需持续同步，可在第二台机也启动 Litestream 服务，指向同一 MinIO 存储。

## 9. 自动故障转移示例 (Standalone Example)

项目新增了 `myapp.standby`，用于在候选节点上定期检查主节点是否存活。如果检测到
`http://localhost:3001/health` 无响应，将自动执行 `litestream restore` 恢复数
据并启动本地服务接管。
(当作为库使用时，此备用逻辑将通过类似 `myapp.standby/start-monitoring` 的可配置函数调用，如“将本项目用作库”部分所述。)

```bash
clojure -M:standby
```

启动后程序会每 5 秒轮询一次主节点，一旦发现故障便会拉起自己的 HTTP 服务和写入任
务。

## 工作原理与热备场景 (General Concept)

在生产环境中可以将 MinIO 部署为共享服务，或在每台主机上独立运行。本项目采用后者（更准确地说，示例倾向于此设置，但作为库可以灵活配置），即每台主机都拥有自己的 MinIO 实例，并通过交叉复制实现热备，或者多个节点备份到共享的MinIO服务。

假设主机 *A* 为当前主库、主机 *B* 为候选库，整体流程如下：

1. *A* 正常运行时，会把 SQLite 的增量数据持续推送到 *B* 本地的 MinIO；
2. 当需要将 *B* 提升为主库，或 *A* 发生故障时，在 *B* 上执行 `litestream restore` 从自身 MinIO 恢复数据库，然后启动应用；
3. *B* 成为主库后，开始将变更推送到 *A* 的 MinIO，使 *A* 恢复后能够重新同步；
4. *A* 重新上线并从其 MinIO 更新数据库后，可作为候选库继续向 *B* 的 MinIO 推送备份。

---
通过以上步骤即可在本地搭建一个基于 SQLite 的可靠备份方案，既适用于开发环境，也可
用于小规模生产场景。
