# v6：OpenAPI 与知办接入核对交付（开发完成，未部署）

本次基于 v5 已完成修复继续开发，保留 FQN 模型命名、创建/更新 ID 分组、ERP 明细校验及既有权限逻辑。GitHub 创建要求已取消；没有修改 `/opt/knowdo`、连接器或知办数据库，没有执行生产业务写入。

修复提交：`8eda2e0ea41702d78e88ad2ea83efd78b922e152`。分支：`fix/knowdo-openapi-integration-v6`。

## 交付文件

所有候选文件由隔离的 Spring MVC / springdoc 上下文实际导出，采用本项目生产 Jackson 配置、扁平查询参数配置和 all 分组构建逻辑。控制器仅注册元数据，不调用生产服务；另用 H2 和模拟业务服务验证请求。

| 文件（相对本目录） | 用途 |
| --- | --- |
| `generated-v6/openapi-all.json.gz` | 业务全量候选 OpenAPI，3040 个操作、3195 个 Schema |
| `generated-v6/openapi-raw.json.gz` | 原始扫描候选 OpenAPI，3437 个操作，含 397 个 all 分组之外的接口 |
| `generated-v6/integration-checklist-all.json.gz` | 按 `METHOD /完整路径` 索引的业务接入核对文件 |
| `generated-v6/integration-checklist-raw.json.gz` | 原始扫描范围的核对文件，含第三方接口及不能确认的条目 |
| `generated-v6/manual-review.json` | 209 个业务接口的人工配置/核验原因，以及 397 个原始额外接口的逐项记录 |
| `generated-v6/affected-operations.json` | 受影响接口与 Schema 清单，对比只读获取的线上 v5 文档 |
| `generated-v6/schema-audit-*.json` | 引用、类型、说明、默认值、示例、重复 operationId 的检查结果 |
| `generated-v6/wire-verification.json` | 2339 项显式绑定对比、下载、响应实例、映射证据指针检查 |
| `generated-v6/request-response-examples.json` | H2/模拟 MVC 的脱敏请求与实际响应；编号仅来自隔离夹具 |
| `generated-v6/test-results.json` | 测试结果及覆盖边界 |
| `generated-v6/deployment-status.json` | 线上 v5 只读核查结果；候选 v6 未部署 |

`.json.gz` 解压后是 UTF-8 JSON；生成器也可直接输出未压缩 `.json`。它们是接入核对材料，不表示知办已读取或应用。

候选版本：`1.0.0-full-contract-v6`。

业务文档 SHA-256（解压后）：`d6d7e37772693bf2ee03f5842c30b009165200f58796ff19c15b188d75ffeb15`。
原始文档 SHA-256（解压后）：`ead4091d8b76a37f0ac26c08055880e1958d55a83d88a70786170e04798655f6`。

## 本次修正及依据

1. **文件上传和导入。** 核实实际 `MultipartFile` / `@ModelAttribute` 绑定，修正 17 处缺少的 multipart consumes 声明，并保留已有正确声明。实际导出按已核实的上传方法给出 `multipart/form-data`、`file: string/binary` 和随表单提交的字段；`updateSupport` 为 boolean，默认 false。文件管理的管理端和应用端分别使用自己的上传 DTO。回归验证缺失文件被拒绝、目录与文件内容传给模拟存储服务。
2. **扁平查询参数。** springdoc 的扁平模式与注解组合会把部分列表和标量声明成字符串。根据实际 Java 签名恢复标量/数组类型、必填性和真实默认值，兼容未显式填写 `@RequestParam` 名称的情况。生成完整方法签名证据，区分重载方法。补充 81 个操作的 114 处直接参数说明，详见 `v6-parameter-corrections.json`。
3. **时间约定。** 原 `LocalDateTime` 反序列化器仅调用 `getValueAsLong()`，非法字符串可能被静默转为 epoch 0。现在保留整数毫秒时间戳，支持 ISO 日期时间、带偏移时间，以及字段 `@JsonFormat` 指定的字符串，严格拒绝非法日期、浮点数、布尔值等。无时区字符串按服务端本地时间解释，带偏移字符串按同一时刻转换。JSON 的时间约定与 MVC query 的 `@DateTimeFormat`（包括 ISO 日期）分开声明。CRM 产品分类中实际未参与过滤的 createTime 兼容字段从文档隐藏。`LocalDate` / `LocalTime` 在当前配置下默认输出数组，字段指定格式时输出字符串；时间数组的输入纳秒与输出毫秒差异已注明。
4. **分页及数字。** `pageNo=1`、`pageSize=10` 是 DTO 已存在的默认值，文档不再要求调用方必须提供；保留 1～200 的条数校验。`-1` 是导出业务内部赋值。Long 响应按已有序列化器声明为整数或十进制字符串，边界及超出 JavaScript 安全整数范围的值保持字符串，调用方应避免有损转换。
5. **请求拒绝。** 日期和 JSON 类型错误返回参数错误，包含字段路径；嵌套数字格式错误可定位到 `items[0].count`。没有用异常兜底代替订单明细的业务校验。12 类 ERP 单据审核状态增加实际枚举校验：10 未审核，20 已审核；其它状态在业务服务之前被拒绝。
6. **真实返回约定。** 仅为确认返回 `CommonResult<T>` 的操作添加整数 `code=0` 依据及 `msg` 错误路径；code 不限制为单一成功枚举，data 保留具体泛型并允许 null。验证码保留 `repCode="0000"` 的字符串约定。163 个实际调用文件写出工具的接口声明准确媒体类型与 binary 响应，详见 `v6-download-contracts.json`。流式、原始文本回调、无响应体等不套用通用成功条件。
7. **OAuth。** 令牌和校验接口推荐 form-urlencoded，保留服务端接受 query 的兼容说明；客户端凭据为 HTTP Basic 或 client_id/client_secret。明确支持的 grant_type 和各模式字段来源，令牌在 `CommonResult.data`。申请授权返回 code=0 时 data 仍可能为空或包含 access_denied，因此没有把它判为“用户已同意授权”。
8. **响应样例。** 部门查询的空数组/null 均为正常成功；销售订单空明细为参数错误。修复 springdoc 在“仅声明样例”的 `@Content` 上生成占位字符串 Schema 的问题，保留真实泛型返回结构。样例经实际导出的 JSON Schema 校验。

原销售订单 `Item` 错连属于不同业务嵌套类同名造成的 Schema 覆盖，已有 FQN 修复继续生效。本次又对 450 处源模型引用进行比较，未发现错连；创建和修改的 items 都展开为真实订单明细。

## 销售订单约定与隔离样例

创建不需要提交订单 id，更新需要已有订单 id。items 至少一项，元素不能为 null；productId、count、productPrice 必填且大于 0。单位 productUnitId 由后端从产品资料取得，文档为 readOnly，调用方不用提交，传入值被忽略。优惠率/税率和金额限制保留实际校验；不替调用方猜数量、价格或编号。

下面来自 H2 夹具：客户 201、产品 101，产品单位由夹具资料提供；这组编号不能直接用于生产。

```json
{"customerId":201,"orderTime":"2026-09-14T10:00:00","items":[{"productId":101,"count":2.5,"productPrice":100}]}
```

实际错误响应、合法创建返回、无效产品业务错误及空查询返回，见 `generated-v6/request-response-examples.json`。空查询的 `data=[]` / `data=null` 都可以成功；HTTP 200 下的非零业务码仍失败。成功条件表示本次接口处理结果，不等于异步业务最终完成。

## 核对文件格式

每项含 operationId、完整 Java 方法签名、参数来源/目标位置、字段路径、类型、必填信息、媒体类型、原始 Schema 指针、成功条件、错误字段路径、判断依据和人工核验原因。

```json
{"successCondition":{"path":"code","equals":0},"successValueType":"integer","errorMessagePath":"msg"}
```

0 是 JSON 数字。比较器同时检查值和类型，不接受字符串 "0"、false、缺失 code 或非零业务码。不使用 HTTP 200、data 非空或 data=true 作为通用成功条件。

参数路径来自 OpenAPI：根节点 `$`、数组元素 `[]`；path/query/header/requestBody 分开记录，requestBody 附媒体类型。`requiredWhenParentPresent` 表示直接父对象存在时的必填，仍须结合祖先和分支约束；`structureOnly` 节点用于描述结构，不能与叶字段重复提交。readOnly 字段进入 derivedFields。`allOf` 按交集合并，保留原始约束指针；递归、动态键和对象 oneOf 分支不会被猜测成固定字段。

## 覆盖与人工核验

业务全量包含 21 个路径分组：AI、BPM、CRM、ERP、FMS、HRM、IM、infra、IoT、member、MES、MP、pay、PMS、product、promotion、report、statistics、system、trade、WMS；逐组数量见 integration-summary-all.json。

业务文档的引用、450 处源模型引用比较、operationId 唯一性、路径参数、字段说明、示例和默认值检查通过。2339 项显式绑定已与真实签名对比；2130 个 VO 的元数据和嵌套校验覆盖由模型审计验证。该检查不等于对所有接口进行真实业务端到端调用，也没有验证生产账号权限、第三方连接、存储、回调签名或异步完成状态。

2867 个业务接口有可确认的成功条件。209 个业务接口仍需人工配置/核验，其中 163 个为文件下载。其它情形包括：3 个 SSE 流式接口、无响应体的订阅消息发送、公众号/支付原始回调、OAuth 授权是否同意、文件通配路径、动态 Map、BPM 递归结构和 IoT 多态配置。逐接口原因均已列入 manual-review.json，不能用默认 code=0 补齐。

原始扫描另有 397 个 all 分组之外的接口，整体原始文档仍有 392 个第三方操作缺少业务摘要、752 个第三方模型字段缺少说明；保留为人工核验，没有为它们编造业务语义。原始扫描包含按源码发现的控制器，不保证这些控制器在当前部署条件下均启用。

## 知办交接与部署状态

知办应在后续正式部署 v6 并确认版本后重新同步**业务全量文档**，检查参数 in/name/type/required、requestBody/content、items/ref/allOf/oneOf/anyOf、readOnly、默认值、日期格式、枚举及成功条件来源。本次 2434 个 Schema 有变化，引用它们的接口也会受影响，完整清单见 affected-operations.json。

对 `/opt/knowdo` 的只读检查发现，导入侧还需要正确处理组合 Schema、引用附加属性、readOnly 和非 JSON 媒体类型，并保留成功码的值类型。合法 OpenAPI 结构不应被扁平化时丢弃。此项交由知办任务处理；本次未修改其实现，也不宣称核对文件已被自动加载。完成同步后，应重新检查销售订单新增/修改、上传/导入、分页/日期、更新 ID、空查询与非零业务码；文件、流式和回调按人工清单单独配置。

未来部署后的业务地址：`http://192.168.1.199:8088/v3/api-docs/all`；原始地址：`http://192.168.1.199:8088/v3/api-docs`。这些地址当前仍返回 **v5**，本次未部署；v6 仅在隔离上下文中导出并验证，尚未验证运行服务导出的 v6 文档。线上 v5 文档只做了只读核查。

## 复现

需要 Java 17、Maven 和 Python。JSON Schema 验证依赖在 `requirements-integration.txt`，建议安装到独立开发虚拟环境；生成核对文件本身仅用 Python 标准库。

```bash
cd /opt/mgs/ruoyi-vue-pro
uv venv /tmp/mgs-openapi-checks
uv pip install --python /tmp/mgs-openapi-checks/bin/python -r script/openapi/requirements-integration.txt
OPENAPI_PYTHON=/tmp/mgs-openapi-checks/bin/python script/openapi/run_integration_checks.sh
```

该脚本只运行隔离测试、生成文档和校验文件，不执行 package/repackage，不覆盖当前运行容器挂载的 JAR，不启动线上服务或发布连接器。具体结果见 test-results.json。
