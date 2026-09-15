# 192.168.1.199 部署

访问地址：<http://192.168.1.199:8088>。Docker Compose 项目名为 `mgs199`，包含管理前端、全模块后端、IoT 网关、MySQL、Redis、TDengine，均配置自动重启和持久化数据卷。

## 首次部署

前后端仓库需要并列放置，当前路径分别是 `/opt/mgs/ruoyi-vue-pro`、`/opt/mgs/yudao-ui-admin-vben`。需要 Docker Compose、JDK 17、Maven、Node 22 和项目指定的 pnpm。

在后端仓库执行：

```bash
mvn -B -DskipTests package
python3 script/deploy-199/init-env.py
```

`init-env.py` 只创建不存在的 `.env`，不会覆盖运行中数据库的密码。该文件权限为 600，已被 Git 忽略；请单独妥善备份。

在前端仓库执行：

```bash
pnpm install --frozen-lockfile --filter @vben/web-antd... --filter @vben/vite-config... --filter @vben/turbo-run... --filter vben-admin-monorepo --ignore-scripts
pnpm --filter @vben/node-utils run stub
pnpm --filter @vben/vite-config run stub
VITE_BASE_URL=http://192.168.1.199:8088 \
VITE_GLOB_API_URL=/admin-api \
VITE_APP_API_ENCRYPT_ENABLE=false \
VITE_APP_CAPTCHA_ENABLE=false \
VITE_ARCHIVER=false VITE_APP_BAIDU_CODE='' \
NODE_OPTIONS=--max-old-space-size=4096 \
pnpm --filter @vben/web-antd run build
```

回到后端仓库启动：

```bash
docker compose -f script/deploy-199/compose.yaml up -d
docker compose -f script/deploy-199/compose.yaml ps
curl --noproxy '*' -fsS http://127.0.0.1:48088/actuator/health
```

MySQL 初始化脚本仅在空数据卷首次启动时运行，依次导入基础数据、实体重建表、积木报表表和 Quartz 表，再设置本机数据库文件存储。后续升级需要审阅并单独执行迁移；不要重新导入包含 DROP TABLE 的基础脚本。

## 运行维护

```bash
# 查看日志
docker compose -f script/deploy-199/compose.yaml logs --tail 100 server gateway
# 更新 JAR / 前端生产包后重建容器，以刷新文件挂载
docker compose -f script/deploy-199/compose.yaml up -d --force-recreate server gateway admin
# 停止服务但保留数据
docker compose -f script/deploy-199/compose.yaml stop
```

数据库、Redis、TDengine 无宿主机公开端口；后端 48088 仅绑定回环地址，通过前端 Nginx 代理访问 API 和 WebSocket。8088 绑定本机内网 IP。数据保存在 `mgs199_mysql_data`、`mgs199_redis_data`、`mgs199_tdengine_data`、`mgs199_server_data` 卷中；备份数据库、卷和 `.env`。不要使用 `down -v`，它会删除数据卷。

当前使用内网 HTTP、关闭登录验证码，禁用开发模拟登录及示例外部 AI 客户端。积木报表需要的默认 OpenAI ChatModel 保留，但没有真实 API Key。支付、微信、短信、AI、音视频等第三方能力仍需填写自己的业务配置；支付回调还需要服务商能访问的公网 HTTPS 地址。重建 SQL 只补表结构，不提供缺失模块的业务初始化数据。IoT 网关进程已纳入部署，设备协议监听默认关闭，接入设备前需要配置协议与端口。

TDengine 入口包装脚本仅去除供应商脚本的 `set -x`，防止初始化密码进入日志；其余初始化步骤保持原样。

`taos.cfg` 使用标准的 2 GiB 数据写入保留空间，并限制日志。注意监控共享宿主机的磁盘容量，低于保留空间时 TDengine 会拒绝写入。


### 共享主机的 TDengine 内存预留

`taos.cfg` 显式设置 `minReservedMemorySize 1024`（MB），避免默认预留整机 20% 内存导致当前共享主机在后端重启时连表结构查询都报 `Query memory exhausted`。已有数据目录可能优先读取 `dnode/config/local.json`；升级后应查询 `SHOW DNODE 1 VARIABLES LIKE 'minReservedMemorySize'`，不能只依据文本配置判断生效。此次实例已备份原持久化配置并将该值设为 1024，重启后完成读回验证。操作背景、备份位置及验证见 [ERP 交接报告](../openapi/ERP_ORDER_HANDOFF.md#部署依赖恢复记录)。


### 菜单可用性与中文迁移

执行含中文的增量 SQL 时，必须使用 `mysql --default-character-set=utf8mb4`；脚本也应显式 `SET NAMES utf8mb4`。2026-09-15 新增试用菜单曾因客户端默认字符集错误出现乱码，修复脚本为 `script/trial/V20260915_06__repair_trial_menu_encoding.sql`，仅更新四个已确认的错误值，不覆盖后续自行修改的名称。

发布前可用 `audit-menu-pages.py <菜单JSON快照> <前端dist目录> <输出目录>` 检查菜单与实际发布资源是否匹配。快照采用 `id,parentId,type,status,visible,component` 字段；脚本从 `index.html` 遍历可达 JS，忽略目录中的旧包，并兼容菜单 component 带或不带 `.vue`。生成审查清单、下架 SQL 与恢复 SQL，不自行连接或修改数据库。只下架确认缺失的页面及因此变空的父目录，不删除菜单或角色关联。

本机当前已下架 CMS 43 个、OA 6 个缺失页面和两个尚未发布的试用页面；待对应前端发布并重新审查后再恢复。原菜单和角色关联保留在数据库，操作前快照及恢复 SQL 位于 `/opt/mgs/backups/menu-repair-20260915`。权限菜单在登录时加载，变更后需退出重新登录；旧标签页和收藏地址不能用来判断页面已发布。
