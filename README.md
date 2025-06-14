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

## 4. 配置 Litestream
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

## 5. 验证与恢复
1. 运行 Clojure 程序向数据库写入数据。
2. 打开 MinIO 控制台，可看到 `database/` 目录下出现 `generations`、`snapshots` 等子目录，说明复制成功。
3. 若需要恢复，可在项目目录执行：
   ```powershell
   litestream restore -o app-data.db s3://clojure-db-replica/database
   ```
   然后重新运行程序查看数据是否完整。

## 6. 让 MinIO 持久运行（可选）
创建 `start-minio.bat`：
```bat
@ECHO OFF
C:\minio\minio.exe server C:\minio-data --console-address ":9001"
```
通过“任务计划程序”设置该批处理在系统启动时执行，即可在后台常驻运行。

---
通过以上步骤即可在本地搭建一个基于 SQLite 的可靠备份方案，既适用于开发环境，也可用于小规模生产场景。

