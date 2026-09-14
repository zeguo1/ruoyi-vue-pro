# 知办内部接入契约（开发候选，尚未真实联调）

## 路径与职责
访客在官网客服对话登记、验证必要联系方式并确认开通；知办可信服务调用 MGS 试用工具。不得解除公开助手原有工具限制。身份签名、确认凭据、幂等键由知办服务端生成，模型参数中不出现这些字段。

用户补充：租户 1 专用于演示，另建栖云租户用于企业真实业务和运营线索。体验不选择套餐。所有申请和演示业务办理通过 Agent，MGS 只提供本人办理结果查看页。首期业务闭环仍为演示客户查询和新增跟进；不得把完整 ERP/其他模块权限机械开放给试用用户。

## 入站签名 v1
只允许服务到服务 HTTPS。四个工具均 POST，基础路径 `/admin-api/crm/trial-tool`：
- `/submit` — `submit_trial_application`，body `{team,contactName,scenario:"CRM_FOLLOW_UP"}`。
- `/create-accounts` — `create_trial_accounts`，body `{applicationId}`；只确认和入队，不同步等待整套开户。
- `/status` — `get_trial_status`，body `{applicationId}`；只读业务状态，绝不调用开户。
- `/guide` — `get_trial_guide`，body `{applicationId}`；只读说明和实际样例引用。

所有请求 header `X-Mgs-Trial-*`：Key、Timestamp（Unix 秒）、Nonce（16–64 位）、Subject（知办认证主体的稳定不透明 ID）、Verified（true）、Email（验证后的邮箱，仅 submit 必需）、Confirmation（当前申请的确认事实，由可信卡片会话提供，create 必需）、Idempotency（submit 必需）、Signature。

HMAC-SHA256 使用 UTF-8 密钥，输出小写十六进制。签名原文是下列各行，LF 分隔，无末尾 LF；空 header 使用空字符串：
```
mgs-trial-v1
<Key>
<Timestamp>
<Nonce>
<HTTP METHOD>
<完整请求路径>
<SHA256(原始请求体字节)，小写十六进制>
<Subject>
<Verified>
<Email>
<Confirmation>
<Idempotency>
```

允许时钟误差 120 秒；已使用 nonce 在数据库拒绝重放，即使业务响应丢失也必须以新 nonce、原幂等键恢复。禁止 tenant-id、visit-tenant-id 和查询串。验证主体绝不能来自模型自报的邮箱、手机号或企业域。签名方必须核验对话会话、联系验证和当前申请确认的绑定；不能只替模型提供的 header 签名。

## 结果语义
`code=0`（数字）只代表本次命令/查询成功；非 0 为失败，msg 为安全错误说明。
整套开户就绪条件是 `code=0 AND data.accountReady=true`。SUBMITTED/PROVISIONING 均不是账号就绪，READY 也不是公众号已绑定或业务已完成。过期时 accountReady 恒为 false。
状态工具只返回申请编号、步骤状态、安全错误码和非秘密卡片引用。不得把密码、业务 token、绑定码、验证码放进工具结果、提示词、CRM 或普通日志。

## 出站接口（知办尚需实现）
`POST {knowdo-base-url}/internal/mgs-trials/v1/lookup` 和 `/ensure`。
请求 JSON：contractVersion=`mgs-trial-v1`，applicationId，idempotencyKey=`applicationId:step`，step，issuer，subjectId，expiresAt（UTC ISO8601），resources（必要 MGS/知办资源 ID）。
步骤：KNOWDO_MEMBER（体验成员）、KNOWDO_AUTH（仅本人 MGS 授权）、DELIVERY（安全激活/公众号绑定交付准备）、KNOWDO_REVOKE（体验授权及既有渠道后续业务撤销）。

出站签名 header Key/Timestamp/Nonce/Signature，原文：
```
mgs-trial-outbound-v1
<Key>
<Timestamp>
<Nonce>
POST
<完整请求路径>
<SHA256(原始请求体)>
```
知办需要校验服务权限、时效、nonce，并对每个申请/步骤提供并发安全持久化幂等。

响应必须 HTTP 200 且 `{contractVersion,applicationId,step,state,resourceIds}` 与请求一致。
- state=ABSENT：权威确认该幂等操作不存在，可以 ensure。404、超时、鉴权失败绝不是 ABSENT。
- state=PENDING：不得重建，后续 lookup。
- state=COMPLETE：必须返回已持久化资源依据。MEMBER: knowdoTenantId/knowdoMemberId；AUTH: authorizationId；DELIVERY: deliveryRef；REVOKE: revocationId，额外 `allBusinessAccessRevoked:true`（布尔）。

到期撤销必须为整个申请建立持久化撤销标记；并发/迟到的 ensure 不得在撤销后重新创建有效业务授权。成员可保留公开咨询能力。MGS 先 lookup 再 ensure，外部调用不占用数据库事务。

## 安全卡片与公众号（待知办实现/联调）
- deliveryRef 只是记录引用，不能单独领取凭据。必须凭原申请人的可信会话领取；绑定/激活凭据不能转给其他主体。
- 安全卡片领取、一次性兑换、过期、重复领取返回语义必须知办持久化验证。MGS 不提供让模型领取凭据的工具。
- 个人 MGS 授权安全交换已实现下述独立接口；账号激活及卡片仍待对接，当前不能据此宣称真正开户完成。
- 公众号绑定只发送 BOUND 事实，不把点击/关注按钮当成绑定。
- 首次业务完成发送 FIRST_BUSINESS_COMPLETED，包含 MGS 实际返回的跟进 ID。

事件 `POST /admin-api/crm/trial-event/accept` 使用 EVENTS 独立签名能力，body `{eventId,applicationId,type,knowdoMemberId,businessRecordId?}`。MGS 按主体归属、成员映射、事件幂等键核验；首次业务必须有本申请实际持久化跟进及已绑定事实。通知发送失败不触发重新开户。

## 当前验证边界
已运行本地 H2 状态机/签名测试、实际 MGS 登录/OAuth/CRM/权限链路的 MockMvc 联合测试，以及三个边界的独立 JVM 强制中断与文件 H2 冷启动恢复测试。知办仍为明确替身；实际浏览器与后端联网、真实知办安全卡片、公众号及部署环境尚未联调。参见 ACCEPTANCE.md 和 test-evidence.json；此文件为对接候选，尚不是上线验收结果。


## MGS 个人授权交付（已实现，待真实联调）
新增 MGS_GRANT 步骤，位于 KNOWDO_MEMBER 完成之后、KNOWDO_AUTH 之前。此步骤在原有 MGS OAuth 存储创建普通用户授权，步骤记录只包含 `mgsAuthorizationRef`。不重新创建用户、不交付管理员、不把访问令牌保存到 CRM/申请/步骤表。

知办 ensure KNOWDO_AUTH 时接收 `mgsAuthorizationRef`，用单独的 AUTHORIZATION 服务密钥调用：
`POST /admin-api/crm/trial-internal/authorization`，body `{applicationId}`。沿用入站 v1 签名与原申请主体。该接口要求服务实际识别 HTTPS，不自行信任调用方传入的 X-Forwarded-Proto；部署时须由受信代理正确设置安全请求属性。

响应 `CommonResult.data` 为 `{accessToken,expiresAt}`，仅允许进入知办个人连接器的服务端凭据存储。刷新令牌不出 MGS；访问令牌到期前知办通过同一接口更新，仅续用原授权。返回的 expiresAt 不晚于申请截止时间。原授权已撤销/失效时拒绝更新，不自动再授权。

此接口明确隐藏于 Agent OpenAPI，并关闭请求/响应访问日志、设置 Cache-Control: no-store。不能将它包装成 Agent 工具，不能把响应传入对话。知办在凭据安装并核对申请、个人成员和凭据归属一致后，返回 KNOWDO_AUTH COMPLETE；READY 前业务接口会拒绝访问，不能把提前调用业务接口作为该步骤完成前提。真实业务权限调用验收在 READY 后执行。

两侧应用首次就绪前，MGS 的普通业务接口仍拒绝试用账号访问。到期先撤销角色/禁用用户，并删除其访问令牌、刷新令牌及刷新令牌作为 bearer 使用时的缓存，包括只剩刷新令牌的会话。原有 OAuth 存储仍由 MGS 的既有安全与数据备份措施保护。

## 普通 MGS 登录的安全卡片交付（MGS 已实现，知办侧待对接）

MGS 开户事务生成 16 字符随机密码，现有用户服务按 BCrypt 保存登录哈希；新增交付表仅保存 AES-256-GCM 密文、独立版本密钥编号和随机 nonce，关联申请身份/用户/租户作为认证附加数据。服务签名密钥与加密密钥必须分离。密码不写入申请、步骤、CRM、任务结果或工具返回。

DELIVERY 的 ensure 只建立绑定/登录安全卡片记录及原会话归属，返回非秘密 `deliveryRef`。它不能预先调用下述领取接口，否则会形成等待 READY 的循环。后台完成全部步骤进入 READY 后，访客通过原可信安全会话主动查看卡片时，由知办服务端调用：

`POST /admin-api/crm/trial-internal/login-delivery`

body 只有 `{applicationId}`。使用独立 `DELIVERY` 签名能力，Subject 仍为原已验证申请人；`X-Mgs-Trial-Idempotency` 为此安全领取动作的 16–128 位标识，由知办服务端生成并绑定原会话，不能由模型提供。每次网络重试使用新的签名 nonce，保留相同领取标识。

成功响应的 `data` 为 `{username,password,tenantId,loginUrl,expiresAt,retryUntil}`。**这个响应只能进入可信安全卡片通道，不能进入 Agent 上下文、工具结果、CRM、访问日志或普通分析埋点。** MGS 端接口隐藏于工具 OpenAPI、关闭访问日志并设置 no-store，要求实际 HTTPS。知办也需独立落实会话归属、日志屏蔽、禁止缓存与凭据展示策略。

- 只接受 READY、已确认、全部步骤完成且未到期的原申请；已停用账号或原密码被管理员修改时拒绝领取，不重置密码。
- 首次领取记下领取标识哈希，允许原主体同一领取动作在 5 分钟内有限网络重试；不同动作、换主体、超过 retryUntil 均拒绝。申请编号、卡片引用或公开二维码都不足以领取。
- 密码有效期不晚于 `expiresAt`；`retryUntil` 是服务间领取重试截止时间。维护任务清除超过领取重试窗口或申请期限的密文，到期撤权也清除密文，保留非秘密登记以防重建。
- 加密密钥轮换保留旧编号直到相关密文不再可领取；缺失旧密钥时拒绝交付，不生成新密码覆盖原账号。
- 安全卡片告知演示租户编号和用户名，复用既有 MGS 登录页面查看结果；不要求在开通或公众号体验之前登录 MGS，也不要求登录知办后台取得绑定码。不要在 URL 中放密码/令牌。
- 超过领取窗口但用户未成功收到密码，先人工核验安全会话交付记录；本接口不提供自动重置/重新领取入口。知办绑定凭据的防转发、一次性兑换、过期仍由其安全卡片接口落实，尚待真实验收。

错误 `1020100014` 表示登录交付未满足条件或已失效；签名错误、原申请归属、配置缺失沿用已有错误码。`code=0` 只表示本次私有交付成功，不表示已登录或公众号绑定成功。
