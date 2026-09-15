# 2026-09-15 MGS 后端发布

后端已部署至 http://192.168.1.199:8088，健康检查 UP，容器 healthy，重启次数 0。实际运行制品对应 `c67bfe25b966d56b226d2b3dd5400b3c34033d07`，JAR SHA-256 为 `08f025891629556436bea2dcdbab0b0edb0bdba4ddb95c0e3a501ac06e3ccc31`。本次仅重建 server，未重建前端、IoT 网关、数据库或其他服务。

机器可读证据见 [deployment-20260915.json](deployment-20260915.json)。此前文档中的“未部署”是候选版本当时的状态；本记录更新后端部署状态，不替代端到端验收。

## 实际文档

- 默认完整文档：`http://192.168.1.199:8088/v3/api-docs`，3336 个路径，6388 个引用均可解析，无重复 operationId。
- 管理端 all 分组：`http://192.168.1.199:8088/v3/api-docs/all`，3013 个路径，5908 个引用均可解析，无重复 operationId。两者范围不同，不能仅凭分组名认为 all 包含默认文档的全部路径。
- `info.version=1.0.0-full-contract-v6`；试用契约 `x-mgs-trial-contract-version=mgs-trial-v2-candidate`。candidate 保留表示知办联调尚未验收。
- 两份实际导出均包含 12 个试用相关公开操作；私有短信发送/校验、授权交换、登录交付四个端点不在文档中。
- 试用公开接口的 `x-business-success` 均明确整数 `code` 等于数字 `0`，错误路径为 `msg`。开户就绪另有 `code=0 && data.accountReady=true` 条件。
- ERP 销售订单新增/修改的 items 均为真实明细：`id, productId, productUnitId, productPrice, count, taxPercent, remark`；必填 `productId, productPrice, count`；`productUnitId` 为后端取得的只读字段，无需调用方提交。

知办需重新同步文档中的参数结构、业务成功条件与试用 v2 签名/验证凭据契约。Agent 仍只导入 AGENT_API_CATALOGS.md 的两个四接口白名单；私有接口不作为工具。未改写知办数据库、发布连接器或声称其已应用。

## 迁移、配置和验证

- 独立工作树完整编译后端依赖并打包成功，未在运行挂载目录构建。构建日志 `/opt/mgs/releases/build-c67bfe25b9.log`。打包使用 `-DskipTests`，沿用本次代码此前隔离回归证据（累计 132 项 JUnit；其中相关 SMS/鉴权/HTTP/MySQL/OpenAPI 45 项，目录检查另 5 项），不宣称部署时重新运行全部测试。
- 数据库 581 张表已完成一致性备份并验证压缩文件及 dump 完成标记；依次成功执行 01–05 五份增量迁移，未重新导入基础数据。
- `storage-enabled=true`；`enabled=false`、`sms-verification.enabled=false`、`operator-bootstrap.enabled=false`。未配置或伪造真实短信渠道、模板、密钥、企业运营租户、配额或期限。
- 首页 HTTP 200；后端健康 UP。实际调用四个私有端点和只读状态端点（空请求、无签名）均得到 `{"code":1020100001,"msg":"试用服务身份或签名无效","data":null}`，并带 `Cache-Control: no-store`。个人业务查询无登录返回业务码 401。即便 HTTP 200，非零业务码仍判失败。
- 验证后申请、短信挑战、已验证联系人三张表均为 0 行。未发送真实短信、创建账号或订单。
- 本次仅核对实际生成文档结构及无副作用运行检查，没有逐条执行数千业务接口。

## 尚未开放的部分

真实短信渠道/模板、知办安全卡片与 v2 签名，以及企业运营配置仍需完成。当前入口是 HTTP；要求安全传输的私有接口会拒绝请求，尚未验收可信 HTTPS 代理路径。不能通过伪造 X-Forwarded-Proto 绕过。

前端暂存修改仍受全量类型检查内存失败阻塞，未提交或部署，因此本次不会新增前端体验页面。菜单迁移不向现有普通角色授予新权限；超级管理员可能看到新菜单，但对应页面尚未发布。后台维护任务尚未登记，因新开户关闭且无申请，本次没有启动维护任务或初始化栖云租户。

共享宿主机磁盘剩余空间约 700 MiB，仍需扩容或清理已确认可删除的数据。此次仅无损去重 MGS 历史备份及清理本次独立构建目录，未清理其他项目数据。

## 回滚资料

备份目录 `/opt/mgs/backups/trial-deploy-20260915`，含旧配置、加密权限保护下的数据库备份、旧容器身份、迁移哈希和实际 OpenAPI 导出。旧 JAR 已做无损差分存储并校验完整还原 SHA-256；依赖的基础 JAR 位于 `/opt/mgs/backups/erp-order-contract/server-before-34a9eb1d73.jar`，必须一并保留。

恢复旧 JAR（先确保有约 550 MB 输出空间）：

```bash
python3 /opt/mgs/backups/compact-jar-backups.py restore \
  /opt/mgs/backups/trial-deploy-20260915/server-before.jar.restore.json \
  /opt/mgs/backups/trial-deploy-20260915/server-before.restored.jar
```

确认当前仍无活跃试用账号，再恢复旧配置、原子替换 JAR，并仅重建 server。保留新增数据库表，不使用整库恢复覆盖上线后的业务数据。若未来已有活跃试用账号，必须遵循 OPERATIONS.md 的撤权和维护版本要求，不能直接回退到没有访问隔离的旧制品。
