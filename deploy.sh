#!/bin/bash

# 提示用户输入 Clojars 用户名和 API token
echo "请输入您的 Clojars 用户名:"
read username

echo "请输入您的 Clojars API token:"
read -s token
echo ""

# 验证输入
if [ -z "$username" ] || [ -z "$token" ]; then
    echo "错误：用户名和 token 不能为空"
    exit 1
fi

# 设置环境变量
export CLOJARS_USERNAME="$username"
export CLOJARS_PASSWORD="$token"

# 确保先构建 JAR
echo "构建 JAR 文件..."
if ! clojure -T:build jar; then
    echo "构建失败，退出部署"
    exit 1
fi

# 检查文件权限
JAR_FILE="target/sqlite-replicate-0.1.0-SNAPSHOT.jar"
echo "检查文件权限和大小:"
ls -la "$JAR_FILE"
echo "文件可读性检查:"
if [ -r "$JAR_FILE" ]; then
    echo "文件可读"
else
    echo "文件不可读，尝试修复权限"
    chmod 644 "$JAR_FILE"
fi

# 创建文件副本进行部署
cp "$JAR_FILE" "${JAR_FILE}.backup"
echo "创建文件备份: ${JAR_FILE}.backup"

# 运行部署命令
echo "正在部署到 Clojars..."
echo "部署前再次检查文件:"
ls -la "$JAR_FILE"

# 尝试部署
if clojure -X:deploy :artifact "${JAR_FILE}"; then
    echo "部署成功！"
else
    echo "部署失败，查看详细错误报告"
    # 查看错误报告
    REPORT_FILE=$(find /var/folders -name "clojure-*.edn" -type f 2>/dev/null | tail -1)
    if [ -f "$REPORT_FILE" ]; then
        echo "错误报告内容:"
        cat "$REPORT_FILE"
    fi
fi

# 清除环境变量
unset CLOJARS_USERNAME
unset CLOJARS_PASSWORD

echo "部署完成！"