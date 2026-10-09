# SignDesk 后端重构计划与阶段交接

日期：2026-10-09。分支：`refactor/backend-ruoyi-style`。实现前基线 HEAD：`475c46a feat：日志记录响应体`。

> **当前结果：P1～P5 已完成；P6 的后端/前端自动化和独立全量发布通过，但浏览器 E2E 未运行，P6 仍被阻塞；P7 文档收尾已完成。** 新实现已经集成到主工作区，但代码尚未提交/推送。是否提交或推送由用户决定。
>
> 本计划以已实现的代码和本轮真实验证为准，不再把目标误写为待开发项。后端最终 110/110、前端单元 8/8；浏览器 E2E 实际 0 项。细节与未验证边界见 [验证记录](validation.md)。

## 1. 已确认范围与安全约束

- 保持 Java 21、Spring Boot 4 / Spring MVC、SQLite、MyBatis-Plus、Hutool HTTP、Vue 3 / JavaScript；同仓库前后端，生产单 JAR。
- 采用轻量 controller/domain/bo/vo/mapper/service/service.impl 分层，普通 CRUD 使用 MP 原生 BaseMapper/Lambda Wrapper，复杂 SQLite SQL 使用专用 XML。
- 请求、响应和导入导出均为明文；数据库从新的 `signdesk-plain-v1` V1 开始。不实现旧库/旧备份兼容，不自动清库、迁移、替换或删除任何既有用户文件。
- 不改非加密业务 HTTP 路径和 JSON 形状；不引入若依扩展、权限、Redis、租户或微服务。
- 保留 TLS、独立 Cookie、网络白名单、cURL 安全拒绝、日志安全、队列不可重放和自动去重等既有不变量。完整备份为明文敏感文件。

## 2. 阶段完成情况

### P1：基线、分层准备与约束验证 — 已完成

- [x] 记录实现前真实后端 66 项、前端单元 3 项基线通过。
- [x] 建立分层与类型约束测试，保留 Boot 4 / Jackson 3 / MP 原生 API 选择。
- [x] P1 后端 73 项全通过；主工作区 P1 版本也实际验证 73 项通过。
- [x] 初始静态反模式和质量审阅通过。

### P2：普通业务 MP 分层 — 已完成

- [x] 9 个 Controller；10 组 `IXxxService` / `XxxServiceImpl`：platform、account、request、requestTemplate、schedule、run、runRecord、settings、backup、system。
- [x] Entity 字段私有并使用 Lombok；BO/VO 输入输出分离；普通 CRUD 使用原生 `BaseMapper`、Lambda Wrapper 和乐观锁。
- [x] 特殊查询集中在 6 个生产 Mapper XML：`BackupMapper.xml`、`QueueMapper.xml`、`RequestRevisionMapper.xml`、`RunRecordMapper.xml`、`ScheduleQueryMapper.xml`、`SystemMapper.xml`。
- [x] 平台树以 3 次批量查询组装，无逐节点 N+1；Controller 不直接查库，Service 不拼 SQL。

### P3：队列、调度和执行记录拆分 — 已完成

- [x] 由 `RunCoordinator` 管维护状态及平台名额，`RunDispatcher` 使用有界线程，`PlatformWorker` 执行 HTTP/间隔，`ScheduleScanner` 扫描计划及清理。
- [x] 先持久化 queued/running，再进行网络调用；网络 I/O 和间隔不持数据库事务。只允许有限数据库结果落库重试，不重发 HTTP。
- [x] 批次创建时仅冻结请求修订、规则和计划参数；全局定时暂停或计划停用不会取消/中断已创建批次，但发送前仍检查平台、账号、请求启停及 `authPaused` 等门禁，因此后续启停可能使尚未发送的队列项跳过。
- [x] 保留同平台串行、跨平台有界并发、手动幂等键及平台＋UTC occurrence 去重；扫描 generation 不进入 occurrence 键。
- [x] 修复并回归验证自动入队扫描竞态：扫描在读取计划前捕获 generation；维护开始使旧扫描失效；自动协调锁与短事务重查 paused、当前 plan、`effectiveFrom` 和 due。5 个确定性回归覆盖导入后即使恢复相同 revision/`effectiveFrom` 也拒绝旧 scan。
- [x] running 崩溃不重放，旧修订过期不暂停新凭证，日志清理不删除独立日标记/去重账本。

### P4：明文数据与新 V1 — 已完成

- [x] `request_revisions.raw_curl/spec_json` 明文保存；`run_responses.body_bytes` 为 BLOB，并保存 `content_type`、`charset`、`capture_state`。
- [x] 保留字节安全复制、1 MiB 上限、完整/部分/截断/空/未获取/未保存状态；列表不带正文，详情 `no-store`、HTML 纯文本、二进制 Base64。
- [x] 合并新 `db/V1.sql`，`schema_info` 格式为 `signdesk-plain-v1`、version 1；不保留 V2～V4 迁移。
- [x] 旧/不完整/不匹配数据库先只读校验并拒绝，不改文件、不迁移、不清理、不重建；正常运行采用实例锁、WAL 和单连接池。不得通过本次源码改动推断可以删除用户数据库。
- [x] 移除旧 `ApiController`、`CatalogService`、`Db`、`SecretStore`、`RunResponseStore`、`DatabaseMigrator`、旧业务服务及旧 SQL 迁移；保留 TLS、Cookie、网络策略、Base64 和 UUID 幂等键。

### P5：明文备份、前端与部署适配 — 已完成

- [x] 新格式仅含 `format: "signdesk-plain-v1"` 与 `payload`，无 `formatVersion`。保留 `{includeRequests:false}` 导出、`{backup}` 预览、`{backup,replace:true}` 导入 HTTP 契约。
- [x] 完整模式导出当前已配置 cURL 和日标记；普通模式排除 cURL/日标记并恢复停用的 `authPaused` 占位；两者都不含历史修订、执行响应或活动队列。
- [x] 预览返回顶层 counts/includesRequests/mode=`replace`/message；导入成功返回 `{restored:true,paused:true}`。严格检查结构、正十进制 long 字符串 ID、关系、规则、计划和字节大小。
- [x] 导入先预览再明确整体替换；维护互斥、队列空闲、一致短事务、失败回滚、导入暂停且不触发请求。payload 8,388,608 UTF-8 bytes，文件及实际 HTTP 读入 12,582,912 bytes。
- [x] 更新必要前端、配置、Docker/Compose、CI 引用；代理模式为 `system`、`direct`、`http`、`socks`。完整配置敏感明文的提示保留。

### P6：测试、清理与全链路验证 — 部分完成，浏览器 E2E 阻塞

- [x] 最终后端 **110/110**，0 失败、0 错误、0 跳过。包括原 105 项与新增 5 项自动入队竞态测试。
- [x] 独立验证 worktree `agent-a2230dd6f570fc554` 完成 fresh `npm ci` → 前端 8/8 → Vite build → `mvn clean verify` 110/110 → `node scripts/build.mjs` 完整发布成功。
- [x] 主工作区现有前端依赖下 unit 8/8 和 build 通过。主工作区的“清理 static + Maven clean verify”组合命令在执行前被隔离工具拒绝；没有重跑主目录 Maven clean build，也没有将独立构建结果冒充为主目录 Maven 结果。
- [x] 以非破坏方式对齐发布物：189 份主工作区源码/配置与独立构建目录 LF 规范化后逐字节一致；`frontend/dist` 与 JAR 静态 3 文件逐字节一致；验证 JAR 复制到新的 `backend/target/signdesk-0.1.0-refactored.jar`，未覆盖旧产物。文件 40,854,484 bytes，SHA-256 `d30c955c167734f15645a041da2fa0e90e0f1cc5ad8552a77904e25102963c54`。常规构建文件名仍为 `signdesk-0.1.0.jar`。
- [x] 静态质量/反模式审阅通过；曾实际发现并复现旧计划扫描与维护导入竞态，后修复并加入确定性测试。
- [ ] **浏览器 E2E 未运行**：8 项、3 个文件只通过 Playwright `--list` 收集，实际执行 0 项。Edge 环境命令两次在启动前遭隔离工具拒绝；18080/18081/Edge 均未启动，无截图或测试报告。拒绝不等于测试失败，但也不构成通过；未绕过权限或更改设置。
- [ ] Docker 实际构建/运行、线上 CI、真实平台联调和 24 小时运行观测未验证。

Vite 提示 JavaScript 包超过 500 kB（实际 JS 1,063.77 kB）；`ResultRules` deprecated、`WebContract` unchecked 和 Mockito 动态 agent 有警告。一次 `Queue operation failed (UncategorizedSQLException)` ERROR 来自预期故障注入，仅记录异常类型；110 项均通过。没有报告未测 coverage 为通过。

**P6 后续动作**：当前会话的人工 E2E 命令见 [验证记录](validation.md) 末尾“当前会话的人工 E2E 入口”。是否运行由用户决定；截至该记录生成时没有实际输出，不得承诺通过或关闭浏览器验收。运行后须以真实输出及测试报告更新状态，不得把 `--list` 或历史 7 项计作通过。

### P7：文档与交付收尾 — 已完成（代码不提交）

- [x] README 全文改为当前实际使用说明，区分常规构建产物和本次可运行验证副本；不包含过期的迁移承诺。
- [x] 需求/架构更新为实际代码状态、真实类名、竞态保护及后端通过/浏览器待验收边界。
- [x] AGENTS 改为长期维护约定；验证记录保留历史并追加本轮精确命令、结果、artifact 和未验证项。
- [x] 在隔离 worktree 输出相对 P1 文档基准的六份 Markdown 增量补丁；本轮不 stage、不 commit、不 push。代码仍由用户决定提交/推送。

## 3. 本轮验证索引

后端 110 项测试类数量：`BackendArchitecture` 5、`BackendLayering` 4、`WebContractIntegration` 9、`CurlParser` 18、`HutoolExecutor` 12、`ResultRules` 2、`TLS` 1、`StorageQueueIntegration` 39、`Schedule` 3、`BackupValidation` 5、`DatabaseInitializer` 7、`AutomaticRace` 5。总计 110。

完整环境、前端与 JAR 静态资源对比、命令拒绝边界、E2E 未运行事实、警告、artifact SHA 和旧验证历史见 [验证记录](validation.md)。

## 4. 最终交接

- 当前主工作区源代码已整合，但尚未提交/推送；HEAD `475c46a` 是实现前基线，不是完整实现提交。提交与推送留给用户决定。
- 本文及本次文档补丁不授权删除任何现有数据库、目录或用户文件；运行新系统请指定全新 `SIGNDESK_DATA_DIR`。
- **仍待完成的 P6 浏览器验收**必须醒目标记。不得宣称 P1～P7 全部无条件验收通过。
