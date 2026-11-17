#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
DB_HOST="${MYSQL_HOST:-127.0.0.1}"
DB_PORT="${MYSQL_PORT:-3306}"
DB_NAME="${MYSQL_DATABASE:-ry}"
DB_USER="${MYSQL_USER:-root}"

if [[ ! "$DB_NAME" =~ ^[A-Za-z0-9_]+$ ]]; then
  echo "MYSQL_DATABASE 只能包含字母、数字和下划线" >&2
  exit 2
fi
if ! command -v mysql >/dev/null 2>&1; then
  echo "未找到 mysql 客户端，请先安装 MySQL 客户端" >&2
  exit 2
fi

if [[ -z "${MYSQL_PASSWORD:-}" ]]; then
  read -r -s -p "MySQL 密码（不会写入文件）: " MYSQL_PASSWORD
  printf '\n'
fi
export MYSQL_PWD="$MYSQL_PASSWORD"

mysql_args=(--protocol=tcp -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USER")
mysql "${mysql_args[@]}" -e "CREATE DATABASE IF NOT EXISTS \`$DB_NAME\` CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;"

table_count="$(mysql "${mysql_args[@]}" -N -B -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$DB_NAME';")"
if [[ "$table_count" != "0" && "${INIT_ALLOW_EXISTING:-}" != "YES" ]]; then
  echo "数据库 $DB_NAME 已包含 $table_count 张表。为避免覆盖已有数据，初始化已停止。" >&2
  echo "确认已备份并确实要重建时，请设置 INIT_ALLOW_EXISTING=YES 后重试。" >&2
  exit 3
fi

mysql "${mysql_args[@]}" "$DB_NAME" < "$ROOT_DIR/database/bootstrap/ruoyi-base.sql"
while IFS= read -r migration; do
  mysql "${mysql_args[@]}" "$DB_NAME" < "$migration"
done < <(find "$ROOT_DIR/database/migrations" -maxdepth 1 -type f -name '*.sql' -print | sort -V)

unset MYSQL_PWD
echo "数据库 $DB_NAME 初始化完成。默认账号：admin，初始密码：123456；首次登录必须修改密码。"
