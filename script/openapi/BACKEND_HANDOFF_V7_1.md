# v7.1：盘点单、参数配置、短信渠道和仓库默认状态

本次核实截图中的四类问题，保留工作区已有的参数配置名称和仓库 defaultStatus 修正，补齐库存盘点单及短信渠道文案。此前“描述非空”检查不能证明业务语义正确，本轮增加实际导出内容断言。

| 接口 | 修正及核验依据 |
| --- | --- |
| POST /admin-api/erp/stock-check/create | 请求模型改为库存盘点单；id、checkTime、items、明细 id 改为盘点用词；参数错误提示同步修正。创建 id 仍非必填。 |
| PUT /admin-api/erp/stock-check/update | 使用相同正确盘点模型；保留 Update 校验组，更新 id 在实际导出中为必填。 |
| GET /admin-api/infra/config/page、GET /admin-api/infra/config/export-excel | name 为“参数配置名称，模糊匹配”；Mapper 实际匹配 ConfigDO.name。 |
| GET /admin-api/system/sms-channel/page | status 为短信渠道启用/停用状态：0 启用，1 停用；省略不按状态筛选。依据 SmsChannelDO 的 CommonStatusEnum 与 SmsChannelMapper。 |
| PUT /admin-api/erp/warehouse/update-default-status | 删除错误的 status 注解，使用 defaultStatus；实际 query 仅 id 和布尔 defaultStatus，二者必填。此前业务方法不接收 status，但方法级 @Parameters 额外生成了这个虚构必填字段。 |

盘点请求对象、响应对象、分页对象，以及短信和参数配置相邻代码已检索同类错误用词；不把真实的其它出库单或实际任务状态改成盘点/渠道状态。本轮未改变盘点数量计算、订单业务或权限。

回归使用 `script/openapi/run_integration_checks.sh`：真实隔离 Springdoc 导出验证上述接口的最终 Schema、创建/更新 id 必填性，以及仓库查询参数集合、布尔类型和必填性；继续执行全业务模块引用、签名绑定与既有订单校验回归。结果和候选文档见 `generated-v7.1`。

版本为 `1.0.0-full-contract-v7.1`。本次未部署、未操作知办、未创建真实业务数据。只读核验线上仍为 `1.0.0-full-contract-v6`，也仍包含上述错误，不只是知办草稿未同步。先部署本修复并核对运行文档版本，再同步知办的 parameters、requestBody、components/schemas、description，处理已有草稿合并冲突；尤其移除旧工具中虚构的 status 字段和必填项。不得把仍保留错误的生产 URL 当成已修复来源。

部署后文档地址沿用：

- 业务文档：`http://192.168.1.199:8088/v3/api-docs/all`
- 原始文档：`http://192.168.1.199:8088/v3/api-docs`

本轮没有验证截图所述的 AI 重试记录；“检查失败为 0”仍需在知办任务中核实。第三方协议与特殊响应的人工核验边界沿用 `BACKEND_HANDOFF_V7.md`。
