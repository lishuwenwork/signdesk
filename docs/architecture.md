# SignDesk 后端重构架构

> 状态：**P1～P5 已实现；P6 自动化发布验证通过，浏览器 E2E 待运行；P7 文档收尾已完成**。日期：2026-10-09。
> 代码集成在 `refactor/backend-ruoyi-style` 主工作区，`475c46a` 是实现前基线；当前实现尚未提交/推送。后端最终 110/110、前端单元 8/8、独立全量发布通过；浏览器 E2E 实际运行 0 项。详见 [重构计划](refactoring-plan.md) 与 [验证记录](validation.md)。
> 本文说明已落地结构、数据约束和仍需保留的设计不变量；用户验收要求见 [需求文档](requirements.md)。第 1～12 节保留 2026-10-09 的重构记录；2026-10-10 正式全站前端落地与只读摘要扩展见第 13 节，最新验证以 validation.md 的当日章节为准。

## 1. 设计目标

- 使用用户熟悉的 Controller → Service 接口 → ServiceImpl → Mapper → SQLite 调用链。
- 普通 CRUD 交给 MyBatis-Plus，复杂 SQL 集中在 Mapper XML；不再用通用 Db 工具在业务中拼 SQL、传 Map。
- 保留单体、单数据库、单 JAR，不移植若依的公共框架、权限或基础设施。
- 请求、响应和导出文件全部明文；删除加解密、主密钥及旧格式兼容。
- 采用全新数据库 V1；保留已有功能与关键执行语义，而不是重新开发另一套产品。

## 2. 参考依据与取舍

实际参考 `D:/Project/RuoYi-Vue-Plus/ruoyi-modules/ruoyi-demo` 的 TestDemo 业务：

| 参考文件（相对 ruoyi-demo） | 本项目借鉴 |
|---|---|
| `src/main/java/org/dromara/demo/controller/TestDemoController.java` | Controller 分业务、校验参数、构造器注入服务 |
| `.../domain/TestDemo.java` | 实体使用 MP 表与主键注解 |
| `.../domain/bo/TestDemoBo.java`、`.../domain/vo/TestDemoVo.java` | 明确入参与出参，不直接暴露数据库对象 |
| `.../service/ITestDemoService.java`、`.../service/impl/TestDemoServiceImpl.java` | 接口与实现分离，直接注入 Mapper，业务方法清晰 |
| `.../mapper/TestDemoMapper.java`、`src/main/resources/mapper/demo/TestDemoMapper.xml` | 基础 CRUD 复用，特殊查询单独命名并集中到 XML |

参考中的 Service 接口没有继承 MP `IService`，实现也没有继承 MP `ServiceImpl`。因此本项目采用 `IXxxService`／`XxxServiceImpl`，不为了形式增加框架父类。

若依的 `BaseMapperPlus`、`BaseController`、`BaseEntity`、`TenantEntity`、`MapstructUtils`、`R`、`TableDataInfo` 是项目扩展，不是 MP 自带 API。**本项目不复制这些公共模块。** 不增加权限、租户、审计字段全集、Excel、翻译注解、通用操作日志切面或逻辑删除。

## 3. 技术与运行形态

保持已有版本基线：Java 21、Spring Boot 4.1.1、MyBatis-Plus 3.5.17、SQLite JDBC 3.53.4.0、Hutool 5.8.47、Vue 3.5 / JavaScript / Element Plus / Vite。不是本次任务所需的依赖不升级。当前使用 Boot 4 的 MP starter 与 Jackson 3 `tools.jackson`，不能照搬参考工程的 Boot 3／Jackson 2 依赖。

```text
浏览器（Vue，同源 /api）
          ↓
业务 Controller → IService → ServiceImpl → Mapper / XML → SQLite
                                  ↑
计划扫描 / 队列调度 → 工作线程 → Hutool 执行器 → 目标 HTTP 服务
```

- 生产只运行一个 Spring Boot JAR；开发时继续使用 Vite 代理。
- SQLite 位于外部本地数据目录；单实例锁、WAL、外键、busy_timeout、单连接池继续保留。
- 仍使用 Spring 调度与有界工作线程池，不增加 Redis 或消息队列。
- 使用 Lombok 减少访问器与构造器样板；不引入 MapStruct-Plus、代码生成框架或若依 starter。

## 4. 已落地目录与职责

当前后端代码已按以下职责拆分，实际业务实现不是计划中的空壳目录：

```text
backend/src/main/java/com/signdesk/
├── controller/          # 9 个 HTTP Controller；参数、校验、Service 调用、VO 返回
├── domain/              # 实体；字段私有并使用 Lombok
│   ├── bo/              # 输入对象
│   └── vo/              # 列表、详情、分页及动作输出
├── mapper/              # MyBatis-Plus BaseMapper 与命名清晰的特殊 SQL 方法
├── service/             # 10 个 IXxxService 业务接口
│   └── impl/            # 对应 XxxServiceImpl 实现
├── engine/              # cURL 解析、请求/响应值对象、规则和 HTTP 执行
├── scheduler/           # 计划扫描、运行协调、队列派发和平台工作线程
├── config/、common/      # Web 边界、配置、Clock、ID 与安全异常等基础能力
└── storage/             # 新数据库初始化与实例锁等基础设施

backend/src/main/resources/
├── db/V1.sql            # 唯一的新库初始化脚本；schema_info 标记新格式
└── mapper/*.xml         # 复杂关联、UPSERT、CAS 和查询 SQL
```

10 组业务接口/实现分别处理 platform、account、request、requestTemplate、schedule、run、runRecord、settings、backup 和 system。普通查询更新使用 MP 原生 `BaseMapper`、Lambda Wrapper 和乐观锁；复杂 SQL 集中到 6 个生产 XML：`BackupMapper.xml`、`QueueMapper.xml`、`RequestRevisionMapper.xml`、`RunRecordMapper.xml`、`ScheduleQueryMapper.xml`、`SystemMapper.xml`。Controller 不直查数据库、不编排队列；Service 不拼 SQL。

平台树装配采用 3 次批量查询再分组组装，不逐节点访问数据库，无 N+1 查询。运行职责由 `RunCoordinator`（维护状态及平台名额）、`RunDispatcher`（有界工作线程派发）、`PlatformWorker`（HTTP、间隔等待和结果处理）与 `ScheduleScanner`（计划扫描及清理）协作，不再由单体运行服务包揽所有职责。

### 4.1 编码约定

- Java 实体字段使用 private 和访问器；Controller／ServiceImpl 使用 `final` 字段和构造器注入，可用 `@RequiredArgsConstructor`。
- 服务名采用 `IPlatformService`、`PlatformServiceImpl`；实现类只实现自己的业务接口，不默认继承通用 CRUD Service。
- 查询方法用 `queryById`、`queryList`、`queryPageList` 等清晰名称；有业务意义的操作直接命名为 `enqueue`、`cancel`、`replaceCurl`，不强行套 CRUD 名称。
- BO 表示输入，VO 表示输出；操作需要的字段才进入 BO。不继承含 `Map<String,Object> params` 的通用基础对象，不允许提交内部状态字段。
- 查询、创建、修改差异明显时拆 BO；只有字段确实相同才共享校验组，避免一个巨大可空对象服务所有接口。
- 禁止 Controller 返回裸 `Object` 或把任意数据库行 Map 直接返回前端；JSON 规则值或必要的协议解析树不属于业务 Map 滥用。
- Lombok `@Data`／record 的自动 toString 不能泄露 rawCurl、bodyBytes、代理或响应正文；敏感类型用访问器或显式排除／重写 toString。
- 保留有价值的业务注释和方法说明，不复制作者／日期样板、空 TODO、死代码、没有用途的校验空方法。
- 纯解析器、HTTP 执行器、转换器和值对象不机械地增加接口＋实现；现有正确的不可变值对象可继续使用 record。

### 4.2 业务服务划分

| 服务 | 职责 |
|---|---|
| `IPlatformService` | 平台 CRUD、平台树装配；列表只能使用安全 VO |
| `IAccountService` | 账号 CRUD、平台归属和关联删除检查 |
| `IRequestService` | 请求 CRUD、规则更新、明文修订创建/查询、凭证暂停解除 |
| `IRequestTemplateService` | 同平台模板 CRUD、归属与乐观版本检查 |
| `IScheduleService` | 平台计划校验、保存、生效时刻、下一次时间 |
| `IRunService` | 手动/自动入队、原子领取、发送前门禁、结果落库、取消、崩溃恢复；不持事务做网络 I/O |
| `IRunRecordService` | 分页日志、执行详情、响应字节展示；列表不读取响应 BLOB |
| `ISettingsService` | 全局执行及代理设置校验与保存 |
| `IBackupService` | 新明文配置快照、校验、预览和替换导入 |
| `ISystemService` | 状态、概览与安全统计，避免 Controller 内直接查询数据库 |

运行协调放在 `scheduler`：计划扫描器调用 `IRunService`；派发器管理同平台互斥与工作线程；工作线程调用已验证的引擎，在独立短事务中提交结果。不要将业务 Service、调度注解和线程生命周期重新堆进一个超大实现类。

## 5. MyBatis-Plus 与 SQL 边界

### 5.1 基础 CRUD

- 单主键实体对应 `BaseMapper<Entity>`；用 `LambdaQueryWrapper`／`LambdaUpdateWrapper`，不使用列名字符串进行普通查询和修改。
- 配置的 `version` 保持乐观锁语义。普通 `updateById` 可配 `@Version` 与乐观锁插件；自定义更新必须显式包含原版本、递增版本并检查影响行数。
- 新生成业务 ID 使用 `ASSIGN_ID` 或同一 MP ID 入口；都按 String 传输。调用 JDBC/XML 的特殊插入也不能引入另一套 ID 算法。
- Entity→VO 使用明确转换器或 Mapper 的专用 resultType，不返回整个 Entity。规则 JSON 转换只集中在转换器或少量 TypeHandler，避免四处序列化。
- 树查询按平台／账号／请求分组装配，不以“每个节点再查一次”的方式引入 N+1。

### 5.2 必须明确表达的复杂操作

以下使用有业务名称的 Mapper 方法，SQL 放 `resources/mapper`：

- 平台树所需的关联查询、日志联查分页与概览聚合。
- SQLite `ON CONFLICT` UPSERT、自动发生点去重、日标记维护。
- queued→running 的原子条件更新、发送前状态检查、崩溃恢复与批量取消。
- 导入替换时的必要批量操作、记录过期清理。

所有业务数据使用 `#{}` 绑定，客户端不能提供任意 SQL、表名或排序表达式。不保留通用 `delete(String table, ...)` 接口，也不把 SQL 从 Service 原封不动转移进 Java 注解字符串堆。

复合主键（请求修订、请求＋业务日期）不是 MP 原生单 ID CRUD 场景，应显式按全部键查询/更新；不伪造 `@TableId` 或依赖 `saveOrUpdate` 做原子 UPSERT。

### 5.3 保留的基础设施例外

数据库创建必须先于 Mapper 使用，`DatabaseInitializer` 可以使用 JDBC／ScriptUtils 读取元数据并执行 V1。这个启动例外不能成为业务 Service 继续使用 JdbcTemplate 的理由。原有万能 `storage/Db` 删除。

## 6. 全新 V1 数据模型

新数据库模型已实现于 `backend/src/main/resources/db/V1.sql`；该文件是 `signdesk-plain-v1` / schema version 1 的唯一初始化脚本，采用明文字段并合并当前业务所需表。下表总结实际结构与约束，不是待实现目标。

| 表 / 实体 | 关键内容 | 主键与约束 |
|---|---|---|
| `platforms` / Platform | 名称、备注、启停、排序、版本 | 雪花字符串 ID |
| `accounts` / Account | 平台、别名、启停、排序、版本 | 雪花 ID，平台外键 |
| `requests` / RequestDefinition | 账号、名称、启停、当前修订、rules_json、auth_paused、safe_host、method、版本 | 雪花 ID，账号外键；列表不附带完整请求 |
| `request_revisions` / RequestRevision | **raw_curl TEXT、spec_json TEXT**、创建时间 | `(request_id, revision)`；移除 ciphertext；spec 中字节仍可用 Base64 编码 |
| `request_templates` / RequestTemplate | 平台、名称、rules_json、版本 | 雪花 ID，平台外键 |
| `platform_schedules` / PlatformSchedule | 星期/时间、时区、间隔、补执行、每日跳过、修订、生效时间 | platform_id 单主键 |
| `run_batches` / RunBatch | 来源、计划时刻、业务日期、计划快照、幂等键、状态、取消标记 | 雪花 ID；自动平台＋UTC时刻唯一；手动键唯一 |
| `run_items` / RunItem | 批次、请求修订、规则快照、顺序、状态、HTTP、耗时、摘要、时间 | 雪花 ID；保留队列、请求状态和清理索引 |
| `run_responses` / RunResponse | **body_bytes BLOB、content_type、charset、capture_state** | run_id 主键/FK；移除 ciphertext；状态区分 complete/partial/truncated/unavailable |
| `daily_completions` / DailyCompletion | 请求、业务日期、完成执行 ID | `(request_id,business_date)`；完成执行 ID 不因历史清理级联删除本表 |
| `request_day_states` / RequestDayState | 请求、业务日期、unknown_pending | `(request_id,business_date)` |
| `settings` / AppSettings | 暂停、并发、超时、保留天数、代理模式/主机/端口、版本 | 固定 id=1，不生成雪花 ID |
| `schema_info` | 新基线标识 `signdesk-plain-v1`、版本 1 | 启动基础设施标记，不建立业务 Service |

平台／账号／请求的外键级联和“有活动任务不能删”的业务校验都保留。删除 run_items 级联 run_responses，但不能影响日标记或自动发生去重账本。请求的 `currentRevision`、配置编辑的 `version`、计划的 `revision` 分别有独立语义，不因统一命名或乐观锁插件而合并。

### 6.1 初始化契约

1. 对已存在的 SQLite 文件先进行只读结构检查。发现旧标记、旧迁移表、不匹配标记或不完整结构时立即拒绝；拒绝前不创建/修改 schema、不改文件字节、不迁移、不清空、不删除。
2. 只有确认是全新空库或结构完整且标记为 `signdesk-plain-v1` / version `1` 后，才进入正常运行初始化：获取数据目录实例锁，建立 WAL、外键、busy_timeout 与单连接池。
3. 全新空库在一个事务中只执行新 `db/V1.sql`，写入默认设置和 `schema_info`；正常重启不重复初始化。
4. 删除旧 V2/V3/V4 和迁移循环；不实现自动清库、旧库升级或自动替换开关。开发和验收使用新目录，用户既有文件保持原样。

## 7. 持久队列与事务设计

| 阶段 | 事务内 | 事务外 |
|---|---|---|
| 创建批次 | 幂等/发生键校验，冻结请求修订/规则/计划，插入 queued | 返回批次 ID，不发送 HTTP |
| 领取平台批次 | 条件变更批次状态，确认唯一领取 | 获取平台执行名额、派发工作线程 |
| 发送前 | 检查取消/启停/日标记/窗口，条件更新 queued→running 和 started_at | 读取冻结请求快照、独立 Hutool HTTP 调用 |
| 结果落库 | 同事务写最终状态、响应明文字节、完成/待确认标记、必要的凭证暂停 | 处理字节展示前的准备，不重发请求 |
| 请求间隔 | 不持事务 | 等待、检查取消/停机，再处理下一项 |
| 恢复/取消/清理 | 批量状态转换或删除和约束维护 | 不产生补偿性 HTTP 重试 |

- 状态转换必须检查影响行数；禁止先 SELECT 然后无条件 UPDATE 充当互斥。
- Spring `@Transactional` 放在外部调用的 ServiceImpl 方法；不能因把方法搬到同类内部调用而使事务代理失效。无法自然分开时保留少量明确 `TransactionTemplate`，不为追求注解数量牺牲原子性。
- 队列调度只在启动恢复完成后就绪；崩溃留下的 running 标记 unknown，不能重新发送。
- 同平台串行和有界跨平台并发保留；导入维护锁与新入队必须互斥，不能在“判断空闲”和“替换配置”之间允许插入任务。
- 持久化结果失败可有限重试数据库写入，不能重进 HTTP 执行器；无法落库时停止不安全的继续执行并按未知状态恢复。
- 时间用注入的 Clock；计划仍按平台时区取业务日期，自动 occurrence 键为平台＋UTC instant，不包含计划修订或扫描 generation。
- `ScheduleScanner` 在读取计划前捕获本轮 scan generation；维护开始会使旧 generation 失效。自动入队在协调锁与短事务内重新核对全局 paused、当前计划、`effectiveFrom` 和 due 条件，拒绝已失效扫描。5 个确定性 `AutomaticEnqueueRaceIntegrationTest` 回归覆盖维护/导入后恢复相同 revision 与 `effectiveFrom` 仍拒绝旧扫描的竞态。

## 8. 完整去除应用加密

### 8.1 已完成的结构清理

- 已移除应用层请求/响应/配置加解密实现和相关密钥、口令、salt、nonce、AAD、密钥装载/生成/迁移流程及配置引用。
- 旧 `RunResponseStore` 加密包装已删除；响应字节持久化、字符集解码、二进制/Base64 和 capture 状态由当前记录/映射路径完成，不保留只负责转发的空壳。
- 旧 `BackupService` 密码与加密分支、旧备份格式解码和兼容分支已删除；由 `IBackupService` / `BackupServiceImpl` 实现新明文契约。
- 旧 `ApiController`、`CatalogService`、`Db`、`SecretStore`、`RunResponseStore`、`DatabaseMigrator`、旧业务服务以及 `V2.sql`～`V4.sql` 已移除；当前初始化由 `DatabaseInitializer` 负责，复杂查询落在专用 Mapper/XML。
- `SIGNDESK_KEY_FILE`、`SIGNDESK_SECRET_KEY`、`signdesk.key-file`、`signdesk.secret-key` 及相关运行配置、部署挂载和 UI 引用已移除。

**仍保留** HTTPS/TLS 证书验证、证书测试、独立 Cookie、网络策略、Base64 字节编码、`crypto.randomUUID()` 幂等键和日志安全限制。它们不是应用数据加解密功能。

### 8.2 明文的访问纪律

- 原始 cURL、结构化请求和响应都按明文写入 SQLite，无虚假的“明文但仍包一层加密接口”。
- 列表与批次查询使用列投影，不取 raw_curl/spec_json/body_bytes。
- `/requests/{id}/revision` 和 `/runs/{id}` 明确返回详情，保持 `Cache-Control: no-store`。
- 不返回“解密失败”等已无意义状态；未保存、未获取、空、部分、截断仍须区分。
- 浏览器仅纯文本呈现响应，关闭清除；不在 LocalStorage/IndexedDB 中缓存凭证。
- 数据库与完整导出文件的访问风险通过明确提示和文件/网络访问权限处理，不偷偷引入新的加密层或应用登录。

## 9. 新明文配置格式（已实现）

现有 HTTP 路径保持不变，请求格式为：

- `POST /api/backups/export`：`{ "includeRequests": false }`，字段可省略且默认 `false`。
- `POST /api/backups/preview`：`{ "backup": <配置对象> }`。
- `POST /api/backups/import`：`{ "backup": <配置对象>, "replace": true }`。

导出文件只有 `format: "signdesk-plain-v1"` 和类型化 `payload`，没有 `formatVersion`。payload 字段为 `includesRequests`、`settings`、`platforms`、`accounts`、`requests`、`templates`、`schedules`、`completed`、`pending`。代理模式为 `system`、`direct`、`http`、`socks`。

- 完整模式包含当前已配置的 cURL 和日完成/待确认标记；合法的未配置占位请求不含 `rawCurl`。
- 普通模式排除 `rawCurl` 和日标记，导入后请求停用并置 `authPaused` 占位；其 `method` 为 GET、`currentRevision` 为 1、`safeHost` 为空。界面显示未配置、禁止查看详情，但允许用户更新 cURL。
- 两种模式均不导出旧请求修订、历史执行响应或活动队列。实际 `BackupPreviewVo` 返回顶层 `platforms`、`accounts`、`requests`、`templates` 数量，以及 `includesRequests`、`mode: "replace"`、`message`（不是嵌套 `counts`）；导入成功返回 `{ "restored": true, "paused": true }`。
- 只接受新格式与完整新结构。ID 校验为正十进制 long 范围字符串，检查父子关系、规则、计划和记录标记；不接受旧 UUID/旧文件兼容格式。
- 导出从一致的短事务快照取数；预览先完成结构/关系/大小校验。导入要求 `replace: true`、队列空闲和维护互斥，在短事务中一致替换；任一错误整体回滚。新请求修订号为 1，计划 `effectiveFrom` 取导入时 Clock，导入后全局定时暂停，不发送导入请求。
- payload 上限为 **8,388,608 UTF-8 bytes**；导入文件及实际 HTTP 读取上限为 **12,582,912 bytes**。入口限制真实读取量，不能仅依赖 `Content-Length`，对象数量另有上限。
- 完整导出包含明文请求凭证；普通模式也不能保证用户手写的备注/规则不含秘密。界面和文档须明确提示文件敏感，不把格式标识或 JSON 校验称为加密/身份认证。

## 10. API 与前端适配范围

非加密业务路径和 JSON 形状保持，内部用明确 BO/VO 替代 Map。**不为复刻若依 R／TableDataInfo 包装而改变整套前端 API。**

| 接口组 | 路径示例 | 重构要求 |
|---|---|---|
| 平台/账号 | `/platforms`、`/platforms/{id}/accounts`、`/accounts/{id}` | 拆分 Controller，平台树仍有 accounts/requests |
| 请求 | `/accounts/{id}/requests`、`/requests/{id}`、`/requests/{id}/revisions`、`/requests/{id}/revision` | 明文版本服务；保留 revision 与更新解除暂停行为 |
| 预览/规则 | `/requests/preview`、`/rules/test` | 保留解析和试算，不发送真实请求 |
| 模板 | `/platforms/{platformId}/templates[/{id}]` | 平台隔离、版本冲突、独立规则副本 |
| 计划 | `/schedules`、`/platforms/{id}/schedule` | 保留时区、due、生效时间和输入校验 |
| 执行 | `/runs`、`/batches`、`/batches/{id}`、`/batches/{id}/cancel` | POST runs 仍202；幂等与队列行为不变 |
| 执行详情/记录 | `GET /runs`、`GET /runs/{id}` | 分页仍 items/total/page/size；response 不再解密 |
| 设置/状态 | `/settings`、`/system/status`、`/dashboard` | 保留代理与运行状态，无 Controller 直连数据库 |
| 导入导出 | `/backups/export`、`/backups/preview`、`/backups/import` | 移除密码与加密格式，采用第9节新契约 |

错误继续使用明确 HTTP 状态（400校验、404不存在、409冲突）和安全 message。分页默认20、最多100，固定排序或白名单，不接受任意 SQL 片段。`IdVo`、保存/删除结果等小型类型保持现有响应字段；不直接序列化 MP Page 或 Entity。

前端只做必要适配：设置页去密码框、完整配置不再标“加密”、请求与响应弹窗去加密文案、移除 decryption_failed、保留敏感内容提示。不重做界面、路由或规则编辑器。

## 11. 关键架构决定

| 决定 | 理由与代价 |
|---|---|
| ADR-01 单体＋若依式轻量分层 | 用户可按熟悉目录维护；接受必要 BO/VO 文件数，不引入权限/租户大框架 |
| ADR-02 MP CRUD＋专用 XML | 减少字符串字段和重复 SQL；复杂原子语义仍需手写并测试，不能用通用 saveOrUpdate 代替 |
| ADR-03 全部明文持久化/配置导出 | 用户已确认，删除密码和密钥管理负担；代价是文件泄漏将直接暴露敏感数据，需文件/网络访问限制 |
| ADR-04 全新 V1，不兼容旧库/备份 | 未上线，避免过渡代码；代价是必须新数据目录，不提供自动转换，也不自动删除旧文件 |
| ADR-05 业务 HTTP 契约保持 | 避免无收益的全前端修改；仅导入导出与加密专属状态有明确变更 |
| ADR-06 保留成熟执行核心 | 重构重点是职责/数据访问，不重写 cURL/HTTP/调度算法；通过回归测试验证新分层未破坏行为 |

上述架构决定已在当前主工作区实现；验收边界和未完成项见第 12 节及 [验证记录](validation.md)。

## 12. 实现与验证状态

截至 2026-10-09，已完成 Controller/Service/Mapper 分层、持久队列与调度拆分、明文新 V1、明文配置导入导出，以及前端与部署适配。后端最终 **110/110**（0 失败、0 错误、0 跳过），前端单元 **8/8**；独立工作树 fresh `npm ci`、Vite build、Maven clean verify 和 `node scripts/build.mjs` 全发布均通过。完整类计数、警告和独立 artifact 信息见 [验证记录](validation.md)。

| 检查项 | 当前结果 |
|---|---|
| 后端架构/分层、Web 契约、cURL/HTTP、规则、TLS、队列、计划、备份、数据库初始化 | 对应自动化用例均通过；包含 5 项确定性自动入队竞态回归 |
| 独立全量发布 | 成功，JAR 包含最新前端；无旧业务/应用数据加密类或密钥配置 |
| 主工作区前端 | 现有依赖下单元 8/8、生产 build 通过 |
| 主工作区 JAR | 未重新执行 Maven clean verify；189 份源码/配置与独立构建目录按 LF 规范化逐字节一致，dist/JAR 静态 3 文件逐字节一致；验证 JAR 被复制为新文件名，不覆盖旧产物 |
| 浏览器 E2E | **未运行**：8 项/3 文件仅收集清单，实际 0 项；18080/18081/Edge 未启动，无截图/报告 |
| Docker 实际构建/运行、线上 CI、真实平台、24 小时运行观测 | 未验证 |

浏览器执行命令两次在启动前被隔离工具拒绝；即使一次已获得明确许可，也没有绕过或改变权限设置。因此不能将历史 7 项浏览器结果或本轮 `--list` 结果作为当前 E2E 通过。文档完整交付不等于 P6 浏览器验收完成；整体 P1～P7 不应无条件标为全部通过。

## 13. 2026-10-10 正式工作区前端与只读请求摘要

本次按用户认可的完整 HTML 工作区原型落实五个正式 Vue 3 / JavaScript 页面，仍使用既有 Element Plus、Vue Router 与同源 API，不引用原型运行时资源、不使用 iframe、不新增生产依赖。统一纸面背景、森林绿、浅黄绿、SVG 图标、窄主导航与移动底部导航；仅平台页有平台侧栏。原型的示例时钟、模拟请求/队列、场景工具、原型说明与专用备份格式均不进入产品。

- 概览保留服务端准确总数、独立日标记统计、需要关注的请求、下一计划、最近执行和真实持久队列；可定位请求、查看/取消批次。不实现后端不支持的趋势统计。
- 平台页显示账号分组、请求方法/安全主机/修订、今日状态、最近执行摘要；请求详情、执行详情、计划编辑与模板管理均连接真实接口。启停与凭证暂停独立，主动重跑仍受后端门禁限制。
- 计划页支持搜索、启停筛选、独立开关、编辑和跨页定位；设置按原型顶部分类组织执行偏好、网络代理、配置备份、关于系统，保留草稿离开提醒、版本冲突和预览后的明确整体替换。
- 执行记录的平台、状态、来源和分页在服务端筛选；文本搜索明确只搜索当前页，未增加不存在的全局搜索接口。响应详情仅按需 `no-store` 获取，活动详情仅轮询批次元数据，终态正文不重复下载；HTML 纯文本，关闭/卸载清理并阻止迟到响应。

### 唯一后端契约扩展

`GET /api/platforms` 嵌套 `RequestVo` 新增：

- `todayState: "none" | "completed" | "pending"`：注入 `Clock`，按该平台 `Asia/Shanghai` 或 `UTC` 业务日期查询 `daily_completions` / `request_day_states`，完成标记优先；不从最近日志推断，清理日志不会抹去日状态。
- `lastRun: null | { id, status, finishedAt, createdAt, httpStatus, durationMs }`：只读最近执行摘要，没有原始请求、正文或摘要消息；日志已清理时可以为 null，日标记仍保留。

专用 `RequestStatusQueryMapper.xml` 批量装配状态和摘要，平台树共四次批量查询，无客户端 N+1，四次读取绑定同一短只读事务；不修改数据库 schema、队列或发送事务。每请求使用既有 `items_request` 索引范围关联 top-1，不物化全历史窗口。最近执行按批次 UTC 秒与右补九位的纳秒小数精确降序，执行项 `rowid DESC` 打破同刻并列，避免可变精度 `Instant.toString()` 的字符串排序错误；既有 `RunRecordMapper.queryLogs` 的元数据列表同步采用相同排序，保证最近记录与请求摘要一致，不改变列表字段或接口。仍需扫描各请求保留历史，不宣称 O(请求数)，也未完成 365 天容量压测。其余 HTTP 路径、BO/VO、明文 `signdesk-plain-v1` 备份和执行语义不变；规则编辑保留真实契约支持的四类严格 JSON／文本规则与试算，不引入原型示例中的额外判断能力。
