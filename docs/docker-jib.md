# Docker 部署操作指南

本机需要 JDK 21、Maven 3.9+、Node 22.12+；服务器需要 Docker 和 Compose v2。以下以 root 部署到 `/root/signdesk` 为例，`server` 替换为服务器 IP。

## 1. 打包与上传

在本地仓库根目录执行：

```bash
mvn -f backend/pom.xml -Pjib clean verify
```

产物：`backend/target/jib/signdesk-0.1.0-linux-amd64.tar`，镜像标签：`signdesk:0.1.0`。不要使用 `jib:build`，它会推送镜像仓库；ARM64 服务器增加 `-Dsigndesk.image.architecture=arm64` 并替换下面的 tar 文件名。

首次部署，在本地 PowerShell 上传三个文件：

```powershell
ssh root@server "mkdir -p /root/signdesk"
scp .\backend\target\jib\signdesk-0.1.0-linux-amd64.tar .\compose.yml .\.env.example root@server:/root/signdesk/
```

## 2. 配置

在服务器执行：

```bash
cd /root/signdesk
install -d -m 700 -o 10001 -g 10001 /root/signdesk/data
test -e .env || (umask 077 && cp .env.example .env)
chmod 600 .env
vi .env
```

编辑 **`.env`**，不是 `.env.example`；**必须填写自己的强密码，留空无法启动**：

```dotenv
SIGNDESK_IMAGE=signdesk:0.1.0
SIGNDESK_HOST_DATA=/root/signdesk/data
SIGNDESK_PUBLISH_ADDRESS=0.0.0.0
SIGNDESK_PORT=10500
SIGNDESK_AUTH_USERNAME=admin
SIGNDESK_AUTH_PASSWORD=
SERVER_SERVLET_SESSION_COOKIE_SECURE=false
SIGNDESK_ALLOWED_HOSTS=
```

- `SIGNDESK_HOST_DATA`：宿主机数据目录，挂载到容器 `/app/data`；更新时不要改路径。
- 密码建议用单引号包住，例如 `SIGNDESK_AUTH_PASSWORD='你自己的长随机密码'`。
- HTTP 访问保持 Cookie Secure 为 `false`；HTTPS 设为 `true`，公网长期使用建议 HTTPS。
- `SIGNDESK_ALLOWED_HOSTS`：业务请求的可选内网目标白名单，不是页面访问白名单，通常留空。

## 3. 启动与访问

```bash
cd /root/signdesk
docker load -i signdesk-0.1.0-linux-amd64.tar
docker compose config --quiet
docker compose up -d --no-build
docker compose ps
curl -fsS http://127.0.0.1:10500/api/health
```

配置检查无输出表示通过，健康接口应返回 `{"ready":true}`。在云安全组和服务器防火墙放通 **TCP 10500**，浏览器打开：

```text
http://服务器公网IP:10500
```

使用 `.env` 中的账号密码登录，前端和 API 都是这个地址。

## 4. 更新

1. 本地重新执行第 1 节的 Maven 打包命令，**只上传新 tar，不覆盖服务器 `.env`**：

   ```powershell
   scp .\backend\target\jib\signdesk-0.1.0-linux-amd64.tar root@server:/root/signdesk/
   ```

2. 页面暂停定时，等待队列空闲，在服务器停机备份数据：

   ```bash
   cd /root/signdesk
   umask 077
   backup_dir=$(mktemp -d /root/signdesk/backup-XXXXXXXX)
   docker compose stop && \
     tar -C /root/signdesk/data -cpf "$backup_dir/data.tar" . && \
     cp -p .env compose.yml "$backup_dir/"
   ```

   若配置了其他数据目录，替换 tar 的路径；**停机和备份都成功后才继续**。

3. 用新 tar 覆盖同名镜像标签 `signdesk:0.1.0`，强制重建容器，不另保留旧镜像标签：

   ```bash
   docker load -i signdesk-0.1.0-linux-amd64.tar
   docker compose up -d --no-build --force-recreate
   docker compose ps
   curl -fsS http://127.0.0.1:10500/api/health
   ```

4. 重新登录页面，确认数据和功能正常，再按需恢复定时。

## 5. 常用操作

在 `/root/signdesk` 执行：

| 操作 | 命令 |
|---|---|
| 查看状态 | `docker compose ps` |
| 查看日志 | `docker compose logs --tail=100 signdesk` |
| 实时日志 | `docker compose logs -f signdesk` |
| 停止 | `docker compose stop` |
| 启动原容器 | `docker compose start` |
| 重启原容器 | `docker compose restart` |
| 修改 `.env` 后生效 | `docker compose up -d --no-build --force-recreate` |
| 验证数据挂载 | `docker inspect --format '{{range .Mounts}}{{.Source}} -> {{.Destination}}{{end}}' "$(docker compose ps -q signdesk)"` |

不要删除数据目录、执行 `down -v` 或用空目录替代已有数据；旧具名卷的数据需要先停机备份并迁移，再改用绑定目录。

## 6. 直接从源码构建

同样使用 `compose.yml` 和 `.env`，按第 2 节配置后，在完整源码仓库执行：

```bash
docker compose up -d --build
```

源码更新先按第 4 节停机备份，再执行 `docker compose up -d --build --force-recreate`。
