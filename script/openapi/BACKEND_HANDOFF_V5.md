# 全业务模块后端契约修复与交接（v5）

2026-09-14，代码提交 `43de3eaaa7` 已推送到 `origin/master-jdk17`，已部署至 192.168.1.199。文档版本 `1.0.0-full-contract-v5`。

## 运行文档及核验范围

- 全业务文档：[all](http://192.168.1.199:8088/v3/api-docs/all)，覆盖全部 19 个业务模块，3040 个操作、3193 个模型、17800 个字段。
- 原始总文档：[root](http://192.168.1.199:8088/v3/api-docs)，3439 个操作，额外包含第三方内部接口。
- ERP：[erp](http://192.168.1.199:8088/v3/api-docs/erp)；系统管理：[system](http://192.168.1.199:8088/v3/api-docs/system)。其他模块可从 `/v3/api-docs/swagger-config` 获取。
- 从实际运行服务重新获取全部 20 份分组文档，摘要、字段描述、引用、operationId、示例及默认值类型检查通过。运行文档有 450 处嵌套引用与实际 Java 类型直接比对，一致。
- 全业务 JSON SHA-256：`88eb1ca72d5312ceb4bb3b47fd94d8a0a566febcb40872bb72df67aa10963b36`。
- 运行 JAR SHA-256：`5e6d134bfedc2705da72a6046a175fa0191a68f681b51c4d5d1c56a874f35207`，已在容器内读回核对。
- `mgs199-server-1` 健康，启动时间 `2026-09-14T12:59:48.072822618Z`，核验时重启次数 0；Nginx 配置检查和重载完成。

原始总文档仍保留 394 个第三方内部接口摘要、810 个第三方字段说明缺口，见 `v5-raw-verification.json`。本次没有臆测或修改不可核实的第三方业务语义。所有业务分组均通过上述检查，不代表逐一执行了所有接口的全部业务分支。

## 根因和后端修复

### JSON 响应声明

Springdoc 将未显式声明 produces 的统一响应生成成 `*/*`。知办的成功条件依据校验只接受 JSON 媒体类型，即使字段已经写明 `code=0` 成功，也会拒绝采用。

按实际 Controller 返回类型为 CommonResult 的接口，将单一通配响应媒体类型明确为 `application/json`，覆盖所有模块的 2866 个运行接口。显式声明的媒体类型、支付回调、微信文本/XML、下载和 SSE 保持原有契约，未改变实际响应体。验证码独立声明 JSON，成功条件仍是字符串 `repCode="0000"`。

用线上新文档调用本地知办导入和依据校验函数，ERP 创建订单、系统创建部门、验证码校验三个代表接口都通过成功条件依据检查。此为离线元数据验证，没有调用实际业务接口，也没有更改知办连接器。

### 创建与更新编号

此前只纠正了误标为创建必填的 62 组模型；这次全量扫描进一步发现 192 个更新入口实际按已有 id 更新，但未在入口校验和文档中要求 id。

本次补齐 192 个入口，分布为：MES 80、HRM 22、系统管理 17、PMS 14、FMS 13、WMS 9、IoT 7、AI 6、CRM 6、基础设施 6、IM 5、商城商品 4、BPM 3。详细 Controller、请求类型和 Service 实现证据见 `v5-update-contracts.json`。

- 用 Update 校验组要求更新编号，继承原有 Default 校验；缺失编号在业务调用前返回参数错误。
- 共享模型的 id 显式为创建可选；更新操作通过标准 `allOf` 声明必填。BPM 流程模型 id 保持字符串，未误转为整数。
- 创建可选不代表应生成或填写示例编号。描述明确要求更新编号来自已有记录。
- 部门、字典数据、字典类型这三个已核实创建接口明确由后端生成编号，调用方传入 id 会被忽略；本次未给其他创建接口笼统添加“传入值忽略”的承诺。
- HRM 定薪/调薪接口是允许新建或编辑的例外，继续允许省略 id，并说明首次定薪查找已有记录的规则。
- 示例主子表更新通过校验组转换，允许在修改已有主表时新增没有 id 的子表记录；单独更新子表仍需要已有 id。

实际运行的全量文档共核验 339 个含 id 的业务更新操作，除明确的定薪例外外，不存在未解释的可选更新 id。原有 ERP 订单明细、商品单位、数量、单价规则继续保留，没有要求调用方编造数据。

### 授权列表

涉及：

- `POST /admin-api/system/permission/assign-role-menu`
- `POST /admin-api/system/permission/assign-user-role`

明确这些接口全量替换现有授权，不是追加。列表遗漏、null 或空数组都会清空对应的全部授权。追加前应查询现有授权并合并。字段允许 null 的语义同时进入 OpenAPI 3.1 类型定义；列表元素不能是 null。

菜单授权入口在租户过滤前将列表规范化为可修改集合，修复显式 null 或不可修改集合可能先触发异常的问题；租户套餐过滤仍生效。没有将空列表改成必填，也没有为使检查通过而改变清空授权的既有业务约定。

### 验证码请求分阶段

- `POST /admin-api/system/captcha/get`：只要求 `captchaType`，可选 `clientUid`、`ts`。
- `POST /admin-api/system/captcha/check`：要求 `captchaType`、`token`、`pointJson`，可选 `clientUid`、`ts`。
- 支持当前可用的滑动拼图 blockPuzzle 和文字点选 clickWord；未知类型、缺失 token 或坐标会在进入 SDK 前拒绝。
- token 来自获取挑战的响应，坐标来自真实用户交互并按验证码协议编码。captchaVerification 属于后续业务二次验证，不应作为 check 的必填项；服务端生成的图片、密钥和编号不再混入请求模型。
- 参数校验保留验证码组件响应格式，错误包含具体字段；不会调用 Redis/SDK 后才发现缺失字段。

## 回归结果

共 42 项测试，0 失败、0 错误、0 跳过：

| 测试 | 数量 |
|---|---:|
| ErpSaleOrderContractTest（独立 H2，含失败不留数据） | 24 |
| BackendHandoffContractTest（ID、创建编号、主子表、验证码、授权列表） | 6 |
| FullContractValidationTest | 4 |
| ContractSchemaCustomizerTest | 3 |
| OpenApiItemSchemaTest | 2 |
| FullOpenApiExportTest | 1 |
| FullOpenApiModelAuditTest | 1 |
| ExternalOpenApiContractTest | 1 |

完整 Maven package 通过。隔离文档上下文扫描 588 个控制器、2130 个模型，455 处嵌套引用与 Java 类型一致；不加载业务数据库或 Redis。新的入口校验测试使用实际 Controller 与校验器、模拟 Service/Mapper，验证失败不会调用业务服务。ERP 数据回滚验证使用独立 H2，没有在生产创建订单、修改授权或操作业务数据。

新增校验示例均来自隔离测试，不能直接将示例编号用于生产：

创建部门不提交 id：

```json
{"name":"隔离测试部门","sort":0,"status":0}
```

修改部门缺少 id 时，HTTP 200 返回的关键错误字段为：

```json
{"code":400,"msg":"请求参数不正确:id: 修改记录编号不能为空"}
```

验证码 check 缺少坐标时，响应关键字段为：

```json
{"repCode":"0016","repMsg":"pointJson: 验证码坐标 pointJson 不能为空"}
```

## 知办交接与未完成的知办工作

本任务完成的是后端源码、部署和实际运行 OpenAPI 验证。知办连接器未同步、未应用草稿、未发布。

知办任务仍需：

1. 修复导入与分析元数据中 `readOnly`、`exclusiveMinimum`、枚举、范围等约束丢失；正确处理标准 `allOf` 请求体及其 required 合并。
2. 不能因创建模型存在可选 id 就要求 AI 填写；不能把空数组、遗漏、null、全量覆盖和追加混为一谈。
3. 重新获取 v5 文档，核对版本和摘要，检查差异后应用草稿并发布。旧 418 个接口检查报告不能自动代表新文档的检查结果。
4. 对 CommonResult 使用有文档依据的数字 `code=0`；验证码使用字符串 `repCode="0000"`；下载、回调、流式接口按各自契约处理，不能套统一成功码。

本次未修改 `/opt/knowdo` 的源码或部署。

## 机器可读证据与复验

- `v5-affected-operations.json`：相对 v4，2868 个操作的定义或引用模型发生变化。
- `v5-update-contracts.json`：192 个新增修复入口及实现证据。
- `v5-runtime-contract-verification.json`：业务语义专项检查及 339 个更新 ID 检查。
- `v5-business-verification.json`、`v5-raw-verification.json`、`v5-group-verification.json`：完整运行文档检查。
- `v5-test-verification.json`、`v5-deployment-verification.json`：测试、实际运行包、部署及离线兼容性证据。

```bash
python3 script/openapi/audit_backend_handoff.py downloaded-openapi-all.json --output contract-check.json
python3 script/openapi/audit_full_contract.py downloaded-openapi-all.json --output schema-check.json --strict
```

上一个运行版本的回退包：`/opt/mgs/backups/full-contract/server-before-backend-v5.jar`。服务使用文件挂载 JAR；后续打包或回退需先移开当前目标文件，避免原地覆盖正在运行的 JAR inode，再替换文件并重建 server 容器。
