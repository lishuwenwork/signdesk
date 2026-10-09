# SignDesk 开发与维护约定

## 项目状态与文档入口

截至 2026-10-09，后端分层、持久队列、全新 SQLite 基线、明文配置备份及前端适配已在主工作区完成；本地构建验证通过。浏览器 E2E 尚未实际运行，因此 P6 尚有验收阻塞。代码集成在 `refactor/backend-ruoyi-style` 主工作区，HEAD `475c46a` 是本轮实现前的基线，当前实现未提交；是否提交或推送由用户决定。详细证据与限制见 [验证记录](docs/validation.md)。

- 用户需求、验收状态：[docs/requirements.md](docs/requirements.md)。
- 当前后端实现结构与数据模型：[docs/architecture.md](docs/architecture.md)。
- 阶段清单和交接：[docs/refactoring-plan.md](docs/refactoring-plan.md)。
- 历史及本轮验证：[docs/validation.md](docs/validation.md)。

文档中的未验证项目必须继续标为待办；`--list` 收集不等于浏览器测试通过。不要把旧版本历史结果冒充当前实现的验证。

## 技术栈与分层

保留 Java 21、Spring Boot 4 / Spring MVC、SQLite、MyBatis-Plus、Hutool HTTP、Vue 3 / JavaScript。同仓库前后端，正式发布为单 JAR；SQLite 位于 JAR 外的本地数据目录。

当前实现采用 9 个 Controller、10 组 `IXxxService` / `XxxServiceImpl`（platform、account、request、requestTemplate、schedule、run、runRecord、settings、backup、system），Entity 私有字段与 Lombok，BO/VO 分离。普通 CRUD 使用 MP 原生 `BaseMapper`、Lambda Wrapper 和乐观锁；复杂 SQLite 操作放入专用 Mapper XML。具体职责和生产 XML 清单见架构文档。

持续遵守以下边界：

- `controller` 处理参数、校验、调用 Service 和返回 VO；不直接查库、不编排队列。
- `domain` / `domain.bo` / `domain.vo` 分别承载实体、输入和输出；不直接返回敏感实体或大量数据库行 Map。
- 业务接口放 `service/IXxxService`，实现放 `service/impl/XxxServiceImpl`，使用构造器注入。
- 普通 CRUD 使用原生 MyBatis-Plus；复合关联、SQLite UPSERT、队列 CAS 等复杂 SQL 放明确命名的 Mapper/XML。Service 不拼 SQL，不恢复万能 `Db` 工具。
- `engine`、`scheduler`、`storage` 保留必要的 HTTP/解析、调度派发、数据库初始化和实例锁职责；纯工具和值对象不机械地增加接口层。
- 保留明确业务方法名、有效校验与必要注释；不为减少行数删除安全/事务约束，不引入若依私有扩展或未使用的框架层。

参考本地 `D:/Project/RuoYi-Vue-Plus` 的轻量分层即可；不得修改参考仓库，也不得引入其权限、Redis、租户、公共模块或微服务架构。若依的 `BaseMapperPlus` 等是自有扩展，不是 MyBatis-Plus 原生能力。

## 明文数据、数据库与备份

请求、执行响应和配置导出按明文处理。完整原始请求仅在明确的请求详情返回；响应列表不含正文，执行详情 `no-store`，HTML 只作为纯文本显示，关闭详情后清除页面内容。日志不得记录原始请求、响应正文、SQL 参数、备份正文或凭证，敏感 BO/VO/Entity 不得因自动 `toString` 泄露数据。

数据库采用 `signdesk-plain-v1` / schema version 1 的全新 `db/V1.sql`。对已有但不兼容或结构不完整的数据库只读检查后拒绝启动；不迁移、不清空、不重建、不删除用户文件。验证和开发使用新的数据目录。代码变更不得自动删除任何现有数据库、配置、日志或其他用户数据。

新备份使用 `signdesk-plain-v1`，没有 `formatVersion`。完整模式可包含已配置的 cURL 和日标记，是敏感明文文件；普通模式不含 cURL 和日标记，但用户手工填入的备注、名称或规则仍可能包含敏感内容。导入必须先预览、再明确确认整体替换；校验、维护互斥和事务回滚必须保留，导入后定时暂停且不得触发请求。

## 必须保留的业务与安全不变量

- 不执行用户粘贴的 cURL shell，也不读取 cURL 引用的本地文件；保留原始 URL 与 Body 字节，不能等价支持的语义明确拒绝或提示。
- 不宣称 Hutool 完全复现浏览器指纹或支持所有 cURL 选项；README 和需求文档应说明实际支持边界。
- 每次执行使用独立 HTTP 对象和 Cookie，不使用全局 Cookie；保留 TLS 证书与主机名校验、网络目标策略和同源写保护。
- 先持久化 `queued` / `running` 再发送；HTTP 超时或中断为 `unknown`，不自动重试；崩溃不得重放 `running` 请求。有限数据库结果写入重试不能重发 HTTP。
- 自动发生去重使用平台＋UTC 计划时刻，与计划修订无关；手动幂等键与平台组合去重。计划扫描 generation 只用于拒绝过期扫描，绝不能加入 occurrence 键。
- 日完成/待确认标记及自动发生去重账本独立于执行日志清理；删掉响应或历史执行项不能让请求重新发送。
- 同平台串行、跨平台有界并发；网络 I/O 和间隔等待不占数据库事务。批次创建时冻结请求修订、规则和计划参数；全局超时/代理在发送开始时读取，不能影响正在执行的请求。
- 维护/导入开始要失效尚未完成的旧计划扫描，并在短事务内复核暂停、当前计划、生效时间和 due；恢复相同 revision/effectiveFrom 也不能让旧扫描入队。
- 旧修订返回过期不得暂停新凭证；主动重跑不得绕过启停和凭证暂停。
- 响应上限 1 MiB，区分部分、截断、空、未获取和未保存；列表无正文，详情 `no-store`，HTML 纯文本，关闭详情清理。
- 导入先校验预览，再明确替换确认；队列空闲并持有维护互斥，短事务一致替换，失败回滚，导入后定时暂停。
- 不提交真实 cURL、`.env`、数据库、日志或生成文件；运行测试使用隔离临时数据目录与允许的本地 HTTP fixture，不使用真实平台凭证。

## 构建与验证

要求 Java 21、Maven 3.9+、Node 22.12+（建议 Node 24）。

- 后端：`mvn -f backend/pom.xml test`
- 前端：`cd frontend && npm ci && npm run test:unit && npm run build`
- 完整发布：仓库根目录 `node scripts/build.mjs`，包含本轮前端资源、后端测试和单 JAR 打包。
- 浏览器：安装 Chromium 或配置 `SIGNDESK_TEST_BROWSER`，在 `frontend` 运行 `npm run test:e2e`；只使用 18080 隔离后端、18081 本地 fixture 和临时数据目录。

调度边界使用注入的 Clock；涉及持久化时测试真实 SQLite。未运行、工具拒绝、失败或仅收集测试清单均需如实报告，不修改权限配置来绕过限制。当前唯一明确的重构验收阻塞为浏览器 E2E 实际尚未运行；Docker 实际构建/运行、线上 CI、真实平台和 24 小时观测也未验证。以 [验证记录](docs/validation.md) 中逐项事实为准。
