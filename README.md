# 签行 SignDesk

一个个人使用的 cURL 请求与签到管理系统。浏览器负责管理，Spring Boot 负责保存、定时、发送和记录；关闭页面后服务器任务继续执行。

同一个仓库包含 `frontend` 与 `backend`，正式发布为一个 JAR。使用 SQLite，无 Redis，无应用内登录认证。HTTP 请求由 Hutool 执行，不调用系统 cURL 或 shell。

## 已实现

- 平台、账号、独立请求的新增、修改、启停与删除，一个账号可有多个请求。
- 同平台多份接口规则模板，新增请求可套用名称和规则，已有请求可直接保存为模板。
- 整份 cURL 导入、解析预览、结果规则试算、整体加密和版本更新。
- 单请求、平台、全部平台的后台执行，持久队列、取消剩余任务和进度查询。
- 平台的每日／指定星期计划、多时间点、北京时间／UTC、间隔和补执行窗口。
- 同平台串行、跨平台并发上限、手动幂等键和自动触发去重。
- JSON 类型严格的业务判断：成功、已完成、过期、失败、待确认。
- 独立的每日完成与待确认标记、凭证过期暂停、服务重启恢复。
- 记录筛选与分页、全局定时暂停、超时与日志保留配置。
- 无请求内容的配置导出，以及密码加密、可换主密钥恢复的完整配置备份。
- Vue3 页面、桌面和窄屏布局、Linux/Windows 构建脚本、Docker Compose、GitHub Actions 配置。

## 技术栈

| 部分 | 固定版本／实现 |
|---|---|
| Java | 21 |
| 后端 | Spring Boot 4.1.1 / Spring MVC |
| 数据访问 | MyBatis-Plus 3.5.17；SQLite 队列事务使用 JdbcTemplate |
| SQLite JDBC | Xerial 3.53.4.0，WAL、单连接池、外部数据目录 |
| 请求与工具 | Hutool 5.8.47 |
| 前端 | Vue 3.5.43、JavaScript、Element Plus 2.14.7 |
| 构建 | Vite 8.3.3、Maven 3.9+、Node 22.12+（建议 24） |
| 调度 | Spring TaskScheduler；SQLite 持久化状态 |
| 加密 | Java JCE AES-256-GCM；备份 PBKDF2-SHA256 + AES-GCM |

平台、账号、请求、执行批次和执行项的主键统一使用 MyBatis-Plus 雪花 ID。平台通过 `ASSIGN_ID` 自动分配，其余 JDBC 写入通过统一的 `Ids.next()` 生成；数据库和 API 均保留字符串类型，避免 JavaScript 长整数精度丢失。浏览器生成的手动执行幂等键不属于主键，仍使用随机 UUID。

## 运行已经打包的程序

只需要 Java 21，不需要 Node、Maven、MySQL 或 Redis。

在 Windows PowerShell 中运行：

```powershell
java -Xms64m -Xmx256m -jar .\signdesk-0.1.0.jar
```

打开 `http://127.0.0.1:8080`。默认在启动目录创建 `data/signdesk.db` 和独立的 `secrets/master.key`。第一次生成密钥后，后续启动复用它。更新 JAR 时保留这两个目录。`-Xmx256m` 是堆上限，进程总内存会高于堆，不代表已经完成长时间资源测量。

如果要固定存储位置：

```powershell
$env:SIGNDESK_DATA_DIR = "D:\signdesk-data"
$env:SIGNDESK_KEY_FILE = "D:\signdesk-secrets\master.key"
$env:SIGNDESK_PORT = "8080"
java -Xms64m -Xmx256m -jar .\signdesk-0.1.0.jar
```

Linux 用同样的 `java -jar` 启动，路径用本机绝对路径。需要长期运行时可交给系统服务管理器；停止服务期间无法签到。

## 从源码构建

先安装 JDK 21、Maven 3.9+ 和 Node 22.12+。在仓库根目录运行：

```bash
node scripts/build.mjs
java -Xms64m -Xmx256m -jar backend/target/signdesk-0.1.0.jar
```

脚本先 `npm ci` 和构建前端，再清除旧静态资源并复制本轮 dist，最后运行 Maven 测试和打包。任意步骤失败会中止。产物为 `backend/target/signdesk-0.1.0.jar`。

开发时分别启动两个工程：

```bash
# 第一个终端：Java 后端
mvn -f backend/pom.xml spring-boot:run

# 第二个终端：Vue 页面
cd frontend
npm ci
npm run dev
```

浏览器打开 Vite 输出的地址，默认 `http://127.0.0.1:5173`。`/api` 代理到本机 8080。IDEA 打开 backend 的 pom.xml，项目 SDK 选 Java 21；WebStorm 打开 frontend。开发和正式启动请设置同一绝对数据目录，避免因工作目录不同而打开另一份数据库。

Docker 可选：

```bash
docker compose up --build -d
```

Compose 默认只向本机映射 8080，数据和密钥分别放在持久卷。Dockerfile 构建时会运行后端测试。本轮已在 Windows 11 上完成自动化测试，尚未验证 Docker 实际运行，具体检查范围见 `docs/validation.md`。

## 配置请求代理

在「设置与备份 → 请求代理」选择模式，填写代理主机和端口后保存。

- **HTTP 代理**：支持 HTTP 请求和 HTTPS CONNECT 隧道。使用 v2rayN 时填写 `127.0.0.1` 和它实际的 HTTP／混合代理端口。
- **SOCKS5 代理**：填写 SOCKS5 代理主机和端口。
- **直连**：忽略 Java 启动时的代理参数。
- **跟随 Java 启动配置**：默认模式，使用 Java 的代理选择器，兼容 `-Dhttp.proxyHost`、`-Dhttps.proxyHost`、`-DsocksProxyHost` 等设置。Java 默认不读取 `HTTP_PROXY` / `HTTPS_PROXY`；Windows 系统代理需在启动时启用 `-Djava.net.useSystemProxies=true`。

代理配置保存在 SQLite，保存后对新开始发送的请求生效，正在执行的请求继续使用原配置；重启后保留，普通配置和加密备份也包含这些设置。旧数据库自动迁移，旧备份缺少代理设置时恢复为默认模式。迁移到新数据库版本后应使用新版程序。

配置了应用内 HTTP／SOCKS5 代理后，启动 Java 时无需再附加代理参数。代理必须运行在 Java 服务能够访问的位置；本机 v2rayN 需要保持运行。当前支持无需用户名／密码认证的代理，不支持在导入的 cURL 中指定代理。HTTPS 始终验证证书和主机名，代理不可用时不回退直连，也不自动重试待确认请求。

## 开始使用

1. 在「平台与账号」新增平台和账号，再点击「添加请求」。
2. 粘贴浏览器复制的完整 cURL，检查方法、URL、Headers 和 Body。
3. 根据平台实际返回配置规则。默认成功规则是 JSON `code` 等于数字 `0`；字符串 `"0"` 要单独填写。
4. 手动执行一次，查看记录并核对真实平台结果。
5. 在「定时计划」配置该平台的时间并启用。

HTTP 401 自动标记凭证过期；用户配置的过期业务规则也会暂停请求。更新 cURL 可解除凭证暂停。HTTP 200 未匹配规则、超时或连接中断会标为待确认，系统不自动重试。主动「重新执行」会忽略当前日期的完成／待确认标记，但不会绕过启停或凭证暂停。

## 复用同平台的接口规则

在「平台与账号」选中平台，点击「接口模板」，分别保存签到、领取奖励、查询积分等接口的名称和结果规则。也可以打开已有请求的「更多 → 保存为接口模板」，直接复用已经配置好的规则。

以后在同平台任意账号下「添加请求」，先选择接口模板，名称和规则会自动带入；再粘贴当前账号自己的完整 cURL，预览后保存。选择模板不会修改已粘贴的 cURL，手工改过名称或规则时，切换模板会先要求确认。模板是可选项，不使用模板仍可照常添加。

模板只保存名称和规则，不保存 URL、Headers、Body 或 Cookie／Token，也不替换账号变量。新请求保存自己的规则副本；修改或删除模板不改变已有请求或已排队的任务。模板随平台删除，并包含在普通配置和完整加密备份中；没有模板字段的备份导入后，模板列表为空。

## cURL 支持范围

| 项目 | 当前行为 |
|---|---|
| 格式 | 常见 Bash、Windows CMD 格式；不执行变量、命令或文件引用 |
| 方法 | GET / POST / PUT / DELETE / HEAD / OPTIONS；不支持 PATCH、GET Body、HEAD Body |
| URL | 合法 ASCII HTTP/S 绝对 URL；保留编码与重复 Query，不重新生成签名参数 |
| Headers | 保存有序条目与重复值；Cookie、Basic Auth、设备头可来自完整请求 |
| Body | UTF-8 内联 `-d`、`--data-raw`、`--data-binary`、`--data-urlencode` 与多个数据项 |
| GET 参数 | `-G` 将数据项放入查询参数 |
| 压缩 | gzip / deflate / identity，拒绝 br / zstd |
| 重定向 | 明确 `-L` 时，同来源最多 5 跳；301/302 POST 转 GET，303 转 GET，307/308 保持方法和 Body；跨来源停止，不转发凭证 |
| 超时 | 无参数时继承全局；命令中的 max-time / connect-timeout 与全局上限取最严格值，connect-timeout 在本版也限制总执行期限 |
| 不支持 | `-L` 同时显式 `-X`、文件上传、`@文件`、cURL 中的代理选项、客户端证书、HTTP/2 强制、关闭 TLS 验证、命令管道、外部配置文件 |

手工 Host、Connection、Transfer-Encoding 等传输头会被拒绝；Content-Length 提示按实际 Body 字节计算。Hutool/JDK 的传输头、HTTP 指纹和浏览器可能不同，完整 cURL 是一次请求快照。时间戳、一次性签名、Token、IP 绑定和验证码不能靠重放长期维持有效。CMD 中非 ASCII 请求体按 UTF-8 处理，需核对目标平台的编码要求。

## 数据与访问配置

| 环境变量 | 默认值／说明 |
|---|---|
| SIGNDESK_BIND_ADDRESS | `127.0.0.1` |
| SIGNDESK_PORT | `8080` |
| SIGNDESK_DATA_DIR | `./data` |
| SIGNDESK_KEY_FILE | `./secrets/master.key` |
| SIGNDESK_SECRET_KEY | 可选，Base64 编码的 32 字节主密钥，优先于文件 |
| SIGNDESK_ALLOWED_HOSTS | 可选，以逗号分隔的明确内网主机，例如 `internal.example.com,192.168.1.20` |

本版没有登录、Session、JWT 或角色模块。需要远程使用时，接入你自己的私有网络入口。写接口只接受 JSON，浏览器跨站请求被拒绝；这些边界不替代登录或私有入口。

默认阻止本机、内网、链路本地和云元数据地址。个人内部平台可显式允许主机，云元数据地址始终阻止。此策略面向个人配置的请求，不是面向不可信多用户的完整网络隔离沙箱。

完整原始 cURL 和解析结果使用随机 nonce 的 AES-GCM 整体加密，关联数据绑定请求 ID 和版本。列表与日志不返回完整请求或响应；明确查看请求时接口禁止缓存。丢失主密钥后不能从数据库单独恢复请求，服务不会生成新密钥覆盖已有密文。

## 备份与恢复

- 普通配置导出不含请求内容。恢复后请求保持停用，需要更新 cURL 再启用。
- 完整备份密码至少 10 字符，使用 PBKDF2-SHA256 600,000 次和 AES-GCM。保存当前请求、规则、计划、执行设置、凭证暂停、每日完成与待确认标记；不保存旧请求版本、历史日志和活动队列。
- 备份在单个 SQLite 事务中取得一致的逻辑快照。内容上限 8 MiB，导入文件上限 12 MiB。
- 导入先预览，再明确替换全部配置；只允许队列空闲时导入，所有修改在一个事务中。密码错误或校验失败不改变当前数据。
- 恢复后当前请求从版本 1 开始，使用目标服务主密钥重新加密，保留请求 ID 和当前周期标记；全局定时始终保持暂停，检查后再恢复。
- 要原样保存所有历史，先停止服务，再同时备份数据库目录与密钥目录。不要在服务运行时只复制主 `.db` 文件而遗漏 WAL。

## 目录与验证

| 目录 | 内容 |
|---|---|
| backend | Java 服务、SQL 迁移、后端测试 |
| frontend | Vue 页面、锁定依赖、Playwright 浏览器测试 |
| scripts | 跨平台发布构建、本地浏览器测试服务 |
| docs | 需求、实施架构和验证记录 |
| .github/workflows | 前后端构建、后端／浏览器检查、JAR 构建产物 |

测试：

```bash
mvn -f backend/pom.xml test
cd frontend
npm ci
npm run test:unit
npm run build
# 浏览器测试需先在仓库根目录完成 node scripts/build.mjs
npx playwright install chromium
npm run test:e2e
```

测试只使用独立临时数据目录和明确允许的本地回显服务，不需要真实账号凭证。项目未内置任何真实平台、Cookie 或 Token。已在 Windows 11 上完成自动化检查，尚未完成真实平台联调、24 小时运行观测和 Docker 实测。
