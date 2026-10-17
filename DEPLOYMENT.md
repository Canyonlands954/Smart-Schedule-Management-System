# 企业版部署与迁移

本版本新增 PostgreSQL pgvector 扩展及企业知识库。已有部署先按 [RAG 升级说明](RAG_GUIDE.md#部署与升级) 备份并构建数据库镜像，首次升级不要直接跳过镜像构建。普通模型与向量模型在管理员页面分别配置。

## 本机启动

需要 Java 17+、Maven 3.9+、Node.js 22.12+，以及运行中的 Docker Desktop（Linux 容器）。本机启动支持 Windows PowerShell 5.1 和 PowerShell 7。在 `enterprise` 目录执行：

```powershell
./start.ps1
```

如果脚本执行策略阻止运行，或提示无法识别 `pwsh`，可在同一目录执行：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\start.ps1
```

也可以双击 `start.cmd`，它会优先使用可用的 PowerShell 7，否则自动使用 Windows 自带的 PowerShell。

如果 Java、Maven 已安装但未加入 PATH，启动脚本也会从 `JAVA_HOME`、`MAVEN_HOME` 或 `M2_HOME` 对应的 `bin` 目录查找工具；Docker 会同时检查 Windows 上的常见安装目录。这些补充仅作用于本次启动进程。

默认前端地址 `http://127.0.0.1:8765/`，Java API 位于 `127.0.0.1:18865`，PostgreSQL 映射至 `127.0.0.1:55432`。首位管理员在本机注册；源码没有预设管理员密码。启动脚本会生成 `.env` 数据库密码，构建缺失的运行文件并记录应用进程。

停止前后端执行 `./stop.ps1`，数据库仍保留。需要停止数据库时执行 `docker compose stop postgres`；后续重新启动不会清空数据。不要执行删除数据卷的命令来代替日常停止。

启动脚本用于本机开发和预览。服务或页面未启动时检查 `.runtime` 内的输出与错误日志。8765 端口若由旧 Python 版本占用，先在旧版启动窗口按 Ctrl+C；脚本不会停止未知进程。

## 从旧版本迁移

先停止旧 Python 服务，并备份其整个 `data` 目录。新 PostgreSQL 必须没有用户和日程。首次启动企业版时运行：

```powershell
./start.ps1 -ImportSource '../multiuser/data/schedule.db'
```

如果已经启动空的 Java 服务，先执行 `./stop.ps1`，再带迁移参数启动。导入后不必每次继续带参数。导入保留原编号与密码摘要，成功登录后更新密码保存方式。不会更改旧 SQLite 文件；旧会话和旧邀请码不迁移，需要重新登录。

已经迁移的数据库仍运行时，旧 Python 版本不应继续接收实际业务操作；两套数据库不会双向同步。新版本已有业务数据时，导入器拒绝覆盖。要迁入其他数据库，应配置另一个 `DB_URL` 并自行管理对应 PostgreSQL 实例。

## 配置

`.env` 格式为每行 `KEY=value`，不加引号。启动脚本读取该文件到子进程环境。Java 直接运行时需由终端或服务管理器提供环境变量；Java 本身不自动读取 `.env`。

| 配置 | 用途 |
| --- | --- |
| `DB_PASSWORD` | PostgreSQL 密码，首次本机启动自动生成 |
| `DB_PORT` | 本机PostgreSQL端口，默认55432；Windows保留该端口时改用空闲端口，例如54329 |
| `DB_URL`、`DB_USER` | 默认 `jdbc:postgresql://127.0.0.1:55432/schedule`、`schedule` |
| `API_HOST`、`API_PORT` | 默认 `127.0.0.1`、`18865` |
| `AI_API_KEY` | 可选的服务器预置密钥；管理员尚未保存页面配置时使用 |
| `AI_BASE_URL` | 默认 `https://api.openai.com/v1`，不要包含 `/chat/completions` |
| `AI_MODEL` | 指定实际可用的模型；默认 `gpt-4.1-mini` |
| `AI_CONFIG_MASTER_KEY` | 可选的32字节随机主密钥的Base64编码；必须稳定保存且与数据库分开 |
| `AI_CONFIG_KEY_FILE` | 未设置主密钥环境变量时的密钥文件路径，默认 `.runtime/secrets/model-config.key` |
| `ALLOWED_ORIGINS` | 允许写请求的完整来源地址，多个地址用逗号分隔 |
| `SCHEDULE_SECURE_COOKIE` | 正式 HTTPS 访问时设为 `true` |
| `IMPORT_SOURCE` | 可选的只读 SQLite 数据导入路径 |
| `KNOWLEDGE_DIRECTORY` | 原文持久目录，默认 `.runtime/knowledge`；生产使用独立持久卷 |
| `KNOWLEDGE_WORKER_ENABLED` | 默认true，关闭后仅暂停领取资料处理任务 |
| `TEST_DB_URL` | 自动检查专用数据库地址，必须指向schedule_test |

管理员可直接在“账户与管理”保存地址、模型名称及API密钥。三项值作为整体使用 AES-256-GCM 加密后存入 `ai_model_settings`，密钥不通过读取接口回显。客户端不做可逆混淆，正式部署通过HTTPS传输输入。配置与调用都在服务端检查管理员身份，团队负责人使用规则解析。页面保存的配置优先于服务器预置变量，停用或清除后不会自动重新启用预置密钥。

若未设置 `AI_CONFIG_MASTER_KEY`，首次保存时自动生成独立的32字节随机主密钥文件，并限制文件访问权限为应用运行账号。数据库备份必须同时保留主密钥，但应分开存储；恢复数据库时使用原主密钥，不能删除或随意替换。密钥丢失或密文校验失败会拒绝解密，不生成替代密钥继续调用。原主密钥不包含在源码包中。

生产Compose配置将主密钥文件写入单独的 `model_secrets` 持久卷。多实例部署必须共享相同的主密钥或密钥文件，并分别设置访问权限。不要使用数据库密码派生主密钥。

兼容模型服务需支持聊天接口和 JSON 对象输出，等待超时35秒；已配置标签不代表真实模型联调成功。调用会向所配置的服务发送当前消息、最近对话及授权业务上下文。页面提供独立的固定消息连接测试，不发送业务资料，也不显示服务商原始错误响应。

改变 `.env` 中的数据库密码不会自动修改已存在数据卷内的 PostgreSQL 用户密码；实际改密需同时修改数据库用户与应用配置。源码包不包含本机 `.env`。

## 构建与测试

```powershell
cd frontend
npm ci
npm run build
cd ../backend
mvn package -DskipTests
```

构建结果为 `frontend/dist` 和 `backend/target/schedule-api-2.0.0.jar`。启动脚本复制 JAR 到 `.runtime` 再运行，避免 Windows 上运行中的文件阻止下一次构建。修改后端后停止并重新启动应用，脚本会根据源码和pom的修改时间重新构建；正在运行的服务不会被脚本强制替换。

后端测试使用单独的 `schedule_test` 数据库，先在本机 PostgreSQL 中创建它，并在运行 Maven 的环境设置 `.env` 中的 `DB_PASSWORD`：

```powershell
docker exec xushi-postgres psql -U schedule -d schedule -c 'CREATE DATABASE schedule_test'
# 在当前 PowerShell 中加载本机环境配置；不会显示密钥。
Get-Content .env | ForEach-Object {
  if ($_ -match '^([A-Z][A-Z0-9_]*)=(.*)$') {
    [Environment]::SetEnvironmentVariable($Matches[1],$Matches[2],'Process')
  }
}
mvn -f backend/pom.xml test
```

测试会清空 `schedule_test` 内的业务表，先校验实际数据库名称，拒绝作用于其他数据库。不要将测试数据库用于实际业务。可选浏览器检查需本地 Playwright，且只接受8877端口的隔离测试服务；前后端运行无需 Playwright。

## 构建后的容器部署

停止本机前后端，配置 `.env` 后：

```powershell
docker compose -f compose.yml -f compose.production.yml up -d --build
```

该方式由 Nginx 分发已构建的 Vue 页面，代理 Spring Boot API，API 连接 PostgreSQL；前端、API 与数据库分别运行。默认网站仅绑定服务器回环地址的8765端口。源码提供此构建配置，本机业务验证使用开发预览；正式部署前应在目标环境检查容器构建和完整流程。

内网或公网开放时，由运维配置监听地址和网络访问范围；写请求的浏览器来源必须加入 `ALLOWED_ORIGINS`，例如 `https://schedule.example.com`。HTTPS 场景配置 `SCHEDULE_SECURE_COOKIE=true`，并通过同一个HTTPS域名访问网页和API。不要将数据库端口开放到公网。

如果没有旧数据而要首次初始化容器部署，通过服务器本机网页创建管理员，再开放对外入口。后台看到的初始化来源依赖代理连接；因此初始化前不应开放注册入口到外网。

## 数据备份与恢复

业务数据在 PostgreSQL 数据卷中，不在源码目录的 SQLite 文件里。备份使用 PostgreSQL 逻辑导出，示例：

```powershell
docker exec xushi-postgres pg_dump -U schedule -d schedule -Fc -f /tmp/schedule-backup.dump
docker cp xushi-postgres:/tmp/schedule-backup.dump ./schedule-backup.dump
```

请给本地备份加上日期并妥善保存。这种文件复制避免旧版 PowerShell 重定向二进制输出的问题。备份包含业务数据，不能随源码公开分发。

恢复前停止前后端、保存当前数据库备份，再由运维将导出文件复制回容器，使用 `pg_restore` 恢复到经过确认的目标数据库。应在独立数据库中先验证恢复，不直接覆盖正在工作的数据库。正式使用时配置备份周期、保留策略、恢复检查和服务监控。
