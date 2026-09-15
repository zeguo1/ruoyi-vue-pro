# 隔离 MySQL 持久化回归

本项使用实际 MySQL 8.4、原始增量迁移、TrialStore、TrialOrchestrator、事件记录和专属接口访问判断，补充 H2 无法证明的 SQL 方言、唯一约束、事务和行锁行为。它不连接已部署 MGS/知办，也不创建真实业务账号。

## 执行

在后端仓库根目录运行，要求本机 Docker 可用且已缓存 `mysql:8.4` 镜像：

```bash
MGS_TRIAL_TEST_MYSQL=true MAVEN_OPTS='-Xmx512m -XX:ActiveProcessorCount=2' \
mvn -q -pl yudao-module-crm -am test \
  -Dtest=TrialOrchestratorTest,TrialMySqlIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -DargLine='-Xmx384m -XX:ActiveProcessorCount=2'
```

测试只通过代码创建一个独有的临时容器，不接受外部 JDBC 地址、账号或数据库配置。容器限制 512 MiB 内存、禁用 swap、1 CPU，只映射随机 loopback 端口，使用临时随机密码；不挂载宿主目录或现有卷，不自动下载镜像。每个测试创建独立数据库，结束后清理该精确容器和匿名卷；正常 JVM 退出另有清理钩子。MySQL 初始化最多等待 90 秒；容器命令有单独超时。

如果整个 JVM 被 SIGKILL，退出钩子可能不运行，应先核对 `mgs.trial.isolated-test=true` 标签、测试创建的容器 ID 和随机名称，只清理确属本次测试的资源。不要对现有 mysql 容器或卷运行清理命令。

普通测试默认不启用此套件。命令只有 `test` 阶段，不会覆盖部署挂载的 JAR/dist。

## 已验证范围

2026-09-15，实际镜像运行版本 **8.4.11**：

- 原 H2 编排套件 11 项通过；MySQL 继承相同 11 个验收场景，另加原始迁移重复执行检查，共 12 项通过。
- MySQL 场景包括首次申请、必须确认后才开户、并发重复申请/续办、主体与幂等归属、查询远端失败不得创建、远端超时后先查结果、本地步骤失败时事务回滚、单侧撤权失败继续追踪、两用户申请映射和路由限制、未确认申请不阻塞队列、事件重放与事实校验。
- 四个增量迁移按原文件执行并重复应用；system_menu 仅从仓库基础 SQL 提取原始建表语句，不导入其业务数据。确认菜单仍只有原 4 条且编号和父子关系保留，已有 READY 申请及初始化登记不丢失，交付表 TIMESTAMP(6) 保留微秒。
- 测试使用实际 Spring JDBC/TransactionTemplate，并发数据库锁和回滚发生在 MySQL。测试中的本地账号/CRM/OAuth 效果记录及知办仍为显式 fixture；未执行真实账号和 CRM 服务的 MySQL 联合验证。

结果与源文件哈希见 [mysql-test-evidence.json](mysql-test-evidence.json)。Maven XML 位于 `yudao-module-crm/target/surefire-reports/`。镜像版本和 ID 由实际运行容器记录，不从 tag 推断。

## 仍未验证

本套件中的“恢复”是重新建立编排对象，不是 MySQL 断电或应用进程冷启动。已有独立 JVM 强制中断/冷启动恢复测试使用文件 H2，仍不能等同于 MySQL 环境故障演练。真实租户/用户/OAuth/CRM 服务的 MySQL 联调、运行部署、官网安全会话、知办安全卡片及公众号体验闭环仍未完成。
