#!/bin/bash
set -euo pipefail
export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"
mysql --default-character-set=utf8mb4 -uroot "$MYSQL_DATABASE" < /schema/mysql/ruoyi-vue-pro.sql
for module_file in /schema/mysql/modules/*.sql; do
  [[ "$module_file" == */all.sql ]] && continue
  mysql --default-character-set=utf8mb4 -uroot "$MYSQL_DATABASE" < "$module_file"
done
mysql --default-character-set=utf8mb4 -uroot "$MYSQL_DATABASE" < /schema/mysql/third-party/jimureport-schema.sql
mysql --default-character-set=utf8mb4 -uroot "$MYSQL_DATABASE" < /schema/mysql/quartz.sql
mysql --default-character-set=utf8mb4 -uroot "$MYSQL_DATABASE" < /bootstrap/deployment-data.sql
