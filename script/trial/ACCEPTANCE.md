# 试用开户候选版本验收边界

当前实现遵循用户补充：租户 1 为既有演示环境，栖云为独立企业运营租户；访客不选择套餐。共享演示租户中的体验账号按申请、账号、CRM 所有权和专属接口范围隔离。此变更不代表已初始化生产栖云租户。

| 要求 | 已验证证据 | 当前边界 |
| --- | --- | --- |
| 可信主体、确认、请求幂等、防重放、篡改字段拒绝 | TrialServiceAuthTest、TrialOrchestratorTest、实际 Springdoc 输出 | 知办公开客服的可信服务入口仍由知办实现 |
| 运营 CRM 留资、MGS 普通账号与虚构客户 | TrialLocalJourneyIntegrationTest 使用实际 Clue/User/Role/Menu/Permission/Customer 服务和同一 H2 数据库 | 没有创建运行环境账号或业务；没有自动转客户 |
| 栖云企业初始化 | TrialCorporateTenantIntegrationTest，实际 Tenant/Package/Menu/User/Role/Permission 服务、动态事务 | 生产资料/容量/菜单/有效期未配置，MySQL 初始化未执行 |
| 浏览器登录与个人授权共享本人业务结果 | TrialLocalJourneyIntegrationTest：实际 AuthController 登录、OAuth 数据库和缓存 DAO、Token/Tenant 过滤、方法权限及 CRM HTTP 请求 | MockMvc HTTP + H2；Redis 协议由独立 loopback jedis-mock 提供，Spring 权限缓存为 ConcurrentMapCacheManager；不是实际浏览器和运行后端的联网联调 |
| 两人数据隔离、拒绝导出/文件/管理接口 | TrialBusinessIntegrationTest、TrialLocalJourneyIntegrationTest | 文件/导出在 MVC 哨兵处理器前拒绝，未连接真实对象存储 |
| 并发、超时核对、部分成功恢复、重建编排器续办 | TrialOrchestratorTest 和 TrialLocalJourneyIntegrationTest；真实 MGS 资源/授权不重复 | 知办 adapter 使用明确替身；重建对象不等于真实进程崩溃演练 |
| 绑定与业务事件独立、去重、真实结果核验 | TrialLocalJourneyIntegrationTest：实际 CRM 摘要写入、缺少业务记录拒绝、故障回滚；既有事件单测 | 事件来源的服务签名另有单测；未验收真实公众号绑定与安全卡片 |
| 加密登录凭据交付、原主体及重试窗口限制 | TrialLoginDeliveryTest、MgsTrialAccountIntegrationTest、TrialLocalJourneyIntegrationTest | 不替代知办安全卡片的原会话领取、防转发及绑定码过期验证 |
| 到期、既有会话/令牌/刷新别名撤销 | TrialLocalJourneyIntegrationTest：两类真实 MGS grant、实际缓存 DAO、到期前置拒绝、撤权后 401/旧密码登录失败；另一用户不受影响 | 知办渠道撤销由 adapter 替身确认，真实知办侧尚未联调 |
| 运营前端与本人结果页 | frontend-test-evidence.json：实际 web-antd 渲染、桌面/手机 5 场景、错误状态、分页与恢复权限 | 所有前端 API（包括登录）为 fixture；限定范围类型检查通过，全应用检查未通过验收 |
| OpenAPI/工具契约 | generated/openapi.json、generated/tool-contract.json；实际隔离 Springdoc 12 个公开接口 | 候选版本 mgs-trial-v1-candidate；不是运行服务导出，也不宣称知办已同步 |

## 尚需外部对接或部署配置

- 知办可信身份与确认流程、受限工具调用入口；不解除公开助手原有直接工具限制。
- 确定性的成员查询/创建、个人 MGS 授权、安全交付引用、实际安全卡片领取、公众号绑定和到期撤销接口，详见 KNOWDO_CONTRACT_DRAFT.md。
- 真实 HTTPS 地址、服务鉴权密钥与登录交付加密密钥；栖云内部联系人/账号/菜单/容量/有效期，试用期限与配额。
- 在批准的隔离联调环境完成官网申请到公众号绑定、办理业务、MGS 查看结果的全链路实测，以及实际进程重启/网络故障演练。

当前不具备完整产品链路验收依据。新开户保持关闭；MGS/知办发布、连接器同步、渠道绑定与真实业务写入均未在本次开发中执行。现有容器使用的 JAR/dist 未改写。以两个仓库功能分支的提交标识和测试证据核对候选代码，后续发布按 OPERATIONS.md 执行。
