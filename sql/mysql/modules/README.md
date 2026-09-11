# 全模块数据库初始化

后端根 `pom.xml` 已启用全部 18 个业务模块组，`yudao-server/pom.xml` 已引入相应运行时依赖。商城的子模块和 IoT core/biz/gateway 均参与构建；IoT gateway 是独立进程，不打入主服务。

本目录根据当前实体源码补建 **453 张 MySQL 表、7,341 个字段**，与原始初始化脚本的 48 张表一起覆盖仓库的 **501 个 MyBatis-Plus 实体**。另外，`../third-party/jimureport-schema.sql` 补充 24 张积木报表/大屏依赖库的表，总共 525 张 MySQL 表。

这是用于开发初始化的重建结构，**没有菜单、字典、角色权限、流程模型、财务模板、支付配置或其他业务初始数据**；表建好不代表所有业务可以直接使用。官方初始化包还可能包含本仓库没有声明的约束和默认值。

## 文件清单

| SQL 文件 | 模块 | 表数 |
| --- | --- | ---: |
| `ai.sql` | AI 大模型 | 14 |
| `bpm.sql` | 工作流业务表 | 8 |
| `crm.sql` | 客户管理 | 21 |
| `erp.sql` | 进销存 | 33 |
| `fms.sql` | 财务管理 | 30 |
| `hrm.sql` | 人力资源 | 50 |
| `im.sql` | 即时通讯 | 17 |
| `iot.sql` | 物联网业务配置 | 15 |
| `mall.sql` | 商品、营销、交易、统计 | 49 |
| `member.sql` | 会员中心 | 11 |
| `mes.sql` | 制造执行 | 133 |
| `mp.sql` | 微信公众号 | 8 |
| `pay.sql` | 支付 | 14 |
| `pms.sql` | 项目和知识管理 | 33 |
| `report.sql` | GoView | 1 |
| `wms.sql` | 仓库管理 | 16 |

`manifest.json` 记录每张表的实体路径、H2 参考脚本、租户属性和字段清单。每条建表语句也注明了源码位置，方便后续上游同步时核对。

## 导入

以下命令从后端仓库根目录执行，数据库名使用项目默认的 `ruoyi-vue-pro`。目标数据库需预先创建并使用 utf8mb4。

**仅全新数据库**先导入原始基础脚本（它含 `DROP TABLE`，不要对已有业务数据库重新运行）：

```bash
mysql -u root -p --default-character-set=utf8mb4 ruoyi-vue-pro < sql/mysql/ruoyi-vue-pro.sql
```

随后导入所有扩展模块和积木依赖库：

```bash
mysql -u root -p --default-character-set=utf8mb4 ruoyi-vue-pro < sql/mysql/modules/all.sql
```

`all.sql` 使用 MySQL 客户端的 `SOURCE` 命令，路径相对客户端的工作目录。图形化数据库工具可逐个运行模块文件，再运行 `sql/mysql/third-party/jimureport-schema.sql`。

扩展脚本均采用 `CREATE TABLE IF NOT EXISTS`，没有删除、覆盖或插入数据操作。**已有同名表会直接跳过，不会自动添加缺失字段或升级旧结构**。请核对客户端输出中的错误；如果已有历史库，应先比较实际结构，再编写单独的迁移脚本。

## 重建规则和边界

- 使用 Java AST 解析 `@TableName`、`@TableId`、`@TableField` 及父类字段；排除 `exist=false`、`static`、`transient` 和嵌套 JSON 对象的内部字段。遇到未知持久化类型直接报错。
- 普通数值主键自增；`IdType.INPUT` 保留外部赋值语义。审计时间、逻辑删除字段带默认值。
- 按 `TenantDatabaseInterceptor` 的实际规则处理租户：没有 `@TenantIgnore` 的 `BaseDO` 实体也补 `tenant_id`，不只检查 `TenantBaseDO`。
- JSON 类型处理器映射到 MySQL `JSON`；`LongListTypeHandler` 等逗号分隔处理器映射到 `TEXT`，保持 `FIND_IN_SET` 查询语义。
- 优先采用同模块 H2 测试结构中明确的字符串长度（不超过 2,048）和 decimal 精度。没有长度信息及超大字符串使用 `LONGTEXT`，无精度信息的 `BigDecimal` 使用 `DECIMAL(24,6)`。这些是重建选择，不代表官方原始定义；业务上线前应按实际范围收紧长度并核对精度。
- 业务字段默认允许 NULL；可从 H2 确认且类型兼容的标量默认值会保留。不能仅凭 Java 类型推断业务 NOT NULL 约束。
- 保留测试脚本中明确的唯一索引和普通索引，包括 FMS 结账模板软删除生成列；另补租户和数值关联字段的非唯一查询索引。不会从字段名称猜测唯一约束或外键。没有 H2 证据的业务唯一性仍需单独核对。
- 积木依赖库的 24 张表摘自官方 SQL 的固定提交，来源和 SHA-256 在 `../third-party/provenance.json`。只保留 `jimu_*`、`onl_drag_*` 的正式表结构；去除演示、ChatBI、备份表、清表语句及数据。依赖版本为 JimuReport 2.5.1 / JimuBI 2.5.0；未完成报表业务功能验收。

## MySQL 以外的运行依赖

- **IoT：**`application.yaml` 已启用 TDengine 数据源，可通过 `TDENGINE_URL`、`TDENGINE_USERNAME`、`TDENGINE_PASSWORD` 覆盖。用 TDengine 客户端执行 `sql/tdengine/iot.sql`；它来自本仓库 Mapper 的设备消息建表语句，不能导入 MySQL。产品属性超级表和设备子表由代码动态建立。必须有可连接的 TDengine 服务，否则现有启动初始化器会退出主服务。
- **BPM：**Flowable 自己的引擎表由现有 `flowable.database-schema-update=true` 创建；`bpm.sql` 只包含芋道业务表。
- **Quartz：**需要持久化定时任务时使用原有 `sql/mysql/quartz.sql`，按现有调度器配置初始化。
- **其他：**MySQL、Redis，以及实际使用的 AI 模型/向量库、微信公众号、支付和 LiveKit 等服务需另行配置。本次只启用模块、重建结构，不调用这些外部服务。

## 重新生成和验证

需要 JDK 17、Maven、Python 3.10+；数据库验证还需 Docker。

```bash
python3 -m venv /tmp/yudao-schema-tools
/tmp/yudao-schema-tools/bin/pip install -r sql/tools/schema/requirements.txt
/tmp/yudao-schema-tools/bin/python sql/tools/schema/generate.py
/tmp/yudao-schema-tools/bin/python sql/tools/schema/generate.py --check
/tmp/yudao-schema-tools/bin/python -m unittest discover -s sql/tools/schema -p 'test_*.py'
mvn -B -ntp -DskipTests package
/tmp/yudao-schema-tools/bin/python sql/tools/schema/verify.py
```

`verify.py` 创建独立 MySQL 8.4 容器，无对外端口、无外网、数据保存在临时内存目录，并在结束时停止删除容器。检查首次/重复导入、全部字段清单、编译产物中实际 MyBatis 映射、长文本、JSON、逗号分隔数组、租户字段、非自增主键及软删除唯一索引。不会连接或修改现有业务数据库。可通过 `MYSQL_TEST_IMAGE` 指定其他测试镜像。

这里只验证数据库结构及模块打包；不等同于全模块启动、外部服务联调或业务验收。
