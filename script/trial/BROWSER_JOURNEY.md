# 浏览器连接隔离 MGS 后端的回归

本验证覆盖普通体验账号实际登录、个人 Agent 授权新增跟进、MGS 页面读取同一记录、刷新保留结果及另一账号的数据隔离。不是知办官网/安全卡片/公众号联调，不访问部署数据库，不覆盖生产挂载的 JAR/dist。

## 环境与边界

- 实际前端：`/opt/mgs/yudao-ui-admin-vben/apps/web-antd` 的 Vite 开发服务。Browser 插件不可用，复用项目已安装的 Playwright 和 Chromium，不新增依赖。
- 后端：JUnit 临时启动仅监听 `127.0.0.1` 随机端口的 Tomcat，加载实际 AuthController、TenantController、试用业务 Controller、MGS 普通账号/角色/权限/OAuth/CRM 服务，以及项目正式的时间戳 JSON 序列化模块。
- H2、jedis-mock 和 Spring 内存权限缓存隔离；知办、联系验证、字典、部门/岗位等外围服务仍为替身。演示/运营两个租户和所有账号都只存在于测试数据库。
- 菜单使用 `V20260914_02__trial_menus.sql` 的实际页面/按钮层级；只在测试装配中移除 MySQL 事务边界语句以便 H2 执行，不改迁移文件。
- 浏览器拦截仅把 API 请求转发到临时 Tomcat，响应来自实际后端，没有伪造登录、权限或业务数据；禁止请求落入 Vite 原有后端代理。外部图片用测试色块代替，其他外部资源阻止，因此不验证生产图标/统计资源可用性。
- 临时账号凭据只通过权限 `0600` 的测试输入文件传递给浏览器进程，测试结束删除；不写入交付证据。测试子进程、Tomcat 及独享缓存服务在结束时关闭。

## 运行

先在前端仓库启动独立 Vite（结束后停止自己的进程，不使用 build）：

```bash
cd /opt/mgs/yudao-ui-admin-vben
NODE_OPTIONS=--max-old-space-size=1400 VITE_PORT=5198 VITE_GLOB_API_URL=/admin-api VITE_APP_API_ENCRYPT_ENABLE=false VITE_APP_CAPTCHA_ENABLE=false pnpm --filter @vben/web-antd dev --host 127.0.0.1 --port 5198 --strictPort
```

再在后端仓库执行；`CHROMIUM_EXECUTABLE` 按本机已安装路径设置，未设置则用 Playwright 默认浏览器。没有 `TRIAL_UI_URL` 时，该专项测试跳过，不将跳过算作通过。

```bash
cd /opt/mgs/ruoyi-vue-pro
TRIAL_UI_URL=http://127.0.0.1:5198 \
TRIAL_BROWSER_OUT=/tmp/mgs-trial-live-browser \
MAVEN_OPTS='-Xmx512m -XX:ActiveProcessorCount=2' \
mvn -q -pl yudao-module-crm -am test \
  -Dtest=TrialBrowserJourneyTest -Dsurefire.failIfNoSpecifiedTests=false \
  '-DargLine=-Xmx384m -XX:ActiveProcessorCount=2 -Duser.timezone=UTC'
```

此测试为独立的显式浏览器门槛，不在默认 `run-tests.sh` 中自动启动浏览器。使用 UTC 固定测试数据的时间断言；实际页面沿用项目日期工具按用户时区显示。

## 结果与回归问题

- 两个浏览器上下文：1440×1000 桌面账号、390×1000 手机账号；均通过实际登录进入本人结果页。没有业务写入按钮，不调用全局字典/通知接口。
- 个人 Agent token 发起真实测试 HTTP 写入，重复幂等请求返回同一编号；页面刷新返回相同记录编号，页面重载后仍显示；另一账号只读取自己的客户和空跟进列表。最终数据库只有一条跟进及一条幂等记录。
- 使用正式时间序列化后，原体验页把毫秒数直接展示为下次联系时间，断言可复现失败。前端 API 类型已对齐 `nextTime: number`，页面复用 `formatDateTime`；修复后显示 `2027-01-02 10:30:00`，与生成 OpenAPI 的 integer/int64 定义一致。
- 检查页面标题和路由、非空、初始加载层退出、无框架错误层、无页面横向溢出、无未处理浏览器异常；只允许记录已有表单/存储弃用告警和被主动隔离的外部资源失败。
- `browser-live-test-evidence.json` 为脱敏实测证据，截图存于其指定的 `/tmp` 目录。原五项模拟接口页面回归仍记录在 `frontend-test-evidence.json`，不能与本测试混为同一种覆盖。

尚未验证 MySQL、真实知办/公众号、安全卡片、生产发布环境。前端全量类型检查门槛仍未通过，前端候选尚未提交。这里的本地登录与业务读取通过，不代表完整产品链路已完成。
