# 全量业务 OpenAPI 修复与知办交接

> 本文保留 v4 历史记录。最新全业务后端契约修复及线上验证见 [BACKEND_HANDOFF_V5.md](BACKEND_HANDOFF_V5.md)，当前版本为 `1.0.0-full-contract-v5`。

核验时间：2026-09-14T10:43:06.723439+00:00。最终版本：`1.0.0-full-contract-v4`。代码已提交并推送到 `origin/master-jdk17`，提交 `5462876c28`（全量模型、生成与请求校验）和 `2ec0c4db7a`（业务接口引用的 SDK 模型）。

## 当前可用文档与部署

| 文档 | 地址 | 接口操作 | 模型 | 字段 |
|---|---|---:|---:|---:|
| 全量业务，覆盖全部 19 个业务模块 | [OpenAPI all](http://192.168.1.199:8088/v3/api-docs/all) | 3040 | 3193 | 17814 |
| 原始总文档，另含第三方组件内部接口 | [OpenAPI root](http://192.168.1.199:8088/v3/api-docs) | 3439 | 3317 | 18688 |
| ERP | [OpenAPI ERP](http://192.168.1.199:8088/v3/api-docs/erp) | 146 | 144 | 960 |

全量业务和全部模块分组均已从 **1.199 实际运行服务** 下载验证；20 份文档的接口摘要、字段说明均无缺失，未发现断开的引用、重复 operationId、类型错误的默认值或示例。字段说明包括业务接口中直接使用的微信、Flowable、验证码 SDK 对象。

- 容器：`mgs199-server-1`，运行且健康，重启次数 `0`；启动于 `2026-09-14T10:37:04.119329782Z`。
- 实际挂载运行 JAR 的 SHA-256：`55f42073265b2b585f51e7c65f747c73770e2bab04cdbdf764d691eec0628976`。
- 全量业务 JSON SHA-256：`f36abfc2a193647e116830cc069a9d2ce78ef4eafb7231fa5c0f51680eb95ebf`。
- 原始总 JSON SHA-256：`812d53d3a64a7f966371bb71f768e53583c93829a329bd5d447daead2be42316`。
- 原始总文档仍有 **394 个第三方内部接口缺摘要、810 个第三方字段缺说明**，明细见 `full-runtime-verification.json`。它们位于积木报表、数据大屏、EasyTrans 等组件范围；不能把这些缺口说成已经完成语义核实。积木报表当前发布的两个 sources.jar 均不含 Java 源文件，证据见 `full-sdk-source-evidence.json`。没有按方法名编造业务含义，也没有为使检查变绿而删除这些第三方接口。
- 此次未执行生产订单、库存、资金等业务写操作。**知办连接器未同步、未发布**；后端部署完成不代表知办已经使用新契约。

## 根因与修复

1. 同名嵌套模型由原有 `springdoc.use-fqn=true` 保持完整类名，继续验证 38 个不同控制器 Item；实际总文档中 37 个被接口引用，另 1 个没有暴露为组件。销售订单新增、修改仍指向订单自身 Item，商品、数量、单价完整，不含 PMS 排序字段。
2. 源码 JavaDoc 没有参与编译和 Springdoc 生成，导致已有源码说明没有导出。启用 therapi 注解处理器与运行时支持，增加数组类型处理；补齐通用响应、具体缺失字段和 EasyTrans 返回映射的说明。保留原有明确的 `@Schema` 描述优先级。
3. 通用生成器把注解的空默认值输出成实际默认值，并遗漏部分验证边界。统一清理无效默认值、类型不符或违反数值/枚举约束的示例；根据默认校验组生成数值界限及 `@InEnum` 枚举，正确序列化 OpenAPI 3.1 数值排他边界。没有替调用方编造编号、价格或数量。
4. 62 组共享新增/修改 DTO 把编号误标为新增必填。新增模型移除此要求；修改方法启用继承 Default 的 Update 校验组，在接口级 allOf 中要求 id。代码生成模板也不再把数据库主键的非空性直接等同于新增请求必填。另修复 CRM 仅新增的跟进记录编号文档；代码生成表/列定义确实是已有定义的修改，补齐原本缺失的编号校验。
5. 核实并修复 ReqVO 扫描发现的 24 处嵌套校验候选（含继承属性重复报告）；补充实际对象字段上的 @Valid 和清单元素非空。五类 ERP 采购/出入库/退货单明确清单非空；单位从商品资料取得，调用方传入值忽略。优惠比例未填写按既有业务规则取 0。其余五类单据仍保留既有“单价可选、未填本行不计价”的规则，不能与销售订单的严格成交单价要求混淆。
6. 购物车结算允许 cartId 或 skuId + count 两种既有方式，移除与该规则冲突的 skuId 无条件非空要求；空清单不再让跨字段校验方法空指针。积分活动明细的 activityId、spuId、activityStatus 来自所属活动，调整为后端字段，再启用嵌套校验。
7. 隐藏 21 个跨字段校验辅助 getter；修复公众号分页把中文说明写成参数名称的问题。修正库存盘点被称为库存调拨、积分记录被归入签到、拼团记录/分销记录分类错误，以及 IoT 物模型属性/服务/事件被同时标为必填的问题。
8. 对照当前版本依赖源码补齐 19 个 SDK 模型，移除 Flowable 的 values 复制辅助 setter 和验证码响应的 repCodeEnum 辅助 setter，共 4 个非业务属性；使用真实 Jackson 序列化回归确认它们没有对应响应 getter。
9. 隐藏 DefaultController 的内部兜底路由，避免当作已实现业务功能导入；运行时路由行为不因此改变。真正的文件下载通配路径保留。

## 受影响接口与可复查结果

- `full-affected-operations.json`：138 个与调整后请求模型关联的操作、3019 个文档或引用模型发生变化的操作；还列出从文档移除的兜底操作。共同响应字段说明补齐会影响大量接口文档，这是预期的文档变化，不代表所有接口业务逻辑都变更。
- `full-update-contracts.json`：62 组新增/修改 DTO 与控制器对应关系。
- `full-nested-contracts.json`：24 处候选的所属类、字段及实际 Java 类型。
- `full-source-fixes.json`：被隐藏的校验辅助 getter。
- `full-group-verification.json`：每份实际分组文档的版本、接口数、模型数、字段数和 SHA-256。
- `full-business-verification.json`、`full-runtime-verification.json`、`full-item-verification.json`：业务全量、原始总文档及销售订单/Item 的核验结果。
- `full-deployment-verification.json`：测试、部署与运行包校验值。

## 隔离回归与边界

共 **35 个测试**，0 失败、0 错误、0 跳过，包括：

| 测试 | 数量 | 核验内容 |
|---|---:|---|
| ErpSaleOrderContractTest | 24 | MockMvc + 真实 Service/MyBatis + 独立 H2；错误明细拒绝、合法新增修改、失败不留订单/明细、错误修改不改变原数据 |
| OpenApiItemSchemaTest | 2 | 38 个 Item 独立命名与字段；订单新增/修改及数值边界 |
| FullOpenApiModelAuditTest | 1 | 2,128 个控制器模型清点；ReqVO 嵌套校验候选检查 |
| FullOpenApiExportTest | 1 | 仅装载 588 个控制器元数据的隔离文档上下文，无数据库、Redis 或业务服务；说明完整性和新增编号约定 |
| FullContractValidationTest | 4 | 62 组修改编号规则；五类 ERP 明细；购物车替代路径；积分活动后端字段 |
| ContractSchemaCustomizerTest | 2 | 校验边界交集、校验组、枚举及错误默认值/示例清理 |
| ExternalOpenApiContractTest | 1 | 19 个 SDK 模型说明与辅助属性的真实序列化检查 |

隔离导出有 455 处嵌套引用可与实际 Java 字段类型直接比较，实际服务导出有 450 处，全部一致。两者数量不同，因为隔离扫描还包含运行条件下未注册的控制器。实际全量原始文档还有独立的 37 个 Item 组件逐字段比对。

自动检查证明的是上述契约、结构与回归范围；没有逐一执行 3,040 个接口的全部业务分支，也没有在生产通过创建订单试错。后端完整打包通过。SDK 补充只改文档，主业务回归结果与 SDK 专门回归分别保存并汇总。

复现测试（在后端仓库根目录）：

```bash
MAVEN_OPTS='-Xmx640m -XX:ActiveProcessorCount=2' mvn -pl yudao-server -am test \
  -Dtest=ErpSaleOrderContractTest,OpenApiItemSchemaTest,FullOpenApiModelAuditTest,FullOpenApiExportTest,FullContractValidationTest,ContractSchemaCustomizerTest,ExternalOpenApiContractTest \
  -Dsurefire.failIfNoSpecifiedTests=false -DargLine='-Xmx512m -XX:ActiveProcessorCount=2'
python3 script/openapi/audit_full_contract.py downloaded-openapi.json \
  --inventory yudao-server/target/full-model-inventory.json --output audit.json --strict
```

运行环境以文件挂载 JAR。后续打包前需先重命名运行中的旧 JAR，避免覆盖其 inode；本次回退包保存在 `/opt/mgs/backups/full-contract/`。

## 知办同步交接

建议此次全量业务同步读取 `http://192.168.1.199:8088/v3/api-docs/all`，它包含全部已启用业务模块；原始 `/v3/api-docs` 额外包含上述第三方内部接口。两者均通过匿名 GET 成功读取，文档获取认证与业务 OAuth 凭据独立。

知办任务需要重新获取文档、检查差异、应用草稿并发布连接器版本，再自行验证工具定义和业务授权。应确认文档版本为 `1.0.0-full-contract-v4`，记录同步时实际 SHA-256。这里没有代替知办完成这些动作。

脱敏销售订单样例（全部编号和值来自隔离测试夹具，不是生产资料）：

```json
{
  "customerId": 201,
  "orderTime": "2026-09-14T10:00:00",
  "items": [{"productId": 101, "count": 2.5, "productPrice": 100, "taxPercent": 13}]
}
```

销售订单修改还必须提供已有订单 id；单位由商品资料取得。编号、数量和成交单价必须来自查询及用户确认，缺少信息时补问，不能为通过校验而杜撰。

仅含 id、sort 的订单明细会在业务处理前返回现有 CommonResult 参数错误，例如：

```json
{"code":400,"msg":"请求参数不正确:items[0].productPrice: 产品单价不能为空"}
```

多个字段同时缺失时，首个错误字段可能不同。项目沿用 HTTP 200 承载业务错误码的既有约定，调用方必须检查 code。

## 元数据依据

- 源码说明集成遵循 [springdoc 文档](https://springdoc.org/v2/) 的 JavaDoc 支持方式。
- SDK 描述按当前 Maven 依赖源码核对：WxJava `4.8.6-20260825.155844`、Flowable BPMN Model `8.0.0`、Anji Captcha `1.4.0`；下载包校验值与是否包含 Java 源码记录在 `full-sdk-source-evidence.json`。
- 已有 ERP 故障、日志及初次隔离验证见 `ERP_ORDER_HANDOFF.md`；本文是后续全量修复和最终运行状态记录。
