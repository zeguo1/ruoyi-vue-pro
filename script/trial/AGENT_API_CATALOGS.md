# 知办应接入的 MGS 接口

这是开发候选的导入清单，不是部署或知办同步成功的证明。MGS 统一编排双侧开通；知办实现可信客服入口、内部成员与个人凭据、安全卡片、公众号绑定和撤销。不要解除公开客服原有的直接业务工具限制。

## 两个 Agent 目录

| 用途 | OpenAPI 文件 | 参数与成功条件核对文件 |
| --- | --- | --- |
| 官网可信开户入口 | [onboarding-openapi.json](generated/onboarding-openapi.json) | [onboarding-tool-contract.json](generated/onboarding-tool-contract.json) |
| 本人体验业务 | [personal-business-openapi.json](generated/personal-business-openapi.json) | [personal-business-tool-contract.json](generated/personal-business-tool-contract.json) |

两个目录各包含 4 个接口，从本地实际 Springdoc 导出的 [openapi.json](generated/openapi.json) 按明确的方法和路径选取，只保留可达的组件引用及使用的鉴权方案，不重建字段或响应结构。来源版本、源文件和目录 SHA-256 见 [agent-catalog-manifest.json](generated/agent-catalog-manifest.json)。原始 12 个接口导出还包含运营接口和事件入口，不能把原始文件全选作为访客 Agent 工具。

文件保留隔离 Springdoc 的 `servers: http://localhost`，不代表可以调用部署服务。知办需配置双方确认的服务地址；开户工具必须走实际 HTTPS。目录中的路径已经包含 `/admin-api`，导入时应核对最终 URL，避免基础地址和路径重复拼接出 `/admin-api/admin-api/...`。

### 可信开户工具

| 方法与完整路径 | operationId | 业务语义 |
| --- | --- | --- |
| POST /admin-api/crm/trial-tool/submit | submit_trial_application | 保存本人申请并关联运营线索 |
| POST /admin-api/crm/trial-tool/create-accounts | create_trial_accounts | 验证用户确认，受理双侧开户 |
| POST /admin-api/crm/trial-tool/status | get_trial_status | 只读查询本人进度，不重放写操作 |
| POST /admin-api/crm/trial-tool/guide | get_trial_guide | 只读获取本人可用的体验说明 |

OpenAPI 的 `MgsTrialSignature` 以 apiKey/header 表达签名头位置，**不代表静态 API Key**。知办可信服务需实现 TOOLS 能力的 HMAC-SHA256 签名、已验证身份、当前申请确认、请求幂等及 nonce；密钥、身份和签名头不能成为模型输入。不能仅在连接器下拉框选 API Key、填入密钥就完成接入。此组禁止 tenant-id、visit-tenant-id 和查询串。签名细节见 [KNOWDO_CONTRACT_DRAFT.md](KNOWDO_CONTRACT_DRAFT.md)。

`code = 0`（数字）只表示本次命令或查询处理成功。整套账号就绪必须同时满足 `code = 0` 和 `data.accountReady = true`（布尔）；公众号绑定与首次业务完成还要分别检查对应事实。四个工具的 `x-mgs-account-ready-condition` 保留这一条件，不能将请求已受理当成开户完成。

### 个人体验业务

| 方法与完整路径 | 用途 |
| --- | --- |
| GET /admin-api/crm/trial-business/customer | 查询本人的虚构演示客户 |
| GET /admin-api/crm/trial-business/follow-up-types | 查询实际可用的跟进类型 |
| POST /admin-api/crm/trial-business/follow-up | 新增本人客户的跟进 |
| GET /admin-api/crm/trial-business/follow-ups | 查看本人的跟进结果 |

OpenAPI 正式声明 `MgsTrialPersonalBearer`，由知办个人凭据存储注入当前用户的访问令牌，不使用共享管理员令牌。Authorization 不再作为普通业务参数出现。`tenant-id` 可省略，实际 TenantSecurityWebFilter 从已认证用户取得租户；如果配置请求头，应由连接器服务端固定为本申请的演示租户，当前为 1，且必须与已认证用户一致。该头带 `x-mgs-model-input: false` 和来源说明，不让模型选择租户。

新增跟进的客户和负责人由后端确定。内容、类型、下次联系时间依据用户真实信息；类型从 follow-up-types 选择；重试保持同一业务幂等键及内容。读取为空仍可正常成功；新增成功的 data 是实际持久化跟进编号。请求字段、线上的序列化类型和成功条件均以对应文件为准。

核对文件保留 `security`、`personalAuthorization`、header 的 `valueSource/modelInput/omittedValueSource`。这些是供知办适配者核验的元数据，不宣称知办现有导入器已识别、存储或执行。

## 只给服务端的接口

| 方法与完整路径 | 独立签名能力 | 用途 |
| --- | --- | --- |
| POST /admin-api/crm/trial-internal/authorization | AUTHORIZATION | 安装或续用本人 MGS 授权 |
| POST /admin-api/crm/trial-internal/login-delivery | DELIVERY | 原申请人的安全登录卡片交付 |
| POST /admin-api/crm/trial-event/accept | EVENTS | 接收经核验的绑定或首次业务完成事件 |

三者均要求 HTTPS 和对应服务签名，不进入上述任何 Agent 目录。前两个本就从原始 OpenAPI 隐藏，含凭据的响应只能进入安全服务通道；完整请求、响应、错误和交付约定见内部契约。`/crm/trial-operations/*` 仅供受权限控制的 MGS 运营页，不给访客 Agent。无需给访客开放通用租户、用户、角色或菜单管理接口。

## 重新生成与验证

`script/trial/run-tests.sh` 会运行隔离测试、实际 Springdoc 导出、目录回归和生成，输出在 `yudao-server/target/`，不打包或覆盖运行 JAR。只重建目录可在仓库根目录执行：

```bash
python3 script/trial/test-agent-catalogs.py
python3 script/trial/build-agent-catalogs.py yudao-server/target/trial-openapi.json yudao-server/target/trial-agent-catalogs
```

测试输入必须来自成功的实际 Springdoc 导出。导出回归验证正式 Bearer 声明、租户来源及可选性、签名能力、字段和账号就绪条件；目录回归验证精确选择、原定义保留、引用闭合、未知新接口排除、缺失或外部引用拒绝和递归引用处理。

知办应分别重新同步两份目录的请求结构、security、租户参数来源、读写属性、CommonResult 成功条件以及开户就绪条件，核验最终 URL 和可信身份注入，再完成真实安全卡片及公众号链路联调。当前文件尚未导入或发布到知办，真实全链路仍待验证。
