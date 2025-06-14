# sqlite-replicate

本示例展示如何在 Windows 环境中利用 **Clojure + SQLite + Litestream + MinIO** 实现本地数据库的增量备份与恢复。下文将分步骤说明环境配置、项目结构以及常见操作流程。

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

## 3. Clojure 项目结构
本仓库已包含最小化的 deps.edn 与示例代码：

```
├─deps.edn
└─src
   └─myapp
      └─db.clj
```
`deps.edn` 声明依赖 `next.jdbc` 与 `sqlite-jdbc`，`src/myapp/db.clj` 实现了简单的用户表读写逻辑并应用 Litestream 所需的 PRAGMA 设置。

### 运行示例
在项目根目录执行：
```bash
clojure -m myapp.db
```
执行后会生成 `app-data.db` 并打印插入与查询结果。

## 4. 启动 HTTP 服务并持续写入数据库

项目还提供了 `myapp.service`，启动后会在 `3000` 端口暴露 `/health` 接口，并每秒向 `events` 表写入当前时间戳。

```bash
clojure -M:service
```

访问 <http://localhost:3000/health> 可得到 `OK`，同时可在数据库中看到不断新增的记录。

### 单机运行两个服务与一个 MinIO

若要在单机测试多实例，可复制项目目录两份，例如 `instance-a/` 与 `instance-b/`，分别启动 Litestream 和 `myapp.service`，但共用同一 MinIO。

在各自的 `litestream.yml` 中修改 `dbs.[0].path` 指向对应实例的数据库文件，并将 `path` 字段区分开，例如 `database/a`、`database/b`。随后分别执行：

```powershell
litestream replicate C:\path\to\instance-a\litestream.yml
litestream replicate C:\path\to\instance-b\litestream.yml
```

这样即可在一台机器上验证两个服务写入同一个 MinIO 的情况。

## 5. 配置 Litestream
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

## 9. 自动故障转移示例

项目新增了 `myapp.standby`，用于在候选节点上定期检查主节点是否存活。如果检测到
`http://localhost:3001/health` 无响应，将自动执行 `litestream restore` 恢复数
据并启动本地服务接管。

```bash
clojure -M:standby
```

启动后程序会每 5 秒轮询一次主节点，一旦发现故障便会拉起自己的 HTTP 服务和写入任
务。

## 工作原理与热备场景

在生产环境中可以将 MinIO 部署为共享服务，或在每台主机上独立运行。本项目采用后者，即每台主机都拥有自己的 MinIO 实例，并通过交叉复制实现热备。

假设主机 *A* 为当前主库、主机 *B* 为候选库，整体流程如下：

1. *A* 正常运行时，会把 SQLite 的增量数据持续推送到 *B* 本地的 MinIO；
2. 当需要将 *B* 提升为主库，或 *A* 发生故障时，在 *B* 上执行 `litestream restore` 从自身 MinIO 恢复数据库，然后启动应用；
3. *B* 成为主库后，开始将变更推送到 *A* 的 MinIO，使 *A* 恢复后能够重新同步；
4. *A* 重新上线并从其 MinIO 更新数据库后，可作为候选库继续向 *B* 的 MinIO 推送备份。

---
通过以上步骤即可在本地搭建一个基于 SQLite 的可靠备份方案，既适用于开发环境，也可
用于小规模生产场景。
