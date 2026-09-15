# 试用开户候选版本验收边界

当前实现遵循用户补充：租户 1 为既有演示环境，栖云为独立企业运营租户；访客不选择套餐。共享演示租户中的体验账号按申请、账号、CRM 所有权和专属接口范围隔离。此变更不代表已初始化生产栖云租户。

| 要求 | 已验证证据 | 当前边界 |
| --- | --- | --- |
| 可信主体、确认、请求幂等、防重放、篡改字段拒绝 | TrialServiceAuthTest、TrialToolHttpIntegrationTest、TrialOrchestratorTest、实际 Springdoc 输出；完整 RequestBodyAdvice/正式 JSON 转换/校验/Controller/本地服务链验证；所有服务签名入口统一拒绝非安全请求 | MockMvc 的 secure 标志验证应用约束，不代表真实 TLS/代理联调；知办公开客服的可信服务入口仍由知办实现 |
| 运营 CRM 留资、MGS 普通账号与虚构客户 | TrialLocalJourneyIntegrationTest 使用实际 Clue/User/Role/Menu/Permission/Customer 服务和同一 H2 数据库 | 没有创建运行环境账号或业务；没有自动转客户 |
| 栖云企业初始化 | TrialCorporateTenantIntegrationTest，实际 Tenant/Package/Menu/User/Role/Permission 服务、动态事务 | 生产资料/容量/菜单/有效期未配置，MySQL 初始化未执行 |
| 浏览器登录与个人授权共享本人业务结果 | TrialLocalJourneyIntegrationTest；新增 TrialBrowserJourneyTest：实际 web-antd 浏览器登录临时 Tomcat，个人授权 HTTP 写入，页面读取同一记录编号和刷新保留，另一账号为空；正式 JSON 时间戳渲染回归 | 本地 H2、独立 jedis-mock、ConcurrentMapCacheManager；知办和外围模块替身。实际浏览器已连接隔离后端，但没有连接部署实例/MySQL/知办/公众号；见 BROWSER_JOURNEY.md |
| 两人数据隔离、拒绝导出/文件/管理接口 | TrialBusinessIntegrationTest、TrialLocalJourneyIntegrationTest | 文件/导出在 MVC 哨兵处理器前拒绝，未连接真实对象存储 |
| 并发、超时核对、部分成功恢复、进程重启续办 | TrialOrchestratorTest、TrialLocalJourneyIntegrationTest、TrialProcessRestartTest；三个确定性边界强制结束子 JVM，再从文件数据库冷启动，核对事务回滚、已提交资源编号、资源行数与远端创建次数 | 实际 MGS 服务 + 文件 H2；知办为独立提交的文件数据库替身。验证未到期租约不可接管后，仅在测试库加速租约到期；未验证 MySQL、真实知办、机器断电或部署切换 |
| 绑定与业务事件独立、去重、真实结果核验 | TrialLocalJourneyIntegrationTest：实际 CRM 摘要写入、缺少业务记录拒绝、故障回滚；既有事件单测 | 事件来源的服务签名另有单测；未验收真实公众号绑定与安全卡片 |
| 加密登录凭据交付、原主体及重试窗口限制 | TrialLoginDeliveryTest、MgsTrialAccountIntegrationTest、TrialLocalJourneyIntegrationTest | 不替代知办安全卡片的原会话领取、防转发及绑定码过期验证 |
| 到期、既有会话/令牌/刷新别名撤销 | TrialLocalJourneyIntegrationTest：两类真实 MGS grant、实际缓存 DAO、到期前置拒绝、撤权后 401/旧密码登录失败；另一用户不受影响 | 知办渠道撤销由 adapter 替身确认，真实知办侧尚未联调 |
| 运营前端与本人结果页 | frontend-test-evidence.json：桌面/手机 5 个模拟接口场景；browser-live-test-evidence.json：两名普通用户通过真实隔离后端登录/查看、生产时间戳转可读日期 | 运营页仍为 API fixture；实际后端浏览器验证仅覆盖本人体验页。限定范围类型检查通过，全应用检查未通过验收 |
| OpenAPI/工具契约 | generated/openapi.json、generated/tool-contract.json；实际隔离 Springdoc 12 个公开接口；从中选取开户和个人体验两个 4 接口 Agent 目录，见 AGENT_API_CATALOGS.md；正式个人 Bearer 与租户参数来源、引用闭合和管理接口排除有回归证据 | 候选版本 mgs-trial-v1-candidate；不是运行服务导出，也不宣称知办已同步 |
| MySQL 持久化与原始迁移 | TrialMySqlIntegrationTest，实际隔离 MySQL 8.4.11；12 项通过，包含原始 4 个迁移重复执行、并发幂等、事务回滚与编排恢复 | 本地账号/CRM/OAuth 步骤和知办是 fixture；未覆盖真实业务服务的 MySQL 联合执行、进程冷启动或部署。见 MYSQL_PERSISTENCE.md |

## 尚需外部对接或部署配置

- 知办可信身份与确认流程、受限工具调用入口；不解除公开助手原有直接工具限制。
- 确定性的成员查询/创建、个人 MGS 授权、安全交付引用、实际安全卡片领取、公众号绑定和到期撤销接口，详见 KNOWDO_CONTRACT_DRAFT.md。
- 真实 HTTPS 地址、服务鉴权密钥与登录交付加密密钥；栖云内部联系人/账号/菜单/容量/有效期，试用期限与配额。
- 在批准的隔离联调环境完成官网申请到公众号绑定、办理业务、MGS 查看结果的全链路实测，以及真实 MySQL/知办环境的进程重启和网络故障演练。文件 H2 上的独立进程中断恢复已有本地证据。

当前不具备完整产品链路验收依据。新开户保持关闭；MGS/知办发布、连接器同步、渠道绑定与真实业务写入均未在本次开发中执行。现有容器使用的 JAR/dist 未改写。以两个仓库功能分支的提交标识和测试证据核对候选代码，后续发布按 OPERATIONS.md 执行。

## 最新运行观察与推进条件

2026-09-15 再次只读请求 `http://192.168.1.199:8088/v3/api-docs`：文档标识仍为 `1.0.0-full-contract-v5`，共 3324 个路径，没有 `/crm/trial-` 路径，响应 SHA-256 与前一天相同。详见 runtime-observation.json。此观察只说明文档中未暴露本次接口，不能推断运行 Git 提交；隔离测试的候选导出不等于运行服务文档。

2026-09-15 文档增量回归：TrialOpenApiExportTest 2 项通过，目录 Python 回归 5 项通过；其他业务/浏览器测试未因纯文档变更重复运行，保留各自时间戳。test-evidence.json 中累计 JUnit 项数为 100，不代表同一时刻全套重跑；独立目录测试证据见 agent-catalog-test-evidence.json。

同日进一步完成 MySQL 持久化补充：原 H2 编排 11 项和 MySQL 编排/迁移 12 项通过。累计 JUnit 证据更新为 112 项，只有这两个套件在本轮执行，其他套件仍以各自时间戳为准。临时 MySQL 容器及匿名卷已清理；现有服务数据库未连接。

前端实际应用的完整检查另以 1792/2048 MiB 堆直接执行同一个 vue-tsc 和完整 tsconfig，均在独立 2560 MiB 总内存限制内因 JS 堆耗尽失败。没有删减检查范围、提高共享主机负载上限或跳过钩子。前端 10 个文件维持暂存；需要资源充足的检查环境，或此前已提出的候选提交检查范围例外得到明确确认。

MGS 可独立完成的实现、迁移、契约和隔离测试已形成候选交付；剩余完整链路依赖知办可信申请入口、内部成员/个人授权/安全卡片/渠道撤销接口，以及双方确认的隔离联调地址、凭据与企业配置。当前禁止修改知办或执行真实业务写入，不能以继续增加替身测试来替代这些依赖的真实验收。
