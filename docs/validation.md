# SignDesk 验证记录

本文按日期保留历史验证原文，旧结果不能代替当前实现，也不能把历史浏览器通过数当成本轮 E2E 通过。最新验证见末尾 **2026-10-14 浏览器标签图标**：后端 154/154、前端单元 50/50、新生产包图标/登录/导航/日期相关浏览器回归 10/10，通过，非全站回归。此前日期区间包 19/19、确认框限宽包 22/22、关闭包 18/18、间距包 11/11、单弹框包全量 68/68 属于各自产物，不作为本轮新包全量结果；主目录清理失败、隔离发布和手机日历截图修复过程保留在各节。

历史日期：2026-10-08（北京时间）。各节只描述当时实际执行的检查。

## 后端

Java 21、Maven 3.9.11，在独立临时目录中使用真实 SQLite。41 项测试，0 失败、0 错误、0 跳过。

| 测试类 | 数量 | 验证内容 |
|---|---:|---|
| CurlParserTest | 18 | Bash/CMD、原始编码、重复参数和请求头、Body、拒绝文件／shell／不支持选项 |
| HutoolExecutorTest | 5 | 本地回显的实际 URL／Headers／Cookie／Body、账号隔离、重定向、期限与分类 |
| ResultRulesTest | 2 | JSON 类型与规则优先级、未知响应 |
| TlsVerificationTest | 1 | 不可信证书被拒绝；可信但主机名不符被拒绝；可信且主机名匹配成功 |
| ScheduleSpecTest | 3 | 北京时间／UTC、指定星期、生效时间、跨日与补执行窗口 |
| StorageQueueIntegrationTest | 10 | 加密与版本冻结、手动幂等、日志清理后的完成标记、计划变更后的去重、取消、重启、过期暂停、迁移备份、错误密码、丢钥拒绝覆盖 |
| WebContractIntegrationTest | 2 | 使用实际 HTTP 服务预览 cURL；备份可选字段、导入预览与明确替换 |

测试使用本地回显 HTTP 服务和临时测试证书，不发送真实平台请求，也不使用用户凭证。cURL 预览不发送请求。

## 浏览器

Playwright / Chromium 147，2 项浏览器测试通过。运行打包后的真实 Spring Boot 服务，不使用模拟前端 API。

- 新增平台和账号，粘贴完整 cURL，预览 Headers / Body、试算规则并保存。
- 页面手动执行并查询结果；本地回显确认重复 Query、百分号编码、POST、Cookie、设备头与 JSON Body 完整保留。
- 查看日志详情、更新 cURL 到版本 2、明确重新执行。
- 启用平台 09:00 计划，核对后端保存的配置。
- 普通配置下载不含请求信息；完整备份导出后不含明文凭证，并使用密码成功预览恢复。
- 页面没有未处理的 JavaScript 异常；1440 像素桌面和 390 像素窄屏检查与截图，窄屏页面无整体横向溢出。
- 跨站写入返回 403，表单编码写入返回 415，管理接口禁用缓存；正常同来源使用不要求登录。

## 构建

- 前端依赖按 package-lock.json 安装，Vite 生产构建成功。
- 后端测试与 Spring Boot 单 JAR 打包成功；最终 JAR 包含本次最新前端静态资源。
- 源码同时提供 Dockerfile、Compose 与 GitHub Actions 构建配置。
- 前端主 JavaScript 包约 1.05 MB，gzip 约 338 kB。Vite 提示大于 500 kB，未进行专门的加载性能优化。

## 接口规则模板增量验证

2026-10-08，在 Windows 11、Java 21.0.2、Maven 3.9.9、Node 22.17.0 环境完成以下检查（上文保留原始交付记录）：

- 后端全量 **54 项测试通过**，0 失败、0 错误、0 跳过；包括 V3 迁移、模板 CRUD／平台归属／乐观锁、雪花字符串 ID、独立请求与排队规则快照、普通和加密备份恢复及非法备份拒绝。
- 前端规则单元测试 **3 项通过**，覆盖明确的 null 分类、JSON 标量类型、模板草稿隔离和无效输入。
- 真实浏览器 **5 项通过**，使用系统 Edge（Chromium）和隔离的后端／本地 HTTP fixture。验证多模板管理、已有请求保存为模板、选择模板不修改 cURL、覆盖确认、平台隔离、关闭重开、迟到响应隔离、加载失败后手动添加，以及原有完整管理／执行／备份／代理流程。
- 检查了模板管理的 1440 像素桌面与 390 像素窄屏截图，窄屏没有整体横向溢出。
- Vite 生产构建和包含本轮新前端资源的单 JAR 打包通过，产物为 `backend/target/signdesk-0.1.0.jar`。大包提示仍存在，未在本轮进行拆包优化。
- `npm ci` 被执行环境权限拦截，本轮前端测试与构建使用已有依赖，没有重新安装依赖。

## 执行响应体增量验证

2026-10-08，在同一 Windows 11 / Java 21.0.2 / Maven 3.9.9 / Node 22.17.0 环境验证：

- 后端全量 **66 项通过**，0 失败、0 错误、0 跳过。覆盖 V4 升级、成功／HTTP 错误／空响应、Hutool 合成错误内容排除、读取超时的部分字节、恰好 1 MiB 与超限前缀、停止重定向的响应、字符集与二进制保留、每次执行独立加密、AAD 防串用、丢钥拒绝替换、列表隔离、禁止缓存以及响应随记录清理而周期标记保留。
- 前端规则单元测试 **3 项通过**，生产构建通过；沿用现有依赖，没有重新执行 `npm ci`。
- 浏览器全量 **7 项通过**，在原有模板／执行／备份／代理回归之外验证响应详情：纯文本展示 HTML、不执行脚本、空／错误／部分／超大／Base64 响应、完成后不重复下载正文、关闭清理与迟到响应隔离。
- 响应详情专项 **2 项再次通过**，等抽屉过渡结束后检查桌面 760px 和窄屏 390px 的实际布局与截图，未发现整体横向溢出。
- Maven verify 和包含最新前端的单 JAR 打包通过。所有网络请求仍限于测试允许的本地 fixture，没有使用真实账号凭证或访问真实平台。

## 验证边界

本轮已完成 Windows 11 上的自动化后端和浏览器检查，没有进行真实签到平台联调、Docker 实机运行或 24 小时资源与稳定性测量。GitHub Actions 配置不等于已经通过远端 CI；远端结果以仓库 Actions 为准。

可重放范围见 README 的 cURL 支持表。动态签名、短期凭证和验证码需要用户更新请求或进一步适配。服务器需持续运行才能定时执行。

## 2026-10-09 后端重构及交付验证

### 环境与基线

Windows 11、JDK 21.0.2、Maven 3.9.9、Node 22.17、npm 10.9.2。重构前的真实基线为后端 66 项、前端单元 3 项，均通过。P1 后端 73 项全通过；主工作区 P1 版本也实际验证过 73 项通过。以下结果仅对应本节标明的最终重构实现，不倒灌到 2026-10-08 的历史记录。

### 最终后端与前端测试

最终后端 owner 测试 **110/110**，0 失败、0 错误、0 跳过。类计数如下：

| 测试类 | 数量 |
|---|---:|
| BackendArchitectureTest | 5 |
| BackendLayeringTest | 4 |
| WebContractIntegrationTest | 9 |
| CurlParserTest | 18 |
| HutoolExecutorTest | 12 |
| ResultRulesTest | 2 |
| TlsVerificationTest | 1 |
| StorageQueueIntegrationTest | 39 |
| ScheduleSpecTest | 3 |
| BackupValidationTest | 5 |
| DatabaseInitializerTest | 7 |
| AutomaticEnqueueRaceIntegrationTest | 5 |
| **合计** | **110** |

独立验证 worktree `agent-a2230dd6f570fc554` 实际完成 fresh `npm ci`、前端单元 **8/8**、Vite production build、`mvn -f backend/pom.xml clean verify`（110/110）及 `node scripts/build.mjs` 完整发布；最终单 JAR 成功生成。命令执行环境为上述 Windows/JDK/Maven/Node 版本。前端现有 Vite 大包提示仍存在；本轮未做 bundle 拆分或性能优化。

主工作区前端依赖已存在，实际运行前端 unit **8/8** 和 `npm run build` 均成功。主工作区的自定义“清理 static + Maven clean verify”组合命令被工具以 `Irreversible Local Destruction` 为由在执行前拒绝，未运行该命令；因此没有主工作区 Maven clean verify 结果，不能将独立验证结果描述成主目录重新构建。

### 单 JAR 对齐与本地副本

为避免删除/覆盖主工作区已有生成物，使用非破坏性交付核对：

- 主工作区 **189 份源码/配置**与独立验证构建目录在 LF 规范化后逐字节一致。
- `frontend/dist` 与 JAR 内静态资源的 3 个文件逐字节一致。
- 单 JAR 内含本轮最新资源 `index-C8RaL_L9.js` 与 `index-Dt2F9Ax3.css`；核对未发现旧业务/应用数据加密类或旧补丁条目，也没有相关密钥配置。
- 将已验证 JAR 复制到主工作区的新文件 `backend/target/signdesk-0.1.0-refactored.jar`，未删除或覆盖旧生成物。大小 **40,854,484 bytes**，SHA-256：

```text
d30c955c167734f15645a041da2fa0e90e0f1cc5ad8552a77904e25102963c54
```

这是独立验证产物的本地副本，不是主工作区再次打包；常规构建文件名仍为 `backend/target/signdesk-0.1.0.jar`。README 分别记录了常规构建和此本地副本的运行命令。运行新实现应选择全新 `SIGNDESK_DATA_DIR`，不要把既有数据库目录交给新 schema。

### 架构审阅、竞态修复与警告

静态质量和反模式审阅通过。审阅/验证过程中发现并实际复现了维护/导入与旧计划扫描并发的竞态；修正后增加 5 项确定性 `AutomaticEnqueueRaceIntegrationTest`，验证维护开始后旧 generation 失效，且导入恢复相同 revision/`effectiveFrom` 也拒绝过期扫描。该修复不改变自动 occurrence 键（平台＋UTC 时刻）。

验证中有以下非阻断警告/日志：Vite 提示 JS bundle 超过 500 kB（JS **1,063.77 kB**）；`ResultRules` deprecated、`WebContract` unchecked、Mockito 动态 agent 提示。一次 `Queue operation failed (UncategorizedSQLException)` ERROR 是预期故障注入，日志只包含异常类型；该测试通过。未测 coverage 未作为通过项报告。

### 浏览器 E2E：未运行，验收阻塞

Playwright 只正确完成 `--list` 清单收集：识别 **8 项、3 个测试文件**，这不是测试执行；本轮实际 E2E 为 **0 项运行**。Edge 环境启动命令两次在执行前被隔离工具拒绝（第二次已有明确许可仍被拒）；18080 后端、18081 本地 fixture 与 Edge 均未启动，没有截图或测试报告。拒绝不是测试失败，也不构成通过；未尝试绕过限制或改动权限设置。

历史章节所记的 7 项浏览器通过属于旧实现，不能替代当前 8 项 E2E。当前会话的人工执行入口见本文末尾“当前会话的人工 E2E 入口”；是否运行由用户决定，执行后须以实际输出更新此节。

### 尚未验证的范围与本轮边界

- Docker 实际构建/运行、线上 GitHub Actions CI、真实签到平台联调及 24 小时运行观测均未验证。
- 后端110/110、前端8/8、独立全量构建已通过；浏览器E2E未通过也未失败，而是未运行。
- 主工作区 Maven clean verify 未运行，详见上文；独立工作树 `node scripts/build.mjs` 全量发布成功。
- 文档收尾任务只修改 Markdown；本轮没有重新运行产品测试，没有修改业务源码、配置或脚本，没有 stage、commit 或 push。
- P1～P5 实施完成，P6 自动化/发布验证完成但浏览器 E2E 阻塞，P7 文档已收尾；不得据此宣称 P1～P7 全部无条件完成。

## 当前会话的人工 E2E 入口

如用户决定在本机会话内尝试运行，请在 Claude Code 输入框完整粘贴以下命令：

```text
! export JAVA_HOME='D:/Install/jdk-21.0.2'; export SIGNDESK_TEST_BROWSER='C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'; export PATH="/d/Install/jdk-21.0.2/bin:/d/Install/IntelliJ IDEA 2024.3.6/plugins/maven/lib/maven3/bin:$PATH"; node scripts/build.mjs && npm --prefix frontend run test:e2e
```

`!` 是 Claude Code 输入框的 shell 命令前缀；在普通 Git Bash 终端执行时去掉最前面的 `!`。这些 `export` 只为本次 shell 命令设置环境变量，不修改权限配置。命令先运行完整 `node scripts/build.mjs`（重新构建标准 JAR），成功后再运行 E2E；测试脚本使用隔离的 18080 后端、18081 本地 fixture 和临时数据目录。

是否执行由用户决定。本节写入时该命令尚无实际运行输出；不能承诺测试通过，也不能把命令示例计作已运行或已验收。执行后应根据真实输出更新浏览器 E2E 状态。

## 2026-10-10 正式工作区前端：源码先行交付

用户在本轮明确选择“先把代码应用上，我自行测试功能以及前端页面是否和原型一样”。因此按认可的完整工作区原型完成五页正式前端、最小只读请求摘要扩展和配套测试后，先交付源码；**不以浏览器 E2E 或截图复核作为源码集成前置条件，也不宣称自动验收完成**。历史章节与下面的最新结果必须分开理解。

### 实现与接口

- 今日概览、平台与账号、定时计划、执行记录、设置与备份采用统一纸面／森林绿／浅黄绿、SVG 图标、窄主导航、平台专属侧栏及手机底部导航；实际业务均连接既有 API，不包含原型模拟场景或假请求。
- 请求、执行、批次详情及增改、模板、规则试算、筛选分页、跨页定位连接真实服务。详情按需读取、纯文本展示、关闭／卸载清理、防迟到响应；活动执行仅轮询批次元数据，终态不重复下载响应正文。版本冲突、独立启停与凭证暂停、整体替换前预览与明确确认均保留。
- 唯一新增 HTTP 契约为 `GET /api/platforms` 的嵌套请求字段 `todayState` 与可空 `lastRun` 六字段安全摘要，无正文。状态来自平台业务日期的独立日标记，不从日志反推。四次批量查询使用短只读事务；最近摘要与既有执行列表同步修正变长 UTC 时间戳的秒／纳秒排序，不修改 schema 或队列语义。详细字段与容量边界见 architecture.md 第 13 节。

### 本轮实际验证及最新失败

- 最新完整发布链实际执行 fresh `npm ci`、前端单元 **34/34 通过**、Vite 生产构建通过；仍有主 JS 包超过 500 kB 的警告，未进行拆包优化。
- 最新后端在 Java 21.0.2／Maven 3.9.9 下实际运行 **115 项：114 通过、1 失败、0 错误、0 跳过**。唯一失败为 `TlsVerificationTest.verifiesBothTrustChainAndHostname` 第 60 行的 `assertTrue`；未完成根因定位，未删除／禁用断言，也未降低 TLS 校验。此前隔离验证的 115/115 仅是此前轮次结果，不能替代本次失败。
- 同轮 `StorageQueueIntegrationTest` **44/44 通过**，包括日标记、摘要无正文、UTC 精确排序、一致读事务及查询计划回归；日志出现一次 `Queue operation failed (UncategorizedSQLException)`，不得当成测试失败计数或新的完整构建成功证明。
- `node scripts/build.mjs` 因上述后端失败返回非零，**最新完整发布未成功，没有可作为本轮交付的最新成功 JAR**。后续 `&&` 的浏览器命令未执行：当前正式 E2E 实际运行 **0 项**，无正式页面桌面／手机截图，像素对照、移动布局和键盘／焦点实测仍待用户或后续自动回归。
- Chromium 无头浏览器已在隔离缓存实际安装；原型参考截图只证明原型能打开，不等于正式前端验收。Edge 启动命令曾被隔离工具拒绝，浏览器安装曾遇锁和下载超时；均未修改权限或删除用户文件绕过。
- 完整链已经退出，没有继续运行的浏览器／测试后台服务；交付时另行核实 18080／18081 端口。测试只使用隔离临时数据和允许的本地 fixture，未使用真实平台凭证。

### 集成与用户自测入口

本轮仅将任务源码、必要测试及相关事实文档应用到主工作区，集成前检查启动基线 SHA-256 与并行新增路径，复制前再核对并逐文件比较；发现冲突应停止而非覆盖。不复制生成 static、target、dist、node_modules、测试报告或日志，不复制旧 JAR 冒充新产物；不修改原型、既有数据库、配置或用户日志，不提交或推送。

用户可在主工作区使用 Java 21／Maven 3.9+／Node 22.12+ 环境执行 `node scripts/build.mjs`。只有完整命令成功后，标准产物 `backend/target/signdesk-0.1.0.jar` 才可作为此次源码构建运行；失败时不要用残留旧 JAR 验收新页面。开发模式可分别执行 `mvn -f backend/pom.xml spring-boot:run` 与在 frontend 执行 `npm ci && npm run dev`，Vite 使用既有后端代理。后端运行必须显式设置全新、隔离的 `SIGNDESK_DATA_DIR`，不得指向需要保留的旧数据库目录。

仍未验证：正式浏览器 E2E、正式桌面／手机截图及与原型的人工复核、TLS 最新失败根因及修复后完整发布、365 天容量压测、真实平台、Docker 实际构建／运行、线上 CI 与 24 小时观测。

### 后续用户验收与两项增量修复（未编译、未重跑测试）

用户随后明确反馈：已自行验收当前界面，与原型存在部分差异但可以接受，不再要求原型对照检查；只需处理 TLS 失败及新增平台等弹窗操作按钮无间距的问题，改后由用户自行编译及验收。**这是用户人工验收反馈，不是浏览器 E2E 通过记录。** 上面的 114/115、发布失败及无正式截图是修复前最后一次自动执行的历史事实，不能抹去或改成最新全通过。

- 依据现存 Surefire 失败报告与当时源码，第 60 行失败的是 `assertTrue(process.waitFor(15, TimeUnit.SECONDS))`：为临时 RSA 证书启动的 `keytool` 子进程超过 fixture 的 15 秒准备上限，尚未执行 HTTPS 请求或证书／主机名校验。日志不足以进一步区分机器负载与进程启动、密钥生成耗时，不将其误报为生产 TLS 校验失效。
- 最小修复仅在 `TlsVerificationTest`：单次证书准备等待改为有界 60 秒，并增加明确的 fixture 阶段失败信息；超时或中断时终止该测试子进程，确认进程结束后清理本次新建的临时 fixture。仍严格要求子进程成功退出，不增加重试；不可信证书、主机名不符、可信正确主机以及代理不得绕过 TLS 的全部原断言和 HTTP 超时保持不变，生产传输代码未修改。
- 弹窗间距根因为全局相邻按钮 margin 已归零，但若干 footer 仍依靠内联排布、没有 gap。全局样式为 Dialog、Drawer 和 MessageBox 操作区补统一 `flex + gap` 与换行，嵌套抽屉按钮组及请求向导操作区同样支持换行，并仅在 footer 内清除默认按钮左 margin，避免双重间距。覆盖新增／编辑平台、添加／编辑账号、计划、模板、备份整体替换、请求向导、三类详情抽屉以及确认消息框，不改表格或主页面操作布局。
- 集成前再次检查主目录，原交付 44 个文件无后续变化，三个待修复文件也与读取版本一致。共享路径直接编辑被隔离工具拒绝后，遵循其要求在匹配当前主目录的隔离副本完成补丁，仅增量应用 `TlsVerificationTest.java`、`frontend/src/style.css` 和本文三文件；复制前重新校验哈希，复制后逐字节比较，不整批覆盖先前 44 文件。
- **按用户要求本轮未编译、未执行任何测试、未启动服务／E2E、未安装依赖、未生成或交付 JAR。** 修复效果及 TLS 完整用例是否通过由用户随后编译和验收；不宣称 115/115 或新增自动验证通过。不改权限，不修改用户数据库、配置、日志或原型，不提交或推送。

## 2026-10-10 Maven Jib 镜像 tar

用户要求在本地通过 Maven/Jib 生成镜像文件，上传 Linux 后用 Docker 启动；不推送镜像仓库。新增 opt-in `jib` profile、隔离前端构建脚本、仅使用已导入镜像的 `compose.image.yml` 和 [部署说明](docker-jib.md)，保留普通构建与 Dockerfile 路径。生产镜像仍由后端在 `10500` 同源提供前端和 API，不启动 Vite。

- 环境：JDK 21.0.2、Maven 3.9.7、Node 22.17.0／npm 10.9.2；Jib Maven 插件 3.5.2。默认 Linux/amd64，可显式生成 arm64。
- 首次构建因运行中的 Vite 占用 Windows 原生依赖，`npm ci` 报 `EPERM` 后失败。已改为在 `backend/target/jib/frontend-build` 复制明确的构建输入并执行锁定依赖安装、测试与构建，不复制 `.env`、数据库、日志或旧产物，不共用开发服务的依赖目录。
- 用户停止本地服务后，原前端目录实际重新执行 `npm ci` 成功（62 个包），恢复了首次安装中断后的开发依赖。
- 上一轮后台构建虽完成前端 34/34、后端 115/115（含 TLS），但与清理 `target` 的新命令重叠，出现 `Unable to find main class`／`Failed to delete ... target`，该轮整体构建失败，不能算镜像生成成功。确认相关旧构建已退出后，仅运行一个完整重建。
- POM XML 与 image-only Compose YAML 静态解析通过；核对无 `build`、`pull_policy: never`、数据挂载源禁止自动创建。`git diff --check` 和前端构建脚本语法检查通过；这些不等于 Docker 运行验证。
- 中间轮次完整测试通过，但 JAR 内容检查发现普通 `target/classes` 混入两份旧前端资源（新首页及新资源内容正确，另有旧文件）。已将整个 Jib profile 的构建输出隔离到 `backend/target/jib`，避免与 IDE 普通编译输出共用。该中间轮次已停止，不作为镜像交付结果。
- 最终单次完整重建命令：`mvn -f backend/pom.xml -Pjib clean verify`。直连 Docker Hub 超时，查询到 Windows 已启用的系统代理后，仅为该 Maven 进程临时设置 `MAVEN_OPTS` 的 HTTP/HTTPS proxyHost／proxyPort，仍保留 TLS 校验；代理地址未写入 POM、镜像或权限配置。最终于 **2026-10-10 22:50:45 +08:00** 返回 **BUILD SUCCESS**，耗时 **3 分 2 秒**：前端 **34/34**、后端 **115/115**（0 失败、0 错误、0 跳过，含 TLS）及 Vite 生产构建通过。
- 默认镜像 tar：`backend/target/jib/signdesk-0.1.0-linux-amd64.tar`，**141,822,464 bytes**，SHA-256 `c6de62e8a28d285481ba313d848024cce13fa58e487015bdbab6cd2ae6081e86`。只生成本地 tar，不调用 Docker daemon、不推送镜像仓库。
- 对 Docker tar 进行只读检查：标签 `signdesk:0.1.0`、平台 `linux/amd64`、运行用户 `10001:10001`、工作目录 `/app`、端口 `10500`、数据目录 `/app/data`、显式 JVM 参数与应用入口正确；逐字节核对镜像内 3 份静态文件与本轮前端产物完全一致，没有多余旧资源。应用层不含数据库、实例锁、`.env`、`node_modules` 或 `/app/data` 下文件；基础层包含 Compose 健康检查所需 `curl`。
- 同轮 JAR：`backend/target/jib/signdesk-0.1.0.jar`，**40,887,867 bytes**，SHA-256 `b12ee2393d23a43138181ae6bb017cc20f08b91eb20ccf11513b1d967a38b313`；可执行入口存在，JAR 内静态文件同样与最新前端逐字节一致。
- 仍有 Vite 主 JS 超过 500 kB、Mockito 动态 agent／编译器弃用等警告；Jib 基础镜像使用标签而非固定 digest，因此不宣称跨时间完全可重现。此次解析的基础镜像摘要为 `sha256:000fd431958bc81a24abe1e8e5f0f0fd3ae365a594bd50aadb20696805f9408c`。这些警告不构成测试失败，也不等于容器验收完成。
- 本地没有 Docker daemon，Linux 容器启动、数据目录权限、健康检查、容器内 TLS、ARM64 实际构建／运行、浏览器 E2E 与线上 CI 仍待验证。不启动或使用用户的真实数据，不提交或推送。

## 2026-10-11 配置文件管理员登录

按用户要求增加单管理员登录，默认 `admin` / `123456` 配置在 `application.yml`；没有用户表、注册、验证码或新增认证依赖。使用服务端 Session，管理 API 读写均校验登录，公开探针改为 `/api/health`。数据库基线、备份格式、队列和发送语义不改动。

### 构建和后端

- 使用 JDK **21.0.2**、IDE 附带 Maven **3.9.9**、Node **22.17.0** / npm **10.9.2**。当前 shell 原本指向 Java 8 且 PATH 没有 Maven，本轮仅给构建进程临时设置正确工具路径，没有修改用户全局环境。
- 主目录前端单元测试 **42/42** 与 Vite 生产构建通过。新增 8 项测试覆盖同源 Cookie、登录失败与业务 401 区别、安全返回目标、注销/重新登录后的迟到请求隔离和断网边界。
- 首次主目录 `mvn test` 输出 **126 项、1 项失败**：`AutomaticEnqueueRaceIntegrationTest.ordinaryPlanEditInvalidatesOldScanButANewCurrentOccurrenceCanRun` 的旧扫描零入队断言出现 1；之后任务达到 120 秒限时被停止。新增认证及既有 Web 契约测试均通过。该失败不能抹除，也不能将首次执行计为成功。
- 上述调度竞态测试独立运行 **5/5** 通过。检查测试的首轮定时扫描与 latch 并发边界后，未修改生产调度或降低断言；首次偶发失败原因尚未确定，仍是历史回归稳定性待核查项。
- 为避免 `npm ci` 删除运行中开发服务依赖或清理占用的 `target`，在仅复制源码/构建输入的临时目录执行仓库的 `node scripts/build.mjs`。初版隔离全发布 **126/126 后端、42/42 前端** 通过。
- 随后补充安全的配置启动校验：不用可能回显密码值的配置 Bean Validation 失败分析，改为固定安全错误；新增 2 项配置边界测试。最终临时目录 `C:\Users\PC\AppData\Local\Temp\signdesk-login-final-rZK6kA` 的完整发布 **128/128 后端、42/42 前端**、Vite build、Maven clean verify 均通过（0 失败、0 错误、0 跳过）。
- 最终构建副本与当前主目录 **203 份源码/测试逐字节一致**。ZIP 只读核对 JAR 含 `AuthController` 与可执行入口，静态资源恰好 3 份，与对应 dist 逐字节一致，无多余旧 bundle。主目录生成的 `src/main/resources/static` 已更新为这 3 份资源，不提交生成文件。
- 验证产物复制为新的 `backend/target/signdesk-0.1.0-login.jar`，未覆盖旧 JAR，**40,904,896 bytes**，SHA-256 `3da19f9569c8cc1aec198e3a71cdd6fdc9b2632a70e09d5ce880837dd44ae09d`。旧 Docker tar/镜像没有被重新构建，不代表包含登录功能。

### 浏览器

- 实际使用已安装的 Chromium，运行 **18080** 隔离后端、**18081** 本地 HTTP fixture 与新临时 SQLite，没有访问用户默认数据目录或真实平台。`SIGNDESK_TEST_JAR` 允许明确指定本轮验证 JAR，不误测旧默认产物。业务 E2E fixture 通过真实登录接口分别认证浏览器、API 和清理 Context，不禁用鉴权，不保存认证状态文件。
- 首次启动 55 项全量 E2E 后出现失败，并在排查过程中主动停止；尚未完成全量成功验收。两项登录测试最后一步回退到 `about:blank`，原因是登录与退出都正确使用 replace、测试没有建立可回退的业务历史；已补充真实导航历史，不修改产品来保留敏感页面。移动端空记录表因隐藏表头而没有可见尺寸，旧布局断言不成立；已校验空态或非空表。另有请求菜单 Escape 后仍遮挡点击、账号/请求启停及内联计划等旧交互用例失败，不能将这些或中止后的 0ms 条目视为通过。
- 使用最终 JAR 实际复跑 `auth.spec.js navigation.spec.js layout.spec.js`，**8/8 全通过**，耗时约 **1.1 分钟**。覆盖桌面与手机的密码错误、显隐、登录、正确返回、刷新、退出、返回历史不绕过、HttpOnly/无浏览器凭证持久化；真实会话失效时抽屉原文清理、未保存设置确认关闭、安全登录重定向；五路由导航、暂停/连接提示与响应式边界。
- 请求维护/响应详情的首次定向回归为 **1 通过、2 失败**：旧断言使用“今日未执行”，当前已声明的日标记文案为“今日未完成”；响应测试用全局关闭按钮匹配到执行详情与隐藏批次详情两处。只修正测试文案/定位及菜单关闭前置，不改变产品业务状态或降低 HTTP/数据库断言。使用同一最终 JAR 复跑后 **3/3 通过**（约 45 秒），覆盖请求版本/规则维护、删除确认、合成停用状态，以及原文、错误、空、二进制、截断、部分响应和 HTML 纯文本展示。
- 最终明确通过的是上述 **8 项登录/导航 + 3 项定向业务测试**，不是全量 55 项通过；未完成或仍失败的账号/请求启停、内联计划等完整业务 E2E 仍需核查。Docker 实际运行、线上 CI、真实平台和 24 小时观测也仍需另行验收。

`git diff --check` 与新增 E2E / 服务脚本语法检查通过，只有既有 LF/CRLF 提示。保留 Vite 大 bundle、Mockito 动态 agent、Java 弃用/unchecked 等警告。计划阶段权限曾拒绝技能、只读 Bash 和计划写入，直到用户要求重试且写入成功才进入已批准实施；未改权限绕过限制。没有提交、推送、修改参考仓库或自动重启用户服务；除用户明确要求修改的源码配置外，没有修改/删除用户已有数据库、本地配置或日志。

## 2026-10-11 结果规则与模板批量关联应用

### 实现与构建

- 新增默认关闭的反向失败选项；仅纯 JSON 成功字段有效且同类型不等时判 failed，缺字段/类型不符/不完整响应继续 unknown。已完成优先于通用失败，401/非 2xx、凭证修订、日标记和不自动重试的边界保留。
- 模板管理提供“选择请求应用并关联”和“应用到全部已关联请求”，确认覆盖范围与改绑后只更新规则/关联/配置版本。模板修改不自动同步，手工编辑可解除关联，删除模板保留请求规则。预览与应用校验模板/请求版本、平台与完整关联集合，失败整批回滚；无 HTTP、凭证、启停、日标记或已排队规则改动。
- 关联使用现有请求配置 JSON 的向后读取兼容封装；数据库 V1/schema 未改变，没有迁移用户数据库。两种备份保留关联并验证模板存在/同平台。新增 RequestRuleConfig、BO/VO、接口与共享前端弹窗，没有增加生产依赖。
- 前期仅规则增量的主目录后端 **140/140** 通过。加入模板应用后的首次全量为 **149 项、148 通过、1 失败**：仍是旧 `AutomaticEnqueueRaceIntegrationTest.ordinaryPlanEditInvalidatesOldScanButANewCurrentOccurrenceCanRun` 的零入队/发送断言。发现夹具只将 fixedDelay 调大，不能阻止首次定时 scan；修正为只取消该测试上下文的后台 scan、保留真实 dispatch 与手动 latch 扫描，并增加恢复前零入队前置断言。未修改生产调度或降低不变量断言；随后独立竞态 **5/5** 与完整隔离发布 **149/149** 通过。
- 初版完整构建在 `C:\Users\PC\AppData\Local\Temp\signdesk-rules-templates-Ckn2KA`，浏览器回归两项产品修正后的最终构建在全新 `C:\Users\PC\AppData\Local\Temp\signdesk-rules-templates-final-wPd2xi`；两次都实际执行 `node scripts/build.mjs`：fresh npm ci、前端 **46/46**（0 失败）、Vite build、后端 **149/149**（0 失败/错误/跳过）、Maven clean verify 和单 JAR 打包全部通过。使用 Java 21.0.2、Maven 3.9.9、Node 22.17.0/npm 10.9.2，仅对进程临时设置环境。
- 主目录与最终隔离副本的 **221 份生产源码/Java 测试/前端单元测试逐字节一致**（排除旧的生成 static）；只读 ZIP 核查 JAR 的 **3 份 static** 与 fresh dist 完全一致，包含 4 份新增生产 BO/VO/配置类。测试期间没有替换主目录旧 JAR 或旧 static。
- 初版 JAR **40,925,342 bytes**、SHA-256 `ce5532b1b409da87987cdbdad3a90506dd21661800b8287e168b4bd4575164df`；两项产品修正后的最终 JAR **40,925,338 bytes**、SHA-256 `c1260e2419e5d416f296c8d873ffb8bd1e3c07f1a69c7434fc0d7783e80007c8`。已使用 exclusive copy 复制为主目录的新文件 `backend/target/signdesk-0.1.0-rules-templates.jar`，与隔离构建产物逐字节一致；未覆盖默认、登录、重构 JAR 或运行中服务，该包在 git 忽略范围内。

### 浏览器验证过程

- 使用真实 Session 登录、18080 隔离后端、18081 本地 fixture、新临时 SQLite，指定上述本轮 JAR；没有访问现有数据目录或真实平台。
- 首轮 15 项定向用例在浏览器启动阶段失败：缺少默认 chromium_headless_shell，没有进入浏览器验收。随后通过项目已有 `SIGNDESK_TEST_BROWSER` 使用已安装的 `chromium-1217/chrome-win64/chrome.exe`，没有下载浏览器或修改权限配置。
- 真实浏览器首轮定向执行因新增用例点击 Element Plus 的隐藏 switch input 超时而中断，不计作完整通过；改为点击可见开关并保留 aria-checked/业务结果断言。进一步的 5 项新增用例为 **3 通过、2 失败**：模板桌面/手机全流程及版本冲突通过，规则试算标签过渡动画短暂存在旧/新两个节点，严格定位报错。等待旧结果实际清除再试算，未修改分类逻辑、强制点击或削弱数据库/HTTP 断言。
- 首次全量浏览器 E2E 实际执行 **60 项：48 通过、12 失败**（约 10.3 分钟，exit 1），其中本轮新增规则 2 项和模板批量应用 3 项全部通过。失败包括旧菜单关闭前置、日状态文案、平台查询参数、代理/整体替换隐藏控件、详情/分页过宽定位，以及保存为模板来源未被监听和批次详情使用错误名称字段。后两项做最小产品修正，测试修正保留原业务/数据库/HTTP/确认断言，不将首次全量记为通过。
- 最终 JAR 的下一轮全量为 **58 通过、2 失败**（约 8.3 分钟，exit 1）：两项产品问题、日状态/计划/代理/菜单/分页及新增 5 项通过，剩余失败均在整体替换确认的 E2E 复选框嵌套 `filter(has)` 定位，未进入对应确认后断言。修正为在确切确认框内点击唯一可见 checkbox wrapper，仍校验其 checked、未勾选禁用/取消无提交/确认请求体。
- 最后一轮使用最终 JAR 与修正后的真实登录 fixture 实际全量执行 **60/60 通过**（约 **5.8 分钟**，exit 0，0 失败；非 `--list`）。包括新增规则桌面/手机 2 项、模板手选/全部关联/冲突 3 项，以及全部原有登录、平台/请求启停、计划、设置/代理、两种备份明确替换、响应详情、队列取消和分页流程。未删除/跳过失败测试，没有关闭鉴权、同源/TLS 检查或 HTTP/数据库断言；最终 18080/18081 已核查释放。
- 已查看本轮规则与模板选择的 1440px/390px 截图；沿用既有纸面/森林绿、窄屏规则与请求列表换行。长表单与确认操作通过滚动访问，桌面/手机实际用例的无横向溢出断言通过；未宣称全尺寸或像素级对照验收。

`git diff --check` 与新增脚本 `node --check` 通过，仅有既有 LF/CRLF 提示。原规则测试整体替换未获许可，保留原文件并增加独立测试，不改权限绕过。中断测试后仅停止核对了 PID/父 PID/隔离 JAR 路径的本轮测试进程，不停止用户正式服务。Docker 构建/运行、线上 CI、真实平台和长时间观测未执行；没有提交或推送。

## 2026-10-11 模板应用确认弹框反馈修复

- 用户反馈“确认更新已关联请求”和“应用并关联所选请求”后确认提示反复弹出。对修复前 JAR 和隔离 Vite 开发态分别实际运行手选/关联 × 鼠标/键盘的 4 项验证，两轮均 4/4 通过，未复现持续循环；用户环境的具体循环触发条件尚未确定，不将推测记录为根因。
- 原流程在已展示请求范围、覆盖与改绑提示的预览对话框上，又调用全局 MessageBox 二次确认。本轮去掉这层冗余弹框：保留范围预览，增加“点击主按钮即确认覆盖”的明确提示，将原两个主按钮作为预览后的最终确认；点击取消不保存。提交期间同时使用响应式锁和原生 disabled 防止重入，不改变后端版本/集合/整批回滚约束，不修改全局确认、导入确认或请求发送逻辑。
- 新增 `template-confirmation.spec.js` 4 项回归，覆盖两种模式的鼠标真实双击、键盘 Enter、延迟响应期间再次点击、始终无额外 MessageBox、只发一次配置应用 POST、请求版本只增加一次。原桌面/手机模板用例改为在预览内取消/确认，保留未选不变、凭证/启停/日标记/修订/原文不变和冲突原子性断言。
- 主目录前端单元 **46/46** 和 Vite production build 通过。使用 18080 隔离后端、18081 fixture、18082 Vite（仅临时配置代理到隔离后端、不修改仓库 Vite 配置）与新临时数据目录，修复后的开发态模板流程及防重入 **7/7 通过**（约 1.3 分钟，exit 0）。使用真实 Session，同源写保护仍启用，没有使用现有用户数据或真实平台凭证。
- 在全新 `C:\Users\PC\AppData\Local\Temp\signdesk-confirmation-fix-akJEI9` 实际执行 `node scripts/build.mjs`：fresh npm ci、前端 **46/46**、Vite production build、后端 **149/149**（0 失败/错误/跳过）、Maven clean verify 和单 JAR 打包通过。主目录与副本 **221 份生产源码/后端测试/前端单元测试逐字节一致**；JAR 内 **3 份静态资源**与 fresh dist 完全一致。
- 已 exclusive copy 为新的 `backend/target/signdesk-0.1.0-template-confirmation.jar`，**40,925,295 bytes**，SHA-256 `eacf9fcfa2d65f114d25b1c88d1589a185fb6702e22612c3e11a674ac5e07556`，副本逐字节一致。旧默认/规则模板包和运行中服务没有被覆盖或重启；使用旧包仍是旧确认流程。未重新生成 Docker 镜像，没有提交/推送。
- 新生产 JAR 实际全量浏览器 **64/64 通过**（约 **8.0 分钟**，exit 0，0 失败），包含新增的手选/关联 × 鼠标/键盘直接确认及防重入 4 项。原桌面/手机模板、版本冲突及全部既有登录/平台/计划/备份/执行/详情/分页流程也通过；不是 `--list`。完成后核查 18080/18081/18082 均释放。此结果对应本轮修复包，不沿用上节历史 60/60。

## 2026-10-11 模板应用滚动闪烁与单弹框修复

- 用户反馈应用视图在打开和滚动时视觉闪烁。修复前生产包新增的 24 请求 × 手选/关联 × 桌面/手机 4 项验证全部失败：每个用例 120 帧始终检测到 **2 层可见模糊遮罩**。同时记录的 DOM 连接/可见帧正常、外框 y 位移为 0；未复现 DOM 销毁重建，不将视觉合成闪烁推断为反复关闭或接口轮询，更不宣称已定位用户显卡/浏览器合成根因。
- `RequestTemplatesDialog` 改为唯一 ElDialog，模板列表/编辑/手选应用/关联应用只在同一弹框内切换；新增 `TemplateApplyPanel` 承载应用内容，移除旧独立应用 Dialog。取消、关闭按钮或第一次 Escape 返回列表，再次 Escape 关闭模板管理；提交中不允许关闭。外框高度按视口限制，标题/操作区保留，内容与长请求列表内部滚动并阻止滚动链外溢，不改全局弹框或后端业务。
- 原 `TemplateApplyDialog.vue` 已在隔离临时目录保留逐字节副本后从活动源码移除；首次跨盘 rename 返回 EXDEV，未启动后续测试或修改原文件，随后使用 exclusive copy、逐字节复核后移除旧源码。没有删除用户数据、数据库或配置，也没有修改权限。
- 主目录前端单元 **46/46** 和 Vite build 通过。隔离 Vite 开发态模板全流程/确认防重入/新增滚动 **11/11 通过**（约 1.4 分钟）；补充同一 DOM 身份与 Escape 返回/关闭后，滚动专测 **4/4 再次通过**（约 1.3 分钟）。四个滚动用例各采样 120 帧，只有一套遮罩、弹框始终连接且可见、外框位移不超过 1px、取消按钮在视口内。已查看 1440px/390px 截图，未把本地验证扩展为所有浏览器/显卡像素验收。
- 全新 `C:\Users\PC\AppData\Local\Temp\signdesk-single-modal-in73w7` 实际 `node scripts/build.mjs`：fresh npm ci、前端 **46/46**、Vite build、后端 **149/149**（0 失败/错误/跳过）、Maven clean verify 和单 JAR 打包通过。主目录与隔离副本 **221 份源码/后端测试/前端单元测试逐字节一致**，JAR 静态 **3 份**与 fresh dist 完全一致。
- exclusive copy 交付为新 `backend/target/signdesk-0.1.0-single-modal.jar`，**40,925,671 bytes**，SHA-256 `01ab7073915c627d696f527aeaf3c9f85f5d04849dbe931fbb43266b892fe922`，副本逐字节一致；旧包/旧 static/用户服务未覆盖或重启。未构建新 Docker 镜像，没有提交/推送。
- 新生产包实际全量浏览器 **68/68 通过**（约 **5.8 分钟**，exit 0，0 失败），包括新增 4 项桌面/手机 × 手选/关联滚动稳定性、同一弹框 DOM 与 Escape 返回关闭验证，以及所有既有模板/确认/版本冲突/登录/计划/备份/执行/分页流程。非 `--list`，完成后核查 18080/18081/18082 均释放；此结果对应本轮单弹框包，不使用旧 64/64 替代。

## 2026-10-11 已关联模板应用筛选栏间距修复

- 用户确认单弹框修复有效，并反馈已关联应用视图的筛选输入框与请求列表紧贴。旧样式的桌面 1440px/手机 390px 两项用例都在新增的间距断言失败，实测工具栏底部与列表顶部距离均为 **0px**：手选视图中间有带 margin 的全选操作栏，关联视图没有，而筛选工具栏只设置上边距。
- 仅在 `TemplateApplyPanel.vue` 增加 `.apply-toolbar + .apply-targets { margin-top: 12px; }`，为直接相邻的筛选栏和请求列表留出间距，不额外改变手选视图的操作栏布局。不修改弹框结构、后端、数据库或应用/版本/事务语义。
- 扩展已有 `template-scroll.spec.js` 的四个组合，在完整列表和筛选后单条列表中实际测量间距不小于 12px，再保留滚动帧采样、单遮罩、同一 DOM、取消/Escape 和页面异常断言。隔离开发态 **4/4 通过**（约 **1.3 分钟**，exit 0），已查看桌面/手机关联模式截图。
- 全新 `C:\Users\PC\AppData\Local\Temp\signdesk-template-spacing-aJgU6x` 的首次 `node scripts/build.mjs` 中 fresh npm ci、前端 **46/46** 和 Vite build 通过；后端实际 **148/149 通过，1 失败**（0 错误/跳过），`AutomaticEnqueueRaceIntegrationTest.ordinaryPlanEditInvalidatesOldScanButANewCurrentOccurrenceCanRun` 在受控扫描恢复前的前置断言期望批次数 0、实际 1，Maven clean verify 失败，不记作发布成功。原日志和首轮 Surefire 报告已保留；没有改动后端或降低/跳过断言。
- 未更改任何源码，在同一隔离目录复核完整 `mvn -B -f backend/pom.xml verify`，后端 **149/149 通过**（0 失败/错误/跳过），Maven BUILD SUCCESS、exit 0，完成包含本轮 fresh 前端的单 JAR。首轮竞态前置断言的失败仍保留，未宣称已修复其稳定性；测试 JVM 退出超时、Surefire 强制结束 fork 的日志亦保留。主目录与隔离副本 **245 份构建输入逐字节一致**，JAR 中 **3 份静态资源**与 fresh dist 一致，并确认生产 CSS 包含 12px 间距样式。
- 指定上述新 JAR，使用真实 Session、18080 隔离后端、18081 本地 fixture 与新临时 SQLite，实际运行模板应用 3 项、直接确认/防重入 4 项、间距/滚动 4 项：**11/11 通过**（约 **1.4 分钟**，exit 0，0 失败），含桌面/手机、手选/全部关联、版本冲突整批回滚、筛选前后间距、同一弹框与取消/Escape。不是 `--list`，本轮没有重跑全站 68 项；上一节 68/68 只属于单弹框基线包。完成后核查 18080/18081/18082 均释放。
- exclusive copy 为新 `backend/target/signdesk-0.1.0-template-spacing.jar`，**40,925,680 bytes**，SHA-256 `326685d496bf714b9d90e268d07f3c8500a51d0bb472cfc5ad66806d8788edc8`，与通过生产专项的隔离 JAR 逐字节一致。没有覆盖旧 JAR/旧 static 或重启用户服务，未使用现有用户数据/真实平台凭证；Docker/线上 CI/真实平台/长时观测未新增验证，没有提交/推送。

## 2026-10-11 接口模板底部关闭退场空列表修复

- 用户反馈底部“关闭”会先短暂显示无模板状态，再关闭，而右上角 × 正常。修复前新增 `template-close.spec.js` 实际运行 **5 项：3 通过、2 失败**（约 **40.6 秒**，exit 1）：桌面/手机底部关闭在可见退场帧中出现列表清空/空状态，右上角两项及关闭后跨平台迟到响应隔离通过，复现两条关闭路径差异。
- 根因：底部按钮直接赋值 `visible = false`，同步更新父级 `modelValue` 并触发本组件同步 watch 清空列表，而 ElDialog 仍在退场。Element Plus 2.14.7 的右上角关闭使用 `handleClose()`，在 `afterLeave()` 才发出 `update:modelValue=false`，所以原内容保留至退场结束。此处不是删除模板或数据库数据。
- 最小修复仅为 `RequestTemplatesDialog` 增加带 JSDoc 类型的 ElDialog ref，并让底部按钮调用同一公开 `handleClose()`；沿用既有 `beforeClose`、单弹框、应用返回列表、Escape、提交中禁止关闭和异步会话隔离。不添加延时，不禁用动画，不改全局弹框或后端。
- 新增回归覆盖桌面/手机 × 底部/右上角关闭的逐帧列表保留、关闭后重新打开仍有原模板，以及跨平台旧列表响应不能污染新弹框。
- 隔离 Vite 开发态实际执行模板管理/应用/直接确认/间距滚动/新增关闭测试 **18/18 通过**（约 **3.3 分钟**，exit 0，0 失败），包含新增关闭 5 项。复现阶段产物保留在独立输出目录，修复后测试未覆盖失败证据。真实 Session、新临时 SQLite 和 18080/18081/18082 测试服务，不访问用户数据。
- 在全新 `C:\Users\PC\AppData\Local\Temp\signdesk-template-close-1RHWfZ` 执行 `node scripts/build.mjs`：fresh npm ci、前端 **46/46**、Vite build、后端 **149/149**（0 失败/错误/跳过）、Maven clean verify 和单 JAR 打包通过，exit 0。Surefire 关于测试 JVM 退出超时并强制结束 fork 的日志保留，不将其隐去；本轮没有修改后端或跳过用例。主目录与副本 **246 份构建输入逐字节一致**，JAR 内 **3 份静态资源**与 fresh dist 一致，上一轮 12px 间距修复仍在生产 CSS 中。
- 指定本轮新 JAR 实际运行同一组生产态模板回归 **18/18 通过**（约 **2.0 分钟**，exit 0，0 失败），其中新增关闭 5 项全部通过：两类关闭的可见退场帧不显示空列表，关闭后重新打开内容保留，跨平台迟到响应隔离正常；模板管理/关联应用/冲突回滚/确认防重入/间距滚动/取消/Escape 同时通过。非 `--list`；本轮只跑模板专项，没有重跑全站全部用例，不沿用历史 68/68 作为新包全量验证。18080/18081/18082 均核查释放。
- exclusive copy 为新 `backend/target/signdesk-0.1.0-template-close.jar`，**40,925,737 bytes**，SHA-256 `5ee4f7d9307894c12d3a7e44f1ca543bab8018814efd0be6fe145d38501af593`，与通过生产回归的隔离 JAR 逐字节一致。旧 JAR/旧 static 和用户服务未覆盖或重启，没有修改现有用户数据，未重新构建 Docker 或新增线上 CI/真实平台/长时观测验证；未提交/推送。

## 2026-10-11 Docker 公网发布配置与部署文档

- 按用户要求，将源码 Compose 和预构建镜像 Compose 的宿主机发布地址默认改为 `0.0.0.0`，增加 `SIGNDESK_PUBLISH_ADDRESS` 覆盖，可切回 `127.0.0.1`。宿主与容器端口仍相同，默认 `10500`；没有修改 Java/Vue、登录、同源写保护、数据库或队列实现。
- 两份 Compose 不再回退到 `123456`，使用必填环境变量表达式拒绝缺失/空密码；两份 `.env` 模板留空密码并说明必须填写。实际部署密码仍由服务器配置提供，没有读取/修改真实 `.env`。Docker 构建上下文新增排除 `.env.*`，避免其他命名的部署凭证进入构建上下文。
- 已更新既有 `docs/docker-jib.md` 与 README，覆盖本地 Jib 打包、架构/标签、上传、`/root/signdesk/data -> /app/data`、UID/GID 权限、登录与公网 TCP 10500、匿名健康/管理 401、挂载核验、停机备份、新标签更新和兼容性回退。明确 HTTP 不加密、HTTPS 代理需单独配置与验收；更新不能只 load/restart，不能删除数据或卷。
- 本机未找到 Docker CLI。实际执行 PyYAML 6.0.2 解析两份 Compose，静态断言公网/同端口映射、必填密码、模板非秘密默认值、绑定挂载/禁止自动建目录、pull_policy、40 秒停止和 `/api/health`，均通过；没有将静态解析称为 Compose 实际插值验证。
- 文档首次 Bash 语法检查受 Windows 默认 Bash 路径与 GBK 解码干扰失败；改为显式调用已安装的 Git Bash、UTF-8 字节输入，只执行 `bash -n`，文档全部 **13 段 Bash 代码块语法通过**，没有执行部署命令。首次 `git diff --check` 检出文档两行尾随空格，已改为正确的 shell 续行，复核通过，保留既有 LF/CRLF 提示。
- 本轮没有重新构建 JAR/镜像或重跑应用/E2E，不沿用历史测试作为新镜像验收。未运行 `docker compose config`、Docker 启动、Linux 挂载、公网防火墙、远程浏览器或 HTTPS 代理测试，也没有操作服务器、修改用户数据、提交或推送；这些仍需目标服务器实际验证。

## 2026-10-12 Jib 与 Dockerfile 部署配置合并

- 按用户要求，只保留 `compose.yml` 和 `.env.example`。统一 Compose 同时指定镜像标签和源码 `build`，保持 `pull_policy: never`；Jib 导入后用 `up -d --no-build`，源码构建用 `up -d --build`，更新/回退已导入镜像明确使用 `--no-build --force-recreate`。
- 两条路线共用宿主机绑定目录（模板为 `/root/signdesk/data`）、容器 `/app/data`、公网/同端口 10500、必填管理员密码、匿名 `/api/health`、40 秒优雅停止和资源/权限限制。Dockerfile 运行基础镜像变体与 Jib 对齐为 `eclipse-temurin:21-jre-noble`，未修改 Java/Vue 或 POM。
- 两份被合并的旧配置先以 exclusive copy 保存到 `C:\Users\PC\AppData\Local\Temp\signdesk-compose-merge-aitrpouh`，逐字节核对后才从活动仓库移除；同步移除 `.gitignore` 中旧模板例外，真实 `.env` 仍被忽略。没有读取/覆盖真实 `.env`，没有操作具名卷、数据目录、数据库或服务器。
- README 与 `docs/docker-jib.md` 已统一模板、上传、启动、更新和回退命令，补充从旧配置切换说明：已有 Jib 部署保留真实 `.env` 和数据路径；旧源码具名卷需要停机备份与显式迁移，不能误用空绑定目录或删除旧卷。历史章节仍保留当时的双配置事实，不改写为已合并。
- 实际运行 PyYAML 静态检查，验证统一 image/build/pull_policy、公网同端口映射、密码必填、绑定挂载及禁止自动创建、优雅停止、资源限制和健康检查；唯一模板/旧文件移除断言通过。Dockerfile 指令边界、UID/数据目录及 Jib 运行基础镜像一致性静态检查通过。
- README 与部署文档 Bash 代码块实际通过 Git Bash `bash -n`；部署文档仍为 **13 段**，所有代码块中的 Compose 启动命令均明确选择 `--build` 或 `--no-build`。当前操作文档没有引用已移除的模板/配置文件；`git diff --check` 通过，只有既有 LF/CRLF 提示。
- 本机仍无 Docker CLI，本轮没有实际验证 Compose 插值、无源码 tar 部署、Dockerfile 构建、容器健康或现有卷迁移；没有重新打包 JAR/tar、运行应用/E2E、变更服务器、提交或推送。以上静态结果不等于两条路线已经在 Docker 中运行通过。

## 2026-10-12 二次确认框宽度修复

- 本轮工作区基线为 `main` / `93399e1`，开始时工作区干净；此前 `refactor/backend-ruoyi-style` 与未提交描述属于前一阶段记录。
- 用户反馈重新执行等二次确认框横向铺满屏幕。Element Plus 的 MessageBox 本来为 `width:100%` 配合 `--el-messagebox-width:420px` 的 max-width，项目全局样式将 max-width 覆盖为仅按视口限制，丢失紧凑上限。正确测量 `.el-message-box` 本体后，旧样式 1920px/1440px 桌面宽度分别为 **1888px/1408px**；实际 3 项验证为 **1 通过、2 失败**（约 **28.4 秒**，exit 1），手机原有视口限宽有效。
- 首轮新增测试误测了带 dialog role 的全屏遮罩容器，三项失败不能作为确认框本体宽度证据；改为测量 `.el-message-box`，截图结束进入动画后再读取尺寸，保留首轮与正确复现的独立产物，未通过削弱宽度/业务断言解决失败。
- 仅修改 `frontend/src/style.css` 的 MessageBox max-width 为 `min(var(--el-messagebox-width, 420px), calc(100vw - 24px))`，恢复默认桌面 420px 上限，窄屏按可用空间缩小。沿用居中、文字换行、footer gap 和焦点管理，不改普通 Dialog/Drawer，也不改确认提示、取消、Session 或执行/导入安全逻辑。
- 新增 `confirmation-width.spec.js`，在 1920/1440/390 三种视口下验证真实执行详情中的二次确认框本体宽度、居中、无横向溢出和按钮可见；取消/Escape 不发执行 POST 或目标 HTTP，明确确认后才发送一次本地 fixture 请求。
- 隔离开发态 **3/3 通过**（约 **40.6 秒**，exit 0），实际取消、Escape 和确认行为均通过；已查看 1920px 桌面和 390px 手机的修复后截图，确认框不再横铺页面，正文换行和按钮均可见。复现、错误定位测试与修复后三轮输出分别保留，没有覆盖证据。
- 全新 `C:\Users\PC\AppData\Local\Temp\signdesk-confirmation-width-MmEyjA` 实际运行 `node scripts/build.mjs`：fresh npm ci、前端 **46/46**、Vite build、后端 **149/149**（0 失败/错误/跳过）、Maven clean verify 与单 JAR 打包通过，exit 0。Surefire 关于测试 JVM 退出超时后强制结束 fork 的既有日志保留；本轮未修改后端或跳过测试。主目录与副本 **247 份构建输入逐字节一致**，JAR 内 **3 份静态资源**与 fresh dist 一致，并确认生产 CSS 包含 420px/视口双重限宽规则。
- 指定本轮新 JAR，实际运行新宽度测试 3 项及登录、平台、设置/备份、模板确认相关回归，**22/22 通过**（约 **1.9 分钟**，exit 0，0 失败）。覆盖强制重跑前的取消/Escape/明确确认、删除与覆盖确认、设置草稿离开提示、备份明确整体替换、会话失效关闭确认，以及桌面/手机布局；本轮未重跑全站全部浏览器用例，不沿用旧 68/68 或 18/18 作为新包全量结果。使用真实 Session、18080 隔离后端、18081 本地 fixture、新临时 SQLite；完成后 18080/18081/18082 均核查释放。
- exclusive copy 为新 `backend/target/signdesk-0.1.0-confirmation-width.jar`，**40,925,770 bytes**，SHA-256 `6f39260bb3a7bc7b5766b1853d974d7a66c35d1ddded5c8d5daf1b39aaa561fc`，与通过生产回归的隔离 JAR 逐字节一致。旧 Jib JAR/tar、现有用户数据与运行服务未覆盖或重启；未构建/加载/推送 Docker 镜像，线上 CI/真实平台/长时观测未新增验证；本轮增量未提交/推送。

## 2026-10-14 执行记录日期区间

- 基线为 `main` / `81e86ef`，开始时工作区干净。执行记录增加只含年月日的起止日期，默认北京时间今天；两端日期均包含整天，与列表显示一致，按 `finishedAt`、缺省时按 `createdAt` 筛选。清空日期查全部历史，重置恢复今天，刷新页面保留区间；平台、结果、来源及分页继续组合使用。API 的可选 `startDate` / `endDate` 使用 ISO 日期，无日期参数仍查全部，保留概览近期记录语义。
- 主目录实际运行前端单元 **50/50** 和 Vite build；后端使用 JDK 21.0.2 / Maven 3.9.9，`mvn -f backend/pom.xml test` **154/154**（0 失败/错误/跳过）。增加 4 项真实临时 SQLite 日期回归及 1 项 HTTP 参数契约回归：北京时间午夜、纳秒边界、结束日期全天、跨日完成/未完成、组合过滤、计数/分页、空日期、单边日期、无效及倒置区间。另以 `TZ=America/Los_Angeles` 运行日期单元专项 **4/4**。
- 主目录 `node scripts/build.mjs` 实际执行 npm ci、50 项单元和生产前端构建后，Maven clean 因无法删除既有 `backend/target/jib` 而 **失败（exit 1）**，没有得到主目录完整发布成功结果；没有强制清理占用目录。改用全新 `C:\Users\PC\AppData\Local\Temp\signdesk-date-build-qdXooA`，fresh npm ci、50/50、Vite build、Maven clean verify 154/154 和单 JAR 打包通过。
- 首轮生产包在 `run-dates`、`navigation`、`responses`、`workflow`、`workspace` 五组浏览器回归中 **19/19 通过**（约 2.0 分钟），但检查截图发现手机日期弹层内部仍被裁切；当时断言只验证了弹层外框，不能证明两个日历均完整。定位为 Element Plus 实际 body 类名及 513px 内部 min-width，另补日期输入包装器的 deep 样式，避免桌面输入无意拉宽。修正为手机上下排列日历、内部 min-width 为 0、限制高度并允许滚动；增强两个日历面板尺寸/排列及真实日历点击断言，不削弱原日期和业务断言。
- 更新隔离副本并再次实际执行前端 **50/50**、Vite build、Maven clean verify **154/154**，最终 JAR 打包通过。收尾文档更新前，主目录与副本 **266 份输入逐字节一致**；未改后端业务实现或跳过测试。Vite 大包提示、故障测试输出中的队列错误日志及既有 Surefire 测试 JVM 退出超时后结束 fork 的日志保留，测试统计均无失败/错误/跳过。
- 指定最终新 JAR 和本地 Edge，再次实际运行上述五组，**19/19 通过**（约 1.8 分钟，exit 0）。日期专项 3 项覆盖 1440/390px、非北京时间浏览器默认今天、日历/键盘选择、昨日为空与跨日区间、清空/重置、刷新保留及分页复位；其中参数/分页状态专项使用模拟分页响应，桌面/手机日期查询和既有分页回归使用真实后端记录。已查看最终手机两个完整日历和桌面紧凑筛选截图；首轮截图保留于主目录 `frontend/test-results`，最终产物位于隔离目录 `browser-final`。只使用 18080 隔离后端、18081 本地 HTTP fixture、临时 SQLite 和测试 Session；结束后两个端口确认释放。本轮未重跑全站全部浏览器用例。
- 最终 JAR 内 **3 份静态资源**与 fresh dist 逐字节一致且无旧资源。主目录重新运行 Vite build，并核对主目录 dist 与 fresh dist 相同，同步本地生成 static 资源；以 exclusive copy 新建 `backend/target/signdesk-0.1.0.jar`，**40,927,722 bytes**，SHA-256 `2a8336927057cb071fa93779666a6aaf029baaaa98949ef50419d377032f4fdb`，与通过最终浏览器回归的隔离 JAR 逐字节一致。既有确认框 JAR 未覆盖。
- 已同步 FR-07 和当前验证入口；本轮未提交/推送、部署或变更真实用户数据。Docker 实际构建/运行、线上 CI、真实平台及 24 小时观测仍未验证，不以历史通过数替代。

## 2026-10-14 浏览器标签图标

- 用户反馈浏览器标签没有图标。入口 `frontend/index.html` 没有 favicon 声明，正式前端也没有对应文件。新增 `frontend/public/favicon.svg` 和明确的 `rel="icon"` 引用，沿用侧栏浅绿底、深绿勾及非对称圆角标识；资源随前端打包，不从外部地址加载。不修改鉴权白名单或业务 API。
- 在全新 `C:\Users\PC\AppData\Local\Temp\signdesk-favicon-build-dji5Pq` 实际执行 `node scripts/build.mjs`：fresh npm ci、前端单元 **50/50**、Vite build、后端 **154/154**（0 失败/错误/跳过）、Maven clean verify 和单 JAR 打包通过，exit 0。Vite 大包提示和既有 Surefire 测试 JVM 退出超时后的 fork 结束日志保留。收尾文档更新前，主目录与副本 **268 份输入逐字节一致**。
- 指定本轮新 JAR，使用 Edge 实际运行 `favicon`、`auth`、`navigation`、`run-dates` 四组，**10/10 通过**（49.0 秒，exit 0），非全站回归。新图标专项验证匿名下载 200 / `image/svg+xml`、页面 icon 引用、浏览器解码及 32px 画布上实际品牌颜色渲染、登录/导航/刷新后保留；同时确认未登录管理 API 仍为 401。本轮没有直接截取浏览器原生标签栏或验证其他浏览器品牌，不将资源与渲染验证描述成所有浏览器验收。
- 只使用 18080 隔离后端、18081 本地 fixture、新临时 SQLite 和测试 Session；结束后两个端口确认释放。此前日期区间包 19/19 等历史结果不作为图标新包的全量浏览器结果。
- JAR 内 **4 份前端静态资源**与 fresh dist 逐字节一致，包括 `favicon.svg` 和正确的 HTML 引用。主目录重新构建前端，核对 dist 与 fresh dist 相同并同步生成 static；确认旧主目录 JAR 校验值后，exclusive copy 保留为 `backend/target/signdesk-0.1.0-date-filter.jar`，再更新 `backend/target/signdesk-0.1.0.jar`。新 JAR **40,928,285 bytes**，SHA-256 `894ece7c3027793870bc2946a72da612248fd6361f66c08ef35986cf860b05eb`，与通过本轮回归的隔离 JAR 逐字节一致。
- 没有提交/推送、部署、重启真实服务或变更用户数据；Docker 实际构建/运行、线上 CI、真实平台和 24 小时观测仍未验证。
