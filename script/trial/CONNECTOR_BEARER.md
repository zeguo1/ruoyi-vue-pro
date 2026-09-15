# 知办标准连接器服务入口（开发交付，未部署）

2026-09-15；协议 `mgs-trial-connector-v1`，OpenAPI `1.0.0-full-contract-v7.2`。

本次增加专用 Bearer 入口，保留原 HMAC 入口、短信验证、开户编排、权限限制与安全交付。没有修改知办项目、发布连接器或执行真实短信/开户；新开关默认关闭，迁移尚未应用线上。现有短信通道、模板配置不会自动启用新入口。

## 为什么需要单独的服务认证

知办当前 `packages/connectors/src/call-context.ts` 自动注入 `X-KnowDo-Context`，内容为无 padding 的 Base64URL JSON：

```json
{"version":1,"actorId":"actor-example","audience":"anonymous","assistantId":"assistant-example","conversationId":"conversation-example","taskId":"task-example","channel":"web","operationId":"operation-example"}
```

`channelId` 可选，其余字段必填；ID/渠道字段限 1–160 个 ASCII 字母、数字或 `_.:@/-`。重复/未知 JSON 字段、非 1 版本、缺失字段均拒绝。按实际助手 ID、audience、channel 配置允许名单，示例值不可直接用于生产。

MGS 在真实 HTTPS 请求上校验专用服务 Token、有效期、能力及允许名单，再信任知办服务端断言的 actorId。身份以 `issuer + actorId` 绑定，不接受工具参数中的身份、手机号验证声明或开户确认声明，不建立个人 LoginUser，不需要且拒绝 `tenant-id`、`visit-tenant-id`。普通管理员 Token 不能调用此入口。7 个具体 POST 路径有独立认证链，避免服务 Token 误入个人 OAuth/租户校验；其他路径、方法仍走原认证链。

该上下文当前没有签名、时间戳或 nonce；本入口不宣称具有原 HMAC 的时间窗口验证。服务 Token 持有方可以断言允许名单内的身份，因此 Token 只能放在可信后端；知办必须从已鉴别的当前访客/安全卡片会话构造上下文，不能转发浏览器或模型自行提供的头。

操作按 issuer + operationId 的 SHA-256 持久化指纹。相同操作、身份、上下文、路径和原始请求字节可以重试；任意变化返回 1020100005。新动作生成新 operationId；网络重试保留原 ID 和原始 JSON 字节。同 issuer 的 Token 轮换不改变身份或重试键。此表不是响应缓存，状态查询重试仍返回当前状态。操作账本不保存原 Token、上下文、手机号或请求正文；没有自动清理，不能在同一 issuer 仍可能重试旧操作时随意清空。

## 两类凭据及流程

| 入口 | 能力 | 是否可发布为 Agent 工具 |
| --- | --- | --- |
| `POST /admin-api/crm/trial-connector/submit` | TOOLS | 是：登记团队、联系人、场景 |
| `POST /admin-api/crm/trial-connector/create-accounts` | TOOLS | 是：受理已经由用户确认的本人申请 |
| `POST /admin-api/crm/trial-connector/status` | TOOLS | 是：本人状态，只读 |
| `POST /admin-api/crm/trial-connector/guide` | TOOLS | 是：本人指南，只读 |
| `POST /admin-api/crm/trial-connector-private/sms/send` | SMS_VERIFICATION | 否：仅安全卡片后端 |
| `POST /admin-api/crm/trial-connector-private/sms/verify` | SMS_VERIFICATION | 否：仅安全卡片后端 |
| `POST /admin-api/crm/trial-connector-private/confirm` | CONSENT | 否：仅安全卡片后端 |

TOOLS 不能与其他能力合并在同一 Token。安全卡片 Token 可具有 SMS_VERIFICATION 和 CONSENT，两类 Token 的 issuer 必须相同，才能识别同一个访客。

1. 安全卡片收集手机号，调用 `/sms/send`：`{"mobile":"13800000001"}`（虚构格式示例，不要向此号码发送测试短信）。
2. 卡片以返回的 challengeId 和用户输入的 code 调用 `/sms/verify`。响应只有 `verified`、`expiresAt`；验证码和内部验证凭据不进入模型、工具结果或聊天记录。仍使用现有发送限频、验证次数、有效期控制。
3. Agent 调用 `/submit`，MGS 自行读取该身份最新未过期的成功验证记录并绑定联系方式。不能凭 actorId 直接声称手机已验证。
4. 用户在安全卡片明确确认当前 applicationId，卡片后端调用 `/confirm`，MGS 保存 5 分钟确认记录。重放不能延长有效期；确认本身不把申请送入开户队列。
5. Agent 调用 `/create-accounts`，MGS 核对本人申请及确认记录，才将开户交给原编排。工具 Token 不能调用确认接口。
6. Agent 查询 `/status`；`code = 0` 仅代表当前命令受理/查询成功。只有同时满足 `code = 0` 且 `data.accountReady = true` 才表示两侧账号、授权、样例及安全交付完成。失败码绝不能因 HTTP 200 或 data 非空判为成功。

登记请求（所有字段由用户真实提供）：

```json
{"team":"示例体验团队","contactName":"王女士","scenario":"CRM_FOLLOW_UP"}
```

其余 3 个工具及私有确认接口请求：

```json
{"applicationId":"00000000-0000-4000-8000-000000000001"}
```

applicationId 必须取自登记结果，上面的 UUID 仅说明格式。安全卡片输入格式：`{"challengeId":"<发送结果中的 challengeId>","code":"<用户输入的验证码>"}`。

失败响应符合 CommonResult 实现，例如缺少用户确认：

```json
{"code":1020100006,"msg":"请先通过可信会话确认当前申请","data":null}
```

其他既有错误码：1020100001 服务认证无效；1020100003 开户配置未就绪；1020100004 申请不存在或不属于本人；1020100005 操作重用内容冲突；1020100016 短信验证/安全卡片参数无效。空请求或 JSON 参数校验也可能返回标准参数错误，不能只枚举这些码来判定失败。

## MGS 配置与密钥

先审核并应用 `V20260915_07__trial_connector_operation.sql`，创建两个独立的操作/确认表；原 trial 迁移仍须齐全。再合并 `CONNECTOR_CONFIGURATION.example.yaml`，保留已有业务配置。不要把模板中的占位符作为真实配置启用。

离线生成 Token（不连接数据库、不部署、不输出密钥）：

```bash
python3 script/trial/create_connector_key.py \
  --output /secure/mgs-knowdo-tools \
  --key-id knowdo-tools-202609 --issuer knowdo-trial \
  --capability TOOLS --assistant '<实际助手ID>' --audience anonymous --channel '<实际渠道值>'
```

父目录须已存在；输出目录必须不存在。目录权限 0700，`service-token.txt` 与 `mgs-config.json` 权限 0600。Token 为 32 字节安全随机数；MGS 只配置 SHA-256 摘要。另运行一次生成卡片凭据，换新 key-id/输出目录并指定 `--capability SMS_VERIFICATION --capability CONSENT`，issuer 不变。不要复制到仓库、浏览器前端、聊天或 Agent 提示词。

生成文件中的 connector.enabled 和每个 key.enabled 均默认 false。完成 TLS、允许名单、业务配置及知办安全卡片联调后才启用。配置通过现有部署流程加载/重载后生效；改文件本身不代表运行进程已更新。轮换时先增加同 issuer 新 key-id，切换调用方，再禁用旧 key；每次请求检查当前配置与 expires-at。

原 `mgs.trial.enabled`、运营租户与负责人、体验期限/容量、SMS secret/模板、登录凭据加密密钥、OAuth 客户端和出站回调配置仍须完整。Token 不能替代这些业务配置。真实 HTTPS 必须让服务端安全地识别请求为 secure，不能只添加客户端自报的 `X-Forwarded-Proto`；代理终止 TLS 时须另外核验可信代理边界。

## 知办配置（待知办任务应用）

| 项目 | 值 |
| --- | --- |
| 接口来源 | HTTP / OpenAPI |
| OpenAPI 地址（部署后） | `https://<MGS域名>/v3/api-docs/trial-connector` |
| 业务 API 基础地址 | `https://<MGS域名>`，导入路径已含 `/admin-api`，避免重复拼接 |
| 认证方式 | Bearer Token |
| 凭据归属 | 连接器服务账号 |
| 业务凭据 | TOOLS Token 的完整文本，不带 `Bearer ` 前缀 |
| 传递可信调用身份 | 开启 |
| 允许公开助手调用 | 开启，仅发布上述 4 个本人开户工具 |
| 固定请求头 | 留空，不添加 tenant-id 或 X-KnowDo-Context |

文档读取与业务服务凭据独立。安全卡片三个接口是知办后端集成，不能导入公开连接器，也不能把私有 Token 放入普通服务工具凭据。

本地 `generated-connector-v1/openapi.json` 为实际 Springdoc 隔离导出；`integration-checklist.json` 按方法+路径给出请求体字段映射、成功条件和依据。上下文在 `x-mgs-service-contract` 扩展中声明，不作为可由模型填写的普通 header 参数。运行地址只有部署新版本且 storage-enabled=true 后才存在，本次没有验证线上新地址。

## 尚未完成的两侧联调

只读核对 `/opt/knowdo`，未发现 MGS 当前出站 `HttpKnowdoTrialAdapter` 所需 `/internal/mgs-trials/v1/lookup`、`/ensure` 实现。现有 MGS 出站 HMAC 适配器保留；未改为不等价的知办管理员/成员接口，未伪造远端成功。成员创建、授权、安全卡片交付和撤销契约及 HTTPS 地址须由知办侧确认实现。

安全卡片前后端还需按以上私有契约接线；账号凭据领取和 OAuth 授权仍使用原受限 HMAC 服务接口，不暴露给本次 Bearer 工具。服务入站兼容完成不代表两侧真实开户已可用。在回调和安全卡片缺失时保持试用开关关闭。

## 验证和范围

见 `generated-connector-v1/test-results.json`。10 项新增 HTTP 集成测试覆盖真实 Spring Security 选择、租户过滤器、MVC/校验、H2 事务、短信 proof 与确认（短信供应商及知办回调为隔离替身）；原 HMAC/短信/授权/编排测试 41 项通过。浏览器环境专用测试 1 项按原有条件跳过，不计为通过。离线密钥工具 4 项通过。

实际全量 Springdoc 导出、跨模块字段/引用/响应检查通过；专用组仅 4 个接口，知办当前导入代码 4/4 读取成功码依据。此只读探针没有访问或修改知办数据库，也未应用/发布连接器。全量检查保留原有第三方等人工确认项，不宣称业务接口全部实调用通过。

本次没有生产业务写入，没有真实 SMS、MySQL 生产迁移、账号创建、前端浏览器或双系统真实联调；没有部署。现网版本仍应为上次交付的 v7.1，后续部署须单独验证运行服务导出的新文档。
