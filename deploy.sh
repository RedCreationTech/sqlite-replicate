#!/bin/bash

# 提示用户输入 Clojars 用户名和 API token
echo "请输入您的 Clojars 用户名:"
read username

echo "请输入您的 Clojars API token:"
read -s token
echo ""

# 设置环境变量
export CLOJARS_USERNAME="$username"
export CLOJARS_PASSWORD="$token"

# 运行部署命令
echo "正在部署到 Clojars..."
clojure -M:deploy

# 清除环境变量
unset CLOJARS_USERNAME
unset CLOJARS_PASSWORD

echo "部署完成！"