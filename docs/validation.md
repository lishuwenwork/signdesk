# SignDesk 验证记录

本文保留 2026-10-08 的历史验证原文，并追加 2026-10-09 当前重构实现的实际验证。前面的历史结果是在重构前代码上完成，不能代替下文的当前实现结果，也不能把历史浏览器通过数当成本轮 E2E 通过。

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