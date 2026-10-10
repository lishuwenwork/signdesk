# Jib 本地打包与 Linux Docker 部署

这条路径不需要本地 Docker，也不上传镜像到镜像仓库：在本地用 Maven 生成 Docker 镜像 tar，上传到 Linux 服务器后 `docker load`。服务器不需要 Java、Maven、Node 或项目源码。

## 1. 本地构建

要求 JDK 21、Maven 3.9+、Node 22.12+，`java`、`mvn`、`node`、`npm` 应可执行。首次构建仍需访问 npm、Maven 仓库与基础镜像仓库。

在仓库根目录执行：

```bash
mvn -f backend/pom.xml -Pjib clean verify
```

该命令依次：

1. 将前端构建输入复制到 `backend/target/jib/frontend-build`，在此隔离目录执行锁定的 `npm ci`、前端单元测试和 Vite 构建，不复用开发服务的 `node_modules`。
2. 将最新前端复制到 `backend/target/jib/frontend-resources/static`。此 profile 排除源码下可能残留的旧 `static`，只打入本轮前端资源。
3. 编译后端，运行后端测试，生成可执行 JAR。
4. 用 Jib 构建 Linux 镜像 tar（绑定在 `verify` 阶段），不调用 Docker daemon，不推送镜像仓库。

默认产物：

- JAR：`backend/target/jib/signdesk-0.1.0.jar`。
- 镜像：`backend/target/jib/signdesk-0.1.0-linux-amd64.tar`。
- tar 内的镜像标签：`signdesk:0.1.0`。

镜像使用 Java 21 JRE，以 `10001:10001` 非 root 用户运行，监听容器内 `10500`，数据目录为 `/app/data`。Jib 分层放入依赖、资源和编译后的应用类；数据库、日志、备份、`.env` 和开发依赖不在打包输入内。前端 `9500` 仅为开发端口，生产镜像由后端在 `10500` 同源提供页面和 API，不启动 Vite。

普通 Maven 构建和 `node scripts/build.mjs` 不会自动执行 Jib；只有启用 `-Pjib` 的上述命令才生成 tar。该 profile 的全部构建输出放在 `backend/target/jib`，与 IDE 的普通 `target/classes` 隔离。不要只执行 `jib:buildTar` 而跳过构建生命周期，否则可能使用旧的 class/静态资源。

### CPU 架构与自定义标签

在服务器执行 `uname -m`：

- `x86_64`：使用默认 `amd64` 镜像。
- `aarch64`：用下面的 ARM64 命令，产物文件名为 `signdesk-0.1.0-linux-arm64.tar`。

```bash
mvn -f backend/pom.xml -Pjib clean verify -Dsigndesk.image.architecture=arm64
```

也可以使用独立发布标签（部署时需同步设置 `SIGNDESK_IMAGE`）：

```bash
mvn -f backend/pom.xml -Pjib clean verify -Dsigndesk.image=signdesk:release-1
```

基础镜像默认 `eclipse-temurin:21-jre-noble`。有重现性要求时，可通过 `-Dsigndesk.base-image=eclipse-temurin@sha256:<经验证的摘要>` 锁定基础镜像；必须保持 Java 21、目标架构和健康检查所需的 `curl`。不要通过关闭 TLS 校验或允许不安全镜像仓库绕过下载失败。

### 构建时访问镜像仓库需要代理

Jib 的构建 JVM 不一定自动采用 Windows 系统代理。若本机直连镜像仓库超时，可以仅为当前终端的 Maven 进程设置代理。例如 PowerShell（端口必须换成自己实际启用的 HTTP 代理）：

```powershell
$env:MAVEN_OPTS = "$env:MAVEN_OPTS -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=10808 -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=10808"
mvn -f backend/pom.xml -Pjib clean verify
```

网络能直连时无需设置；这些参数只用于构建，不能作为镜像中的业务请求代理。不要把本机代理地址固化到 POM 或容器环境，也不要关闭证书校验。本轮实际通过临时构建代理完成，记录见 [验证记录](validation.md)。

## 2. 上传文件

将镜像 tar、`compose.image.yml` 和 `.env.image.example` 上传到服务器的同一个部署目录，例如 `~/signdesk/`。这里使用的服务器地址和用户是示例，请替换。

Windows PowerShell 示例：

```powershell
ssh user@server "mkdir -p ~/signdesk"
scp .\backend\target\jib\signdesk-0.1.0-linux-amd64.tar .\compose.image.yml .\.env.image.example user@server:~/signdesk/
```

`compose.image.yml` 只使用已导入的镜像，没有 `build`，且 `pull_policy: never`，不会在服务器重新编译或拉取应用镜像。不要与用于源码构建的 `compose.yml` 混用。

## 3. 准备持久数据目录

容器内部固定把 SQLite 读写到 `/app/data`（由镜像的 `SIGNDESK_DATA_DIR` 决定，不需要改）。**你只需要决定宿主机这一侧放哪里**，通过 `SIGNDESK_HOST_DATA` 设置。指定目录若不存在，容器会启动失败而不是自动创建，这样不会在你不注意时生成一个 root 属主的空目录。

下面以 `/root/signdesk/data` 为例。**关键点：容器以 `10001:10001` 运行，所以这个目录必须归 `10001` 所有**，否则启动时会报数据库目录不可写／无法获取实例锁：

```bash
sudo install -d -m 700 -o 10001 -g 10001 /root/signdesk/data
ls -ld /root/signdesk/data            # 期望所有者为 10001:10001
```

`-o 10001 -g 10001` 是核心；`-m 700` 是因为该目录存放明文 Cookie／Token，不宜对其他系统用户开放。

`/root` 默认权限是 `700`，脚本或 root 以外的账户可能无法进入，因此本方式需要以 `root`（或能读 `/root` 的账户）执行部署命令。若不想依赖 `/root`，可改成 `/srv/signdesk/data`、`/opt/signdesk/data` 等普通路径。

**已有数据目录**要沿用（例如从旧的 Compose 卷或别的机器搬来）：先停掉占用它的实例，备份，再确认 UID/GID `10001` 对目录和文件有读写权限，最后才把 `SIGNDESK_HOST_DATA` 指过去。不要用 `chmod 777` 或改回 root 运行来绕过权限报错。

镜像不会携带本机的 `C:\Users\<用户>\.signdesk\data`。转移配置可在页面导出后导入；完整导出包含明文凭证，导入必须先预览再确认，并会暂停定时。要保留完整执行历史，数据库复制必须在源、目标应用都停止后进行，并考虑 WAL 文件，且不要覆盖已有目标文件。

## 4. 导入并启动

在服务器的部署目录执行（需要 Docker Compose v2）：

```bash
cd ~/signdesk
cp .env.image.example .env
vi .env                               # 确认 SIGNDESK_HOST_DATA=/root/signdesk/data

docker load -i signdesk-0.1.0-linux-amd64.tar
docker compose -f compose.image.yml up -d
docker compose -f compose.image.yml ps
docker compose -f compose.image.yml logs --tail=100 signdesk
curl -fsS http://127.0.0.1:10500/api/system/status
```

导入时应显示 `Loaded image: signdesk:0.1.0`。启动后检查 API 返回 `ready: true`、容器健康状态，不能把 `docker load` 成功当成应用启动成功。

**验证挂载是否真的生效**（容器内外的文件名和大小应一致）：

```bash
docker inspect --format '{{range .Mounts}}{{.Source}} -> {{.Destination}}{{end}}' signdesk
sudo ls -la /root/signdesk/data       # 应出现 signdesk.db、instance.lock
```

如果这里出现 `instance.lock` 和 `signdesk.db`，说明挂载生效且写入的正是你指定的宿主目录。想临时改路径，也可不改 `.env` 而用一次性变量：

```bash
SIGNDESK_HOST_DATA=/root/signdesk/data docker compose -f compose.image.yml up -d
```

该变量只影响宿主机路径；容器内始终是 `/app/data`。修改 `SIGNDESK_HOST_DATA` 后需要重新创建容器（`up -d` 会重建），切换到一个**新的空目录等于开了一个空库**，不会带入原目录数据。

### 只有 Docker Engine，没有 Compose

准备好上述持久数据目录并导入 tar 后，也可以只用 Docker 命令启动，不必上传 Compose 和 env 文件。与 Compose 方式二选一，不要同时运行两个实例：

```bash
docker run -d --name signdesk \
  --restart unless-stopped --stop-timeout 40 \
  --memory 512m --pids-limit 256 \
  --cap-drop ALL --security-opt no-new-privileges:true \
  -p 127.0.0.1:10500:10500 \
  --mount type=bind,src=/root/signdesk/data,dst=/app/data \
  --health-cmd='curl -fsS --max-time 3 http://127.0.0.1:10500/api/system/status > /dev/null' \
  --health-interval=30s --health-timeout=5s --health-start-period=30s --health-retries=3 \
  signdesk:0.1.0
docker inspect --format '{{range .Mounts}}{{.Source}} -> {{.Destination}}{{end}}' signdesk
docker logs --tail=100 signdesk
curl -fsS http://127.0.0.1:10500/api/system/status
```

此方式用 `docker stop signdesk` 停机、`docker restart signdesk` 重启；默认端口同样不向公网发布。更新时先停旧容器并保留旧镜像，移除已停止的容器后用新镜像重新创建，宿主机的绑定数据目录不会随容器删除。

默认仅将端口发布到服务器 `127.0.0.1:10500`。从本地电脑访问，推荐建立 SSH 隧道：

```bash
ssh -N -L 10500:127.0.0.1:10500 user@server
```

保持该 SSH 连接打开，然后浏览器访问 `http://127.0.0.1:10500`（本地该端口需空闲）。应用没有登录鉴权，不应直接向公网开放。若使用可信私网入口或带认证的反向代理，还需单独验证同源写保护，不通过放宽安全过滤器解决代理配置问题。

`SIGNDESK_ALLOWED_HOSTS` 是**业务 HTTP 请求目标**的内网主机白名单，不是管理页面访问者白名单，也不是登录或网络访问保护。

## 5. 日常操作与更新

```bash
# 查看日志 / 停止 / 重启（停止会给 JVM 40 秒优雅退出时间）
docker compose -f compose.image.yml logs -f signdesk
docker compose -f compose.image.yml stop
docker compose -f compose.image.yml restart
```

不要使用 `kill -9` 作为正常停机方式。发送中断的结果必须保留为待确认，不能用自动重发掩盖。

更新前保留旧镜像标签，并在容器停止后备份数据库及存在的 WAL 文件。例：旧镜像仍为 `signdesk:0.1.0`，新版使用 `signdesk:release-1`：

```bash
docker tag signdesk:0.1.0 signdesk:previous
docker compose -f compose.image.yml stop
# 此时按操作系统权限管理方式备份 ~/.signdesk/data，勿在运行时只拷贝主 db。
docker load -i signdesk-0.1.0-linux-amd64.tar
SIGNDESK_IMAGE=signdesk:release-1 docker compose -f compose.image.yml up -d
docker compose -f compose.image.yml ps
curl -fsS http://127.0.0.1:10500/api/system/status
```

回退镜像（仅限新旧版本数据库结构兼容，不提供自动数据库迁移或恢复）：

```bash
docker compose -f compose.image.yml stop
SIGNDESK_IMAGE=signdesk:previous docker compose -f compose.image.yml up -d
curl -fsS http://127.0.0.1:10500/api/system/status
```

持续使用自定义标签时，应在部署目录 `.env` 中设置 `SIGNDESK_IMAGE`，或每次命令保持相同变量；避免后续无变量的 `up` 意外选择默认标签。不要删除持久数据目录来处理启动失败。

## 验证边界

Maven、前端构建与 tar 检查的实际执行结果见 [验证记录](validation.md)。本地无 Docker 时，即使 tar 成功生成，也不能宣称 Linux 容器实际启动、数据挂载权限、健康检查、容器内 TLS 或浏览器 E2E 已通过；这些需要在目标服务器验收。
