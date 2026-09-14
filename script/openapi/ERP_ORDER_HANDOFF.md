# ERP 销售订单契约修复与知办同步交接

> 后续全量修复及最终部署版本见 [FULL_OPENAPI_HANDOFF.md](FULL_OPENAPI_HANDOFF.md)。本文保留首次 ERP 专项修复的历史证据。

修复代码提交：`21cacef401`（模型命名、请求校验与隔离测试）、`2cce380008`（实际 OpenAPI 3.1 数值排他边界），分支 `master-jdk17`。本文只交接后端结果；知办连接器未同步、未发布，也未重放真实业务订单。

## 根因与证据

- 2026-09-14 15:31:00（服务日志 Asia/Shanghai），`POST /admin-api/erp/sale-order/create` 收到 11 条仅含 `id、sort` 的明细；随后在 `ErpSaleOrderServiceImpl.validateSaleOrderItems` 读取 `productMap.get(...).getUnitId()` 时空指针。未在参数校验阶段拒绝。
- 修复前实际全量 `/v3/api-docs` 中，`ErpSaleOrderSaveReqVO.items.items.$ref` 为 `#/components/schemas/Item`；该模型描述为“PMS 项目分组排序项”，仅有 `id、sort`，并且两者均必填。
- 默认简单类名把不同外部类、不同模块的嵌套 `Item` 合并到了同一组件名；最终结构受生成/访问顺序影响。此次全量导出中 PMS 项目分组排序项占用了该名称。
- 请求顶层已有 `@Valid`，但 `items` 缺少 `@Valid`、`@NotEmpty` 及非空元素约束，导致 Item 中已有的商品、数量注解未执行。
- `productUnitId` 原文档标记必填，但业务代码始终从商品资料覆盖；单价原来没有服务端必填和正数约束。前端销售订单表单已要求数量和单价大于零。
- 这是接口契约与校验问题，日志不是角色权限拒绝。

## 修复范围

1. 启用 `springdoc.use-fqn: true`，用含外部类的完整类名生成 Schema 标识。采用 [springdoc 官方配置](https://springdoc.org/v2/)，不逐个重构业务模型。所有默认命名的 Schema 引用会变化，HTTP 路径和 JSON 字段名不因此改变。
2. 销售订单清单至少一条、元素非空并级联验证；商品编号、数量和成交单价必填且大于零；税率和优惠率限制为 0～100，定金不得小于零。
3. 商品仍批量查询，再按明细索引检查不存在、停用及未配置单位；在生成单号和写库前返回明确业务错误。没有通过捕获空指针来掩盖问题。
4. `productUnitId` 在请求中只读、非必填，传入值忽略，由商品资料确定。
5. 新增订单和明细编号由后端生成，忽略新增时传入的编号；修改使用独立 `ErpSaleOrderUpdateReqVO`，要求订单 `id`。
6. 参数错误继续使用现有 `CommonResult` 契约，并增加字段路径。返回首个失败字段，明细索引从 0 开始；不是一次返回所有错误。
7. 运行导出验证发现 swagger-core 将 `@DecimalMin(inclusive=false)` 的边界按 OpenAPI 3.0 布尔形式存储，3.1 序列化后丢失排他性。添加仅针对销售订单数量、单价的文档定制器，补充 3.1 数字形式 `exclusiveMinimum: 0`，并验证实际 JSON；整数商品/客户/订单编号明确标注 `minimum: 1`。
8. 其他模块只修复已证实的模型命名冲突，没有扩展修改它们的业务校验。

## 调用字段约定

| 字段 | 来源及规则 |
|---|---|
| `customerId` | 调用方查询并确认真实客户编号，必填且大于零；后端校验客户。 |
| `orderTime` | 调用方提交真实下单时间，必填；例如 `2026-09-14T10:00:00`。 |
| `items` | 调用方提交至少一条非空明细。 |
| `items[].productId` | 真实商品编号，必填且大于零；商品须存在、启用并配置单位。 |
| `items[].count` | 真实交易数量，必填且大于零，可为小数，计量单位须与商品资料一致。 |
| `items[].productPrice` | 已确认的成交单价（元/商品单位），必填且大于零；后端不自动用零或商品参考售价补齐。 |
| `items[].productUnitId` | 无需提交。后端读取商品 `unitId`；请求传入值忽略。 |
| `items[].taxPercent` | 可选，0～100；省略不计税。 |
| `discountPercent` | 可选，0～100；省略按 0 计算，表示优惠比例而非“支付折扣”。 |
| `depositPrice` | 可选，非负，单位元。 |
| `saleUserId / accountId` | 可选；提供时校验真实销售人员/结算账户。 |
| `id / items[].id` | 新增由后端生成；修改必须提交订单编号，已有明细修改提交其原编号，新明细省略明细编号。 |

知办不得编造商品/客户编号、数量或单价来满足校验；缺少真实交易信息时应向用户补问。若按立方米计价，必须先确认商品单位为相应单位，不能仅凭单价推导数量。

## 隔离回归

- `ErpSaleOrderContractTest`：24 个测试，0 失败、0 错误。17 类参数错误分别走新增、修改接口；另测不存在/停用/未配置单位商品、合法新增和修改、错误修改保持原记录、可选字段省略、修改编号必填及服务代理嵌套校验。
- 使用 MockMvc + 真实事务 Service + 真实 MyBatis Mapper + 独立 H2 内存库 `erp_sale_order_contract`。商品、客户、账户、用户服务和 Redis 单号生成被替换为测试依赖，不连接生产数据库或 Redis。
- 每个失败新增检查订单表、明细表的物理行数均为 0；非法修改检查原订单金额和原明细不变。合法请求真实写入 H2 并核对商品单位、单价、数量、税费、优惠后的金额。
- `OpenApiItemSchemaTest`：2 个测试，0 失败、0 错误；扫描全部启用模块中的 38 个控制器 `Item`，验证独立名称和声明字段，并专门检查销售订单新增/修改与 PMS 排序结构互不覆盖，以及序列化后的排他边界。
- Maven 完整后端打包成功。测试边界：没有在生产创建测试订单，也没有代替知办执行连接器端到端调用。

复现构建与测试：

```bash
MAVEN_OPTS='-Xmx640m -XX:ActiveProcessorCount=2' mvn -pl yudao-server -am package \
  -Dtest=ErpSaleOrderContractTest,OpenApiItemSchemaTest \
  -Dsurefire.failIfNoSpecifiedTests=false -DargLine='-Xmx384m -XX:ActiveProcessorCount=2'
```

## 脱敏请求与错误响应

以下编号和交易值来自隔离测试夹具，**不是生产资料，不可直接执行**。真实调用必须替换为已查询和确认的信息。

```json
{
  "customerId": 201,
  "orderTime": "2026-09-14T10:00:00",
  "items": [
    {
      "productId": 101,
      "count": 2.5,
      "productPrice": 100,
      "taxPercent": 13
    }
  ]
}
```

仅含 `id、sort` 的明细，在隔离测试中的实际响应如下（多个字段缺失时，首个报错字段顺序可能不同）：

```json
{
  "code" : 400,
  "msg" : "请求参数不正确:items[0].productPrice: 产品单价不能为空"
}
```

当前项目沿用 HTTP 200 + `CommonResult.code=400` 的参数错误返回方式，不能只检查 HTTP 状态；商品业务错误码为 `1020201011`（不存在/停用）和 `1020201012`（未配置单位），消息包含 `items[index].字段`。

## 运行文档与部署验证

最终验证时间：`2026-09-14T09:25:09.264968+00:00`。后端 `mgs199-server-1` 健康、最终容器重启计数为 0；生产运行包包含两笔修复提交。对生产仅进行了健康与文档读取，没有创建回归订单。

- 全量文档：[http://192.168.1.199:8088/v3/api-docs](http://192.168.1.199:8088/v3/api-docs)
- ERP 分组：[http://192.168.1.199:8088/v3/api-docs/erp](http://192.168.1.199:8088/v3/api-docs/erp)
- `info.version`：`1.0.0-erp-order-contract-v2`
- 全量快照 SHA-256：`7fcccdace0550b09084ed9565e2b92edf22f56873ae4e3d50f68c747a07838ef`
- ERP 快照 SHA-256：`4c12dd4350c9f8f9b3e61c86932c74253d878c9aacbaafe96bf95d0615c7c1b3`
- 运行 JAR SHA-256：`441f472e2bf83d84768f47b9ed66cb3da4d71c17b7ed0343ea146a97a0b41fc5`
- 两个销售订单写接口展开后的明细均引用 `cn.iocoder.yudao.module.erp.controller.admin.sale.vo.order.ErpSaleOrderSaveReqVO.Item`，字段完整；`productId、count、productPrice` 必填，`productUnitId` 只读且非必填，数量和单价的 `exclusiveMinimum` 为数值 0。
- 所有文档内部引用可解析，旧的共享 `#/components/schemas/Item` 引用为 0。
- 38 个扫描到的控制器 Item 中，37 个实际出现在运行文档中，全部逐一核对字段集合一致。`MesWmStockTakingTaskLineBatchUpdateReqVO.Item` 未作为组件暴露，报告单独列出，未据此假定存在接口故障。
- [机器可读验证摘要、全部接口清单](erp-order-verification.json)。该 SHA 是本次实际导出快照的指纹，不表示未来文档更新后哈希仍不变。

读取验证（不会调用业务写接口）：

```bash
python3 script/openapi/verify_item_contract.py \
  --document http://192.168.1.199:8088/v3/api-docs \
  --models yudao-server/target/item-model-contracts.json
```

知办后续需重新获取上述文档、检查差异并同步/发布连接器版本；重点确认订单明细不再只有 `id、sort`，且不要发送只读 `productUnitId`。本任务未执行知办同步、发布或真实订单重试，不能视为两端已生效。

## 修复前共享 Item 引用的接口清单

以下 64 个 HTTP 操作通过请求或响应间接/直接引用同一 `Item`。PMS 项目分组排序本身的 `id、sort` 结构正确，是当时覆盖其他模型的来源；清单表示共享引用范围，并非 64 个接口的业务代码都发生了故障。

| 方法 | 接口 | 功能 |
|---|---|---|
| PUT | `/admin-api/pms/pm/project-group/update-sort` | 修改项目分组排序 |
| PUT | `/admin-api/pms/kb/group/update-sort` | 修改知识库分组排序 |
| PUT | `/admin-api/mes/qc/indicator-result/update` | 更新检验结果 |
| PUT | `/admin-api/fms/report/cash-flow-statement/update` | 更新现金流量表 |
| PUT | `/admin-api/fms/report/cash-flow-statement/adjustment/update` | 更新现金流量辅助数据 |
| PUT | `/admin-api/erp/stock-out/update` | 更新其它出库单 |
| PUT | `/admin-api/erp/stock-move/update` | 更新库存调拨单 |
| PUT | `/admin-api/erp/stock-in/update` | 更新其它入库单 |
| PUT | `/admin-api/erp/stock-check/update` | 更新库存调拨单 |
| PUT | `/admin-api/erp/sale-return/update` | 更新销售退货 |
| PUT | `/admin-api/erp/sale-out/update` | 更新销售出库 |
| PUT | `/admin-api/erp/sale-order/update` | 更新销售订单 |
| PUT | `/admin-api/erp/purchase-return/update` | 更新采购退货 |
| PUT | `/admin-api/erp/purchase-order/update` | 更新采购订单 |
| PUT | `/admin-api/erp/purchase-in/update` | 更新采购入库 |
| PUT | `/admin-api/erp/finance-receipt/update` | 更新收款单 |
| PUT | `/admin-api/erp/finance-payment/update` | 更新付款单 |
| POST | `/app-api/trade/order/create` | 创建订单 |
| POST | `/admin-api/mes/qc/indicator-result/create` | 创建检验结果 |
| POST | `/admin-api/erp/stock-out/create` | 创建其它出库单 |
| POST | `/admin-api/erp/stock-move/create` | 创建库存调拨单 |
| POST | `/admin-api/erp/stock-in/create` | 创建其它入库单 |
| POST | `/admin-api/erp/stock-check/create` | 创建库存调拨单 |
| POST | `/admin-api/erp/sale-return/create` | 创建销售退货 |
| POST | `/admin-api/erp/sale-out/create` | 创建销售出库 |
| POST | `/admin-api/erp/sale-order/create` | 创建销售订单 |
| POST | `/admin-api/erp/purchase-return/create` | 创建采购退货 |
| POST | `/admin-api/erp/purchase-order/create` | 创建采购订单 |
| POST | `/admin-api/erp/purchase-in/create` | 创建采购入库 |
| POST | `/admin-api/erp/finance-receipt/create` | 创建收款单 |
| POST | `/admin-api/erp/finance-payment/create` | 创建付款单 |
| GET | `/app-api/trade/order/settlement` | 获得订单结算信息 |
| GET | `/admin-api/trade/order/page` | 获得交易订单分页 |
| GET | `/admin-api/trade/order/get-detail` | 获得交易订单详情 |
| GET | `/admin-api/trade/order/get-by-pick-up-verify-code` | 查询核销码对应的订单 |
| GET | `/admin-api/pms/pm/work-item-work-log/project-report` | 获得项目工时报表 |
| GET | `/admin-api/pms/kb/recycle/content-detail` | 获得知识库最近删除详情 |
| GET | `/admin-api/mes/qc/indicator-result/page` | 获得检验结果分页 |
| GET | `/admin-api/mes/qc/indicator-result/get-detail` | 获得检验结果明细（含检测项模板） |
| GET | `/admin-api/im/face-pack/list` | 获得启用的表情包列表（含表情） |
| GET | `/admin-api/erp/stock-out/page` | 获得其它出库单分页 |
| GET | `/admin-api/erp/stock-out/get` | 获得其它出库单 |
| GET | `/admin-api/erp/stock-move/page` | 获得库存调拨单分页 |
| GET | `/admin-api/erp/stock-move/get` | 获得库存调拨单 |
| GET | `/admin-api/erp/stock-in/page` | 获得其它入库单分页 |
| GET | `/admin-api/erp/stock-in/get` | 获得其它入库单 |
| GET | `/admin-api/erp/stock-check/page` | 获得库存调拨单分页 |
| GET | `/admin-api/erp/stock-check/get` | 获得库存调拨单 |
| GET | `/admin-api/erp/sale-return/page` | 获得销售退货分页 |
| GET | `/admin-api/erp/sale-return/get` | 获得销售退货 |
| GET | `/admin-api/erp/sale-out/page` | 获得销售出库分页 |
| GET | `/admin-api/erp/sale-out/get` | 获得销售出库 |
| GET | `/admin-api/erp/sale-order/page` | 获得销售订单分页 |
| GET | `/admin-api/erp/sale-order/get` | 获得销售订单 |
| GET | `/admin-api/erp/purchase-return/page` | 获得采购退货分页 |
| GET | `/admin-api/erp/purchase-return/get` | 获得采购退货 |
| GET | `/admin-api/erp/purchase-order/page` | 获得采购订单分页 |
| GET | `/admin-api/erp/purchase-order/get` | 获得采购订单 |
| GET | `/admin-api/erp/purchase-in/page` | 获得采购入库分页 |
| GET | `/admin-api/erp/purchase-in/get` | 获得采购入库 |
| GET | `/admin-api/erp/finance-receipt/page` | 获得收款单分页 |
| GET | `/admin-api/erp/finance-receipt/get` | 获得收款单 |
| GET | `/admin-api/erp/finance-payment/page` | 获得付款单分页 |
| GET | `/admin-api/erp/finance-payment/get` | 获得付款单 |

## 部署依赖恢复记录

本次重启暴露出独立的 TDengine 内存预留问题：原默认值为系统物理内存的 20%（日志中的 reserveSize=3344957440 字节），当时系统可用内存约 3.0 GiB，连 `SHOW STABLES LIKE 'device_message'` 都返回 `0x73a Query memory exhausted`，后端初始化因此退出。修复包本身的 ERP 回归测试不依赖 TDengine。

已在此部署的 `taos.cfg` 中设置 `minReservedMemorySize 1024`，并核验运行 dnode 实际值为 1024 MB。TDengine 3.3.6.13 的动态调整在当前内存池状态下返回错误；现有数据库的持久化 `dnode/config/local.json` 又覆盖文本配置，因此停止 TDengine 后备份该文件，只修改这一配置值，再启动并检查实际值及表结构查询。没有修改业务数据，没有关闭查询内存池或跳过应用初始化。其他应用服务未调整。

部署目录的 `.cfg` 对新数据库生效；已有数据库应同时检查持久化配置，不能只修改文本文件就认为生效。原包及配置备份保留在 `/opt/mgs/backups/erp-order-contract/`。该参数含义参见 [对应 TDengine 3.3.6.13 官方文档](https://github.com/taosdata/TDengine/blob/ver-3.3.6.13/docs/en/14-reference/01-components/01-taosd.md#query-related)。

后端容器重建后已重新加载 nginx 以更新其解析的后端容器地址。
