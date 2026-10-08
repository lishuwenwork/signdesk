# 签行 SignDesk · 实施架构 v0.1.0

本文件描述仓库中实际代码。原先 v1.1 是设计评审稿；当前系统已有 Spring Boot 服务、SQLite 和 Vue 页面，不使用单文件原型模拟执行。

## 工程与依赖

同仓库两个工程，开发时独立启动；发布脚本构建 Vue，复制静态资源到后端，Spring Boot 打包单个 JAR。运行只需要 Java 21、可写数据目录和独立密钥目录。精确依赖见 backend/pom.xml 与 frontend/package-lock.json。

后端使用 Spring MVC。平台基础读取和插入通过 MyBatis-Plus；SQLite 队列、去重、关联查询和事务状态更新通过 JdbcTemplate。两个入口共用 DataSource 和 Spring 事务管理器，不启用 Redis、额外数据库服务或应用登录。

## 模块对应

| 包／类 | 实际职责 |
|---|---|
| common.ApiController / Errors | REST 输入校验、安全错误摘要和管理接口 |
| config.StorageConfig | 数据源、外部数据目录、Clock 与 Spring TaskScheduler |
| config.RequestBoundary | JSON 写入、同来源检查、禁止缓存和响应头 |
| platform.CatalogService | 平台、账号、请求与接口规则模板 CRUD、乐观版本冲突、请求版本保存 |
| engine.CurlParser / RequestSpec | 安全分词、cURL 语义、诊断和防御性复制 |
| engine.HutoolRequestExecutor | Hutool 实际发送、原始 URL 连接、独立 Cookie、TLS、总期限、响应上限和受控重定向 |
| engine.ResultRules | JSON 路径／类型判断、文本包含、HTTP 与业务分类 |
| schedule.ScheduleSpec / ScheduleService | 计划校验、星期／时区、下一次时间、生效范围 |
| schedule.ScheduleScanner | 每 5 秒查有效计划、合并错过时刻、触发批次、独立日志清理 |
| run.RunService | SQLite 持久队列、版本／规则快照、平台串行与并发、取消、恢复 |
| run.SettingsService | 暂停、并发、超时、保留天数与乐观锁 |
| storage.InstanceLock / DatabaseMigrator | 单进程锁和事务性数据库版本迁移 |
| storage.SecretStore | 主密钥装载、首次生成、AES-GCM、丢钥拒绝覆盖 |
| storage.RunResponseStore | 执行响应快照加密、执行项关联验证、详情解密与文本／Base64 展示 |
| backup.BackupService | 一致逻辑快照、KDF 加密、结构校验、事务恢复与主密钥迁移 |

## 数据模型

数据库迁移脚本 `backend/src/main/resources/db/V*.sql` 是字段与约束的权威定义，当前顺序执行至 V4。

| 表 | 用途 |
|---|---|
| platforms / accounts / requests | 分组、启用、排序、规则、当前版本与凭证暂停 |
| request_revisions | 按请求 ID / 版本保存认证加密的完整快照 |
| request_templates | 平台下多份名称／结果规则模板，不保存 cURL 或账号凭证 |
| platform_schedules | 一个平台一个计划，多个时间点、星期、时区、生效时间 |
| run_batches | 触发来源、UTC 时刻、业务日期、计划快照、取消和批次状态 |
| run_items | 冻结请求版本、结果规则、执行顺序、状态和安全摘要 |
| run_responses | 绑定执行项 ID 的 AES-GCM 响应快照，随执行记录级联清理 |
| daily_completions | 与日志独立的请求／业务日期完成标记 |
| request_day_states | 与日志独立的本周期待确认标记 |
| settings / schema_migrations | 全局配置与迁移版本 |

SQLite 在本地磁盘，WAL、foreign_keys、busy_timeout=5000、Hikari 最大连接数 1。网络发送和请求间隔不持有数据库事务。一个数据目录只允许一个进程，部署不提供多节点调度。

## 执行与恢复

手动接口先在短事务中创建批次和排队项，返回 202 和批次 ID。手动 key 与平台组合保证重试管理接口不会创建第二批；自动唯一键是平台 + UTC 触发时刻，计划 revision 不参与去重。

每 500 毫秒从持久队列调度可执行平台，不同平台最多并发配置中的数量，同平台由一个工作线程依次发送。创建项时冻结请求 revision 与规则，更新 cURL 不修改已排队的快照。发送前重新检查启停、取消、完成、凭证暂停、待确认和补执行窗口。

发送前落库 running；返回结果后保存安全记录和独立周期标记。数据库结果保存会在内存内有限重试，不重新发送 HTTP。服务崩溃后 running 标为 unknown，queued 在恢复后重新检查窗口和状态。

正常取消立即取消尚未发送的项；当前请求继续收尾，由真实响应决定状态。正常停机等待有限收尾时间，无法完成的请求在下次启动时保守标为 unknown。HTTP 超时、断开、未匹配或 5xx 不确定响应不触发自动重试。

## 计划

使用 java.time、注入的 Clock、Asia/Shanghai 或 UTC。每日成功后跳过默认开启；关闭后每个时刻可重复执行，但本周期 unknown 仍阻止自动重发。无补执行时为调度扫描留 30 秒领取容差；启用补执行时只处理当前业务日期、指定窗口和计划生效后的触发点。已在有效窗口开始的完整平台批次可依次完成，窗口不要求所有账号在同一分钟发送。

全局暂停定时只影响新触发，手动和已有队列仍运行。平台停用会在发送前阻止该平台项；计划修改只影响新的触发，现有批次保持原配置。

## 请求适配与保密

cURL 不进入 shell。Hutool 逐次构建独立 HttpRequest，禁用全局 Cookie，并用 URLStreamHandler 创建携带原始 URL 的 JDK 连接，绕过 Hutool 的 URL 重写。Body 用字节发送。实际 URL、重复 Query、重复 Header、Body 和 Cookie 已由本地回显测试核对。

使用 SSLContext 默认信任与默认主机名验证；测试覆盖不可信证书、可信但主机名不符、正常受信任测试证书。`-L` 只在同来源内受控处理，最多 5 跳；每跳重新校验网络地址，并共享总执行期限。跨来源停止，避免转发任意位置的凭证。响应读取限 1 MiB。

AES-GCM 认证数据绑定请求 ID 和 revision，保存原始 cURL 与结构化快照的全部内容；执行响应单独绑定 `run-response:` + 执行项 ID 加密。主密钥来自环境或独立文件，第一次自动生成后复用；存在请求或响应密文时缺少文件直接停止启动。普通列表不加载解密内容，应用日志不保存响应正文；只有执行详情接口显式解密响应，API 禁止缓存。

每次执行保存已收到的最终响应字节、Content-Type、字符集及完整／部分／截断状态。网络完成后在数据库事务外加密，再与执行状态、周期标记同事务落库；数据库保存重试不会重发 HTTP。响应上限维持 1 MiB，超限只保存前缀并保持 unknown；超时也可保留实际收到的部分字节。空 HTTP 错误响应不保存 Hutool 合成的错误提示。正文随 run_items 外键级联清理，普通配置／加密配置备份不导出执行历史。

## API 与前端

管理接口前缀 `/api`，契约与实际映射见 ApiController 和 BackupController。范围包括平台／账号／请求、解析、规则试算、计划、执行、批次／取消、记录、设置、概览、健康和备份。

Vue 使用 hash 路由。概览每 2 秒刷新，记录每 3 秒、平台每 4 秒、其他状态每 5 秒；页面卸载停止轮询，异常连接不会在后台无限弹窗。轮询只显示状态，不驱动调度。凭证不写浏览器持久存储。

接口规则模板通过平台范围的 `/api/platforms/{platformId}/templates` 接口管理，支持乐观版本更新。前端请求与模板共用规则编辑器；选择模板只填写名称和规则，仍要求输入当前账号的完整 cURL。请求保存独立规则副本，不持有模板外键，模板变更不会改写请求或队列快照。普通配置与加密备份均包含模板；缺失模板字段按空列表导入，恢复时统一校验所属平台。

## 当前边界

这是个人单实例系统。没有用户权限隔离、动态签名生成、Token 刷新、多步变量传递、文件上传或全部 cURL 选项等价支持。精确导入限制见 README。完整可迁移备份是当前配置的逻辑快照，恢复不还原历史日志和旧请求版本；完整历史需停服备份数据库与主密钥。验证范围见 validation.md。
