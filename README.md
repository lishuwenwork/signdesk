# SignDesk

SignDesk 是个人单实例使用的 cURL 请求与签到管理工具。浏览器用于管理平台、账号、请求和规则；Spring Boot 服务负责保存配置、排定计划、发送 HTTP 请求及记录结果，关闭浏览器不影响后台任务。

> **交付状态（2026-10-09）**：P1～P5 的业务实现已集成到主工作区，自动化后端、前端单元和独立全量构建验证通过。浏览器 E2E 实际运行数为 **0**（8 项只完成 Playwright 清单收集），所以 P6 浏览器验收仍待完成。当前代码集成在 `refactor/backend-ruoyi-style` 主工作区，`475c46a` 是实现前基线，代码尚未提交/推送；是否提交由用户决定。逐项记录见 [验证记录](docs/validation.md)，实现约束见[需求](docs/requirements.md)与[架构](docs/architecture.md)。

## 功能概览

- 管理平台、账号、请求和同平台接口规则模板；可预览导入的 cURL 与结果规则。
- 手动或按每日/每周计划执行；支持时区、多计划时刻、间隔、补执行窗口、同平台串行和跨平台有界并发。
- 请求排队后冻结请求修订、规则与计划参数；每个执行独立 HTTP 对象和 Cookie。超时、中断、无法确认的结果不自动重发。
- 支持结果分类、凭证过期暂停、每日日志与去重状态；查看执行响应详情，支持文本、HTML 纯文本和 Base64 呈现。
- 提供两种配置导出和“先预览、再确认替换”的导入。完整配置可能含有原始 cURL 和凭证，属于敏感明文文件。

技术栈为 Java 21、Spring Boot 4 / Spring MVC、SQLite、MyBatis-Plus、Hutool HTTP、Vue 3 / JavaScript。生产为同源单 JAR，SQLite 文件位于 JAR 外部的数据目录。支持配置文件中的单管理员登录，不建用户表、不提供注册或验证码；远程使用仍应通过 HTTPS 或可信私有入口。

> **2026-10-11 登录功能**：当前源码已增加登录与 Session 鉴权，下面 2026-10-09 的交付状态及旧产物说明是历史记录，不代表旧 JAR/镜像含登录页。请重新构建；本轮实际验证见 [验证记录](docs/validation.md)。

## 运行

服务需要 Java 21 和可写的数据目录。默认监听 `127.0.0.1:10500`，默认数据目录为用户主目录下的 `.signdesk/data`（例如 Windows `C:\Users\<用户>\.signdesk\data`、Linux `/home/<用户>/.signdesk/data`），与启动时的工作目录无关。首次运行若需要指定其他位置，请选一个**全新的空目录**，例如：

```powershell
$env:SIGNDESK_DATA_DIR = "D:\signdesk-data-v1"
$env:SIGNDESK_PORT = "10500"
java -Xms64m -Xmx256m -jar .\backend\target\signdesk-0.1.0.jar
```

打开 `http://127.0.0.1:10500`。Linux 示例：

```bash
export SIGNDESK_DATA_DIR=/srv/signdesk-data-v1
export SIGNDESK_PORT=10500
java -Xms64m -Xmx256m -jar backend/target/signdesk-0.1.0.jar
```

`SIGNDESK_DATA_DIR` 应指向新的数据位置，而不是原有应用留下的目录。新程序使用 `signdesk-plain-v1`、schema version 1 的 SQLite 结构；遇到不兼容或不完整的数据库时，会拒绝启动，不会迁移、清空、重建或删除文件。请保留已有数据目录；需要新实例时另选目录。不要将旧数据库直接作为新实例数据源。

### 管理员登录配置

在 `backend/src/main/resources/application.yml` 配置唯一管理员：

```yaml
signdesk:
  auth:
    username: admin
    password: "123456"
```

启动后先登录，再进入管理页面。只有这一个配置账号，没有用户表、注册或验证码。**`123456` 是初始弱密码，远程使用前请修改**；修改后重启生效，应用重启后原登录会话失效。账号只用于页面和管理 API，退出不会停止后台任务，业务备份不包含或替换管理员配置。

源码启动读取上述 YAML。已发布的 JAR 无需解包修改，可在可信位置保存同样结构的外部 YAML，然后：

```bash
java -jar backend/target/signdesk-0.1.0.jar --spring.config.additional-location=file:/absolute/path/signdesk-admin.yml
```

也可以通过 `SIGNDESK_AUTH_USERNAME`、`SIGNDESK_AUTH_PASSWORD` 环境变量覆盖；Docker Compose 可在不提交的 `.env` 中配置这两个变量。外部配置含明文密码，需限制文件权限，不纳入公开备份或源码提交。

会话使用服务端内存与 HttpOnly / SameSite=Lax Cookie，空闲超时 30 分钟，无“记住密码”；打开页面时正常轮询会延续会话。HTTPS 部署可设置 `SERVER_SERVLET_SESSION_COOKIE_SECURE=true`，纯 HTTP 本机/SSH 隧道不要开启，否则浏览器不能携带登录 Cookie。反向代理需保留正确的外部 Host、协议和端口，仅在代理可信且清理客户端转发头时启用 Spring 的 forwarded-header 配置，不放宽同源写保护。

匿名只允许登录、会话查询、退出和只返回 `{ "ready": true }` 的 `GET /api/health`；其他管理 API（含 `/api/system/status`）需登录，否则返回 401。

### 本轮登录功能验证 JAR（2026-10-11）

`backend/target/signdesk-0.1.0-login.jar` 已包含登录页与后端鉴权，由最终隔离全量构建复制为新文件名，没有覆盖旧 JAR：

```bash
java -Xms64m -Xmx256m -jar backend/target/signdesk-0.1.0-login.jar
```

默认数据目录仍是 `~/.signdesk/data`；已有运行实例请先正常停止再启动，不能同时使用同一数据目录。开发时前端使用新源码，后端需重启。旧镜像/tar 不含此次登录改动，Docker 部署须重新构建镜像。该 JAR 是忽略的生成文件，不提交源码；复现方式仍为 `node scripts/build.mjs`。

### 本次已验证的本地 JAR 副本

当前主工作区另有一份非默认名称的验证副本：`backend/target/signdesk-0.1.0-refactored.jar`，大小 **40,854,484 bytes**，SHA-256：

```text
d30c955c167734f15645a041da2fa0e90e0f1cc5ad8552a77904e25102963c54
```

它由已通过独立全量构建的 JAR 以新文件名复制到主工作区；不是主目录重新运行 Maven 生成的产物，也不是常规发布文件名。使用此副本时：

```powershell
$env:SIGNDESK_DATA_DIR = "D:\signdesk-data-v1"
java -Xms64m -Xmx256m -jar .\backend\target\signdesk-0.1.0-refactored.jar
```

该文件是本地未跟踪/忽略的构建产物，不随源码提交。可复现的常规发布方式见下文，默认产物名为 `backend/target/signdesk-0.1.0.jar`。

## 从源码构建

安装 JDK 21、Maven 3.9+ 和 Node 22.12+（建议 Node 24），在仓库根目录运行完整发布脚本：

```bash
node scripts/build.mjs
java -Xms64m -Xmx256m -jar backend/target/signdesk-0.1.0.jar
```

脚本安装锁定的前端依赖、构建本轮前端资源、运行后端验证并打包单 JAR；任一步失败即中止。常规发布文件名仍为 `signdesk-0.1.0.jar`。本次独立验证实际执行了完整脚本；主工作区的前端 unit/build 也通过，但主目录“清理静态资源＋Maven clean verify”组合命令在执行前被隔离工具拒绝，因此不能声称主目录另行运行了 Maven clean 构建。详细边界见[验证记录](docs/validation.md)。

开发时分别启动：

```bash
# 终端一：后端
mvn -f backend/pom.xml spring-boot:run

# 终端二：前端
cd frontend
npm ci
npm run dev
```

Vite 默认在 `http://127.0.0.1:9500` 提供页面，并将 `/api` 代理到本机后端的 `10500` 端口。开发和正式启动默认共用同一个 `~/.signdesk/data` 数据目录；需要并行运行实例时，请为其中一个显式设置不同的绝对 `SIGNDESK_DATA_DIR`，避免误开另一份数据库。

Dockerfile、Compose 与 GitHub Actions 配置已保留，但本轮没有验证 Docker 实际构建/运行或线上 CI。

### Jib：本地生成镜像 tar，上传 Linux 后运行

本地无需 Docker，使用 JDK 21、Maven 3.9+ 和 Node 22.12+，在仓库根目录运行：

```bash
mvn -f backend/pom.xml -Pjib clean verify
```

命令会隔离构建最新前端、运行前后端测试、打包 JAR，并生成 `backend/target/jib/signdesk-0.1.0-linux-amd64.tar`，镜像标签为 `signdesk:0.1.0`，不推送镜像仓库。ARM64 服务器使用 `-Dsigndesk.image.architecture=arm64`。

上传 tar、`compose.image.yml` 和 `.env.image.example`，把后者复制为 `.env` 并设置宿主机数据目录（默认 `$HOME/.signdesk/data`，例如可改为 `/root/signdesk/data`），再按[完整部署说明](docs/docker-jib.md)把该目录初始化为容器用户 `10001:10001` 可写，然后在 Linux 服务器执行：

```bash
docker load -i signdesk-0.1.0-linux-amd64.tar
docker compose -f compose.image.yml up -d
```

镜像包含 Java 21 和生产前端，服务器不需要 Java、Node 或源码。容器内数据目录固定为 `/app/data`，宿主机实际路径由 `SIGNDESK_HOST_DATA` 决定，二者用绑定挂载关联。生产页面与 API 都由 `10500` 提供，`9500` 仅用于前端开发。默认端口只发布到服务器本机，请通过 SSH 隧道或可信入口访问；管理页面需要管理员登录，远程部署前修改初始密码。镜像不会携带本地数据库或凭证。tar 构建成功不代表 Linux 容器/E2E 已验收，实际结果见[验证记录](docs/validation.md)。

## 首次配置与执行

1. 使用配置文件中的管理员账号登录，再在“平台与账号”新增平台、账号和请求。
2. 粘贴浏览器复制的完整 cURL，查看方法、URL、Headers 和 Body 的解析预览。预览不发送请求；不会执行 shell，也不会读取 cURL 引用的文件。
3. 按平台响应配置结果规则。规则区分数字 `0`、字符串 `"0"`、布尔和 null，不执行用户脚本。
4. 手动执行并检查结果，再配置平台计划。

HTTP 401 或用户定义的过期规则会暂停对应凭证，请更新 cURL 后再执行。超时、连接中断、响应不完整或无法分类的结果为待确认/unknown，不自动重试。手动重新执行可以忽略当日完成/待确认标记，但不能绕过平台、账号、请求启停或凭证暂停。

### cURL 支持边界

解析器支持常见 Bash 和 Windows CMD 请求形式，但不执行命令、变量、管道或外部文件。支持 GET / POST / PUT / DELETE / HEAD / OPTIONS、ASCII HTTP/S URL、重复 Query/Header、Cookie、内联数据项、显式 GET 参数与受控重定向；具体语义和拒绝项以[需求文档](docs/requirements.md)为准。文件上传、`@文件`、代理选项、客户端证书、关闭 TLS 验证等不支持或会被拒绝。TLS 证书及主机名验证保持开启。

Hutool/JDK 不保证复现浏览器全部指纹、TLS 行为或所有 cURL 选项。带时间戳/一次性签名、短时 Token、IP 绑定或验证码的请求可能无法长期重放；需按目标平台要求更新请求。不得将此工具视为通用浏览器自动化或不可信用户的网络隔离沙箱。

### 代理

在“设置与备份 → 请求代理”保存代理模式。后端模式标识为 `system`、`direct`、`http`、`socks`：跟随 Java 代理选择器、直连、HTTP 代理或 SOCKS5。代理主机和端口校验后持久保存，对新开始的请求生效；正在发送的请求继续使用开始时的配置。代理不可用时不回退直连。应用内代理不支持用户名/密码认证。HTTPS 仍校验证书和主机名。

## 执行记录与响应

执行记录列表不读取或返回响应正文。打开详情才按需读取已保存的响应字节，接口禁止缓存（`no-store`）；关闭详情会清除页面内容。HTML 按纯文本展示，不执行脚本；文本按响应字符集解码，二进制或无法解码的响应以 Base64 表示。

响应读取上限为 **1 MiB**。超限保存前缀并标记截断；读取中断保留已收到部分并区分部分/未获取状态；空响应、未发送、未保存也各自有明确状态。无法确认完整性的响应不能用于自动判定成功。响应随执行日志清理；每日完成/待确认标记和自动发生去重记录独立保留。运行中断导致未能保存的响应不会靠重发请求补录。

## 配置导出与导入

所有配置导出均为明文 JSON。完整配置可能包含 Cookie、Token、签名等凭证；请只保存在可信位置，不通过外部服务分享。普通模式不导出系统保存的 cURL/每日标记，但用户自行写入备注、名称或规则的敏感内容仍可能被导出，下载前应检查。

HTTP 契约：

```json
{"includeRequests": false}
```

请求 `POST /api/backups/export`；省略 `includeRequests` 也默认为 `false`。预览和确认替换：

```json
{"backup": {"format": "signdesk-plain-v1", "payload": {}}}
{"backup": {"format": "signdesk-plain-v1", "payload": {}}, "replace": true}
```

分别提交到 `POST /api/backups/preview` 和 `POST /api/backups/import`。文件只使用 `format: "signdesk-plain-v1"` 与类型化 `payload`，没有 `formatVersion`。payload 字段为 `includesRequests`、`settings`、`platforms`、`accounts`、`requests`、`templates`、`schedules`、`completed`、`pending`。预览返回顶层 `counts`、`includesRequests`、`mode: "replace"`、`message`；成功导入返回 `{ "restored": true, "paused": true }`。

- 完整模式导出当前已配置的 cURL 与每日完成/待确认标记；合法的未配置占位请求仍不带 `rawCurl`。
- 普通模式不导出 `rawCurl` 或每日标记；导入后占位请求停用并标记凭证待更新。界面显示“未配置”、禁止查看详情，但允许更新 cURL。
- 两种模式都不包含旧请求修订、执行历史、响应正文和活动队列。导入请求修订从 1 开始，计划从导入时刻生效，恢复后全局定时暂停，不自动发送。
- 导入校验结构、正十进制 long 字符串 ID、关系、规则、计划与大小；预览后需明确确认整体替换。只有队列空闲并取得维护互斥才进行一致替换，事务失败整体回滚。
- payload UTF-8 大小上限 **8,388,608 bytes**；导入文件与实际 HTTP 读取上限 **12,582,912 bytes**。

## 数据与访问安全

原始 cURL 与结构化请求写入 SQLite 明文；响应字节以 BLOB 保存。程序不把完整请求、响应正文、备份内容或 SQL 参数写入应用日志。SQLite 文件和完整配置文件仍可能含敏感内容，必须依靠操作系统文件权限和可信备份管理保护。

默认监听本机 `127.0.0.1`，管理 API 使用 Session 鉴权，无用户表或 JWT。登录不能代替 HTTPS、文件权限和网络隔离；远程使用应修改默认密码并置于可信入口之后，不要直接公开明文 HTTP 管理端口。默认网络策略阻止本机、内网、链路本地及云元数据地址；个人内部目标仅能按明确主机白名单放行，云元数据目标始终阻止。

| 环境变量 | 默认值 / 用途 |
|---|---|
| `SIGNDESK_BIND_ADDRESS` | `127.0.0.1`，绑定地址 |
| `SIGNDESK_PORT` | `10500`，HTTP 端口 |
| `SIGNDESK_DATA_DIR` | 默认 `~/.signdesk/data`，外部 SQLite 数据目录；设置后覆盖默认路径 |
| `SIGNDESK_AUTH_USERNAME` | `admin`，覆盖配置文件中的管理员用户名 |
| `SIGNDESK_AUTH_PASSWORD` | `123456`，覆盖管理员密码，远程使用前必须修改 |
| `SERVER_SERVLET_SESSION_COOKIE_SECURE` | HTTPS 部署设为 `true`；本机 HTTP / SSH 隧道保持默认 |
| `SIGNDESK_ALLOWED_HOSTS` | 可选，逗号分隔的明确主机白名单，例如 `internal.example.com,192.168.1.20` |

## 验证状态

截至 2026-10-09：最终后端测试 **110/110**、前端单元测试 **8/8**，独立工作树完成 fresh `npm ci`、前端构建、`mvn clean verify` 和 `node scripts/build.mjs` 全发布。主工作区前端单元和 build 通过；发布 JAR 与主目录静态资源已做逐字节交叉核对。

**浏览器 E2E 仍未运行**：8 项 / 3 个测试文件只被 Playwright `--list` 收集，实际执行 **0 项**；隔离工具在 Edge 环境命令启动前两次拒绝，18080/18081 服务和 Edge 均未启动，没有浏览器截图或测试报告。不得将旧历史 7 项或 `--list` 结果描述为本轮 E2E 通过。Docker 实际构建/运行、远端 CI、真实平台联调和 24 小时运行观测也未验证。完整命令、JAR 指纹与警告见[验证记录](docs/validation.md)。
