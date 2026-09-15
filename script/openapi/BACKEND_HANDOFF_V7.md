# v7：全业务模块参数与成功条件复核

修复提交：`52328f668e`，分支 `feat/knowdo-trial-onboarding`。候选版本 `1.0.0-full-contract-v7`。本次完成源代码、隔离测试和交接，**未部署 v7，未修改知办、未发布连接器、未执行真实业务写入**。运行服务只读核验仍为 `1.0.0-full-contract-v6`。

## 修正结果

共 230 个操作得到文档补充或修正，逐项列于 `generated-v7/affected-operations.json`，保留此前全部修复。

- 220 个自有零参数方法明确说明“业务参数映射允许为空”，同时输出 `x-business-input`。只有反射确认零参数且 OpenAPI 无参数/请求体的方法才适用；动态 Servlet、Map、第三方方法不按此推断。
- `GET /admin-api/erp/stock/get`：必须提供库存 id，或同时提供 productId、warehouseId；有 id 时仍保持原有优先级。实际使用的编号须大于 0，无效组合在服务调用前返回 code=400。查询不到数据仍为 code=0、data=null。文档说明和 `x-parameter-constraints` 保留组合规则，不把三个替代字段都标成必填。
- `GET /admin-api/erp/sale-statistics/time-summary` 和 `GET /admin-api/erp/purchase-statistics/time-summary`：默认 6 个自然月，含当前月，按月份升序。新增 1～120 的请求限制并实际校验。**120 是本次新增的技术上限**，用于限制逐月数据库聚合次数；原来大于 120 或非正数的请求现在会被拒绝。
- `POST /admin-api/system/oauth2/check-token`：明确 token 必填，表单编码，客户端凭据使用 HTTP Basic 或 client_id/client_secret，不能发送 JSON。原文档已有正确的表单字段，此次补充交互说明，没有把它伪装成 JSON。
- `GET /admin-api/crm/trial-operations/page` 的 pageNo/pageSize、`GET /admin-api/crm/trial-operations/get` 的 applicationId 补齐来源、默认值及范围说明。8 个试用相关模型补齐事件、进度、步骤结果、客户和跟进说明；不改变原业务输出。
- 全量导出测试现在启用试用模块的文档扫描条件，并向扫描器传入同一环境。此前隔离扫描遗漏这组条件控制器，因此没有拦截后续新增字段缺少说明的问题。该测试只有文档上下文，不启动试用服务或数据库连接。
- 校验器支持同一 MVC 方法的长短路径变体。例如报表 `/view/{id}` 不应要求仅存在于 `/view/{id}/{type}/{jmRecordId}` 的可选路径变量。该项是修复检查误报，没有为短路径捏造 query 参数。

## 全量验证

| 检查 | 结果 |
| --- | --- |
| 业务 all 分组 | 21 个模块，3,052 个操作，3,216 个 Schema，17,880 个属性 |
| 原始扫描 | 3,449 个操作，3,339 个 Schema；包含第三方控制器及 HEAD/OPTIONS |
| 真实 Java 签名与参数绑定 | 业务 2,349 项、原始全量 2,861 项；错误 0 |
| 引用与同名模型 | 451 处源模型引用比较；错误引用 0，重复 operationId 0 |
| 自有模块说明 | 缺失业务摘要、模型属性说明、直接参数说明均为 0 |
| 隔离测试 | 51 项 JUnit、9 项 Python，全部通过，无跳过 |
| 当前知办解析代码只读复现 | 候选文档导入 3,415 个工具，2,871 个 CommonResult 处理成功依据全部被 supportsSuccess 接受 |

数量口径不同：原始文档包含导入器不支持的 HTTP 方法及不导入的工具； all 分组排除了根路径下的部分 SDK 接口。不能把“导出操作数”直接当作“已发布工具数”。

销售订单新增、修改继续展开为真实业务 items：productId、count、productPrice 必填；productUnitId 存在于 Schema，但为 readOnly，由后端查商品单位，调用方不必提交。只有 id/sort 的明细、空/空元素清单、缺少商品/数量及无效商品由已有 MVC/H2 回归检查拒绝；校验失败不留下订单或明细。合法请求使用隔离夹具通过，未调用生产创建接口。

失败误判回归验证：HTTP 200 下的非零 code 仍失败；code 缺失、字符串 "0"、false 均不能替代数字 0；data=null、[]、false 不自动代表失败。样例来自隔离实际响应，见 `response-examples.json`、`invalid-order-response.json`。库存及统计边界采用 MockMvc 和模拟服务，验证错误请求不进入业务服务。

## 为什么知办截图仍有问题

已只读获取线上 v6 全量文档并调用当前 `/opt/knowdo` 导入函数：2,871 个 CommonResult 接口均能从标准响应 Schema 的 code 描述读取“0 表示成功，非 0 表示失败”，且 code 是整数、未限制为只能返回 0。线上文档本身并非普遍缺少成功依据。

当前知办 `inspectTools` 在未配置 success 时会显示“尚未配置业务成功条件”；**文档有依据不代表连接器已经配置成功条件**。截图中 AI 声称无依据的具体记录，还需知办任务核对当前保存的 responseContracts、文档版本和同步合并冲突。本次没有读取其数据库或断言其已保存的记录一定陈旧。

只读源码与纯解析复现还确认：

1. `openapi.ts` 仅导入 application/json 请求体。全量 23 个非 JSON 请求体（2 个表单、21 个 multipart）需要导入器正确处理媒体类型、文件字段及编码；其中 check-token 的 token 在 MGS 文档中存在，但导入后丢失。不能通过删除表单定义或改要求 JSON 修复。
2. `document-tools.ts` 的 reviewMetadata 会丢弃 minimum、maximum、default 等结构化信息。站内信分页的这些字段在实际文档和导入结果中均存在，送入检查模型时应保留。无需把有默认值的分页字段硬改为必填。
3. 已确认无业务参数的接口允许空映射。根据方法+路径、占位符、请求体和明确说明判断，不因为 mappings=[] 就要求补充虚构输入。
4. 成功条件必须依据 responseContracts，保持数字类型。CommonResult 可用 `{"path":"code","equals":0}` 判断本次接口处理成功。OAuth approveOrDeny 的 data 可表示拒绝，不能把 code=0 解释成用户同意授权；异步受理也不代表最终流程完成。

只读复现脚本为 `check_knowdo_import.ts`，证据为 `generated-v7/knowdo-parser-evidence.json` 和 `live-v6-knowdo-parser-evidence.json`。它们没有运行 AI 检查、修改连接器或对工具进行真实业务调用。

## 剩余人工项与覆盖边界

- 209 个业务接口有专门协议/配置待核验，其中包括 163 个文件下载，另有流式、原始回调、OAuth 授权结果、动态 Map、递归和多态输入。它们不能统一套用 code=0；逐接口原因见 `manual-review.json`。
- 原始 SDK 范围仍有 392 个操作缺少业务摘要、752 个模型属性及 610 个参数声明缺少业务说明（参数涉及 205 个操作，含不同 HTTP 方法）。没有可靠业务依据的第三方字段保留为人工确认，不用无意义文字伪装补齐。这些是 all 分组之外的 SDK 文档边界，不应直接全量发布成业务 Agent 工具。
- 线上有两个 easyTrans 自动配置路径未进入隔离源控制器扫描：`GET /easyTrans/proxy/{targetClass}/findById/{id}`、`POST /easyTrans/proxy/{targetClass}/findByIds`。不宣称已验证这两个运行时路径的新版本契约。
- 没有对每个接口做真实端到端业务调用；权限、第三方服务、存储和异步完成状态不属于本次隔离验证结论。
- 截图中 6 个 AI 检查失败的具体异常未提供。本次未重试知办检查，不能保证此计数因源代码修改而归零。

## 交付与同步

`generated-v7/openapi-all.json.gz` 是修复后的业务文档候选，`openapi-raw.json.gz` 是原始全量候选。解压后为 UTF-8 JSON。`integration-checklist-*.json.gz` 按“请求方法 + 完整路径”给出 operationId、参数来源与目标、类型、字段路径、媒体类型、条件组合、成功条件、错误路径和证据；不猜测知办工具参数名。

`checksums.json` 记录交付文件哈希；文档解压后的 SHA-256 在 schema-audit 文件中。核对文件仅供接入验证，**不表示知办已经读取或应用**。

后续部署并确认 v7 后，业务文档地址为 `http://192.168.1.199:8088/v3/api-docs/all`，原始地址为 `http://192.168.1.199:8088/v3/api-docs`。这些地址目前仍是 v6；运行服务的 v7 文档尚未验证。

知办任务需重新同步 description、parameters、requestBody、components/schemas、responseContracts，并处理已有手工配置的合并冲突；重新检查 affected-operations 清单及所有 CommonResult 成功条件。重点回归订单新增/修改、两个统计、库存两种定位、无参数接口、表单令牌、上传和试用模块。成功条件和读写性质不应因文档同步被无条件覆盖。

复现：先按 `requirements-integration.txt` 安装独立 Python 环境，再设置 `OPENAPI_PYTHON` 执行 `script/openapi/run_integration_checks.sh`。可选地用知办已有 tsx 执行：

```bash
/opt/knowdo/node_modules/.bin/tsx script/openapi/check_knowdo_import.ts \
  yudao-server/target/full-openapi-isolated.json /opt/knowdo /tmp/mgs-knowdo-import-evidence.json
```
