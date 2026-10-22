# 本机接口测试

适用于当前 Vue 3、Spring Boot、PostgreSQL 版本。后端默认地址为 `http://127.0.0.1:18865`，网页为 `http://127.0.0.1:8765`。接口使用 Cookie 登录会话，不使用 Bearer Token；模型供应商的 API Key 不能代替平台登录。

## 一、用 Windows 自带 PowerShell 检查

打开 Docker Desktop，等待引擎就绪。在 `enterprise` 目录启动项目：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\start.ps1
```

只检查服务响应、数据库初始化状态和未登录拦截，不需要账号：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\checks\api-smoke.ps1 -PublicOnly
```

测试登录、Cookie、工作区、排期策略、冲突报告、知识库列表和角色权限：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\checks\api-smoke.ps1 -Username 你的平台账号
```

按提示输入密码，输入内容不显示、不保存。使用已有平台账号；首次使用请先打开网页创建管理员。管理员会检查管理接口及密钥不回显，普通用户会验证管理接口返回403。脚本只显示请求路径、响应状态和可见日程数量，不打印业务内容、密码、Cookie或模型密钥。脚本登录后会在结束时退出该次会话，不影响浏览器的其他登录会话。旧账号首次登录可能触发系统已有的密码摘要升级。

每项显示 `[PASS]` 才代表符合预期。未登录访问工作区的401、普通用户访问管理接口的403都是预期结果。任一检查失败，脚本以非零退出码结束；服务未启动时提示先启动。

脚本默认不会请求外部模型。管理员已经在“账户与管理”保存并启用模型配置后，可显式测试真实模型接口：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\checks\api-smoke.ps1 -Username 你的管理员账号 -TestModels
```

该模式使用后端已加密保存的配置，不需在终端填写 API Key。普通模型和向量模型分别测试；未配置或未启用的项目显示 `[SKIP]`，不代表模型联调通过。测试使用固定内容，可能产生少量模型调用费用。模型兼容性测试通过也不等于实际业务问题的准确率达标。

可将 `-BaseUrl http://127.0.0.1:8765` 用于验证网页代理入口；隔离验收服务可使用18866或8877端口。完整Docker部署只公开8765端口，应使用8765。不要混用localhost和127.0.0.1，以免Cookie域不同。

## 二、手动请求

健康检查：

```powershell
Invoke-RestMethod http://127.0.0.1:18865/api/health
```

预期 `ok=true`、`architecture=spring-boot-postgresql`。这个接口仅表示Java服务响应，不能单凭它确认数据库和所有业务正常；应继续检查初始化状态或使用上述脚本。

```powershell
Invoke-RestMethod http://127.0.0.1:18865/api/auth/status
```

手动登录并保留Cookie，然后查询工作区：

```powershell
$taskAccount = Get-Credential -Message '输入平台账号和密码'
$taskBody = @{
    username = $taskAccount.UserName
    password = $taskAccount.GetNetworkCredential().Password
} | ConvertTo-Json
try {
    Invoke-RestMethod -Method Post `
        -Uri http://127.0.0.1:18865/api/auth/login `
        -ContentType 'application/json; charset=utf-8' `
        -Body ([Text.Encoding]::UTF8.GetBytes($taskBody)) `
        -SessionVariable taskApiSession
} finally {
    $taskBody = $null
}
Invoke-RestMethod -Uri http://127.0.0.1:18865/api/state -WebSession $taskApiSession
Invoke-RestMethod -Method Post -Uri http://127.0.0.1:18865/api/auth/logout `
    -ContentType 'application/json' -Body '{}' -WebSession $taskApiSession
```

也可使用Postman桌面客户端：先发送 `POST /api/auth/login`，Body选raw JSON并填写 `username`、`password`；随后在同一主机名下发送 `GET /api/state`，保留默认Cookie管理。网页版本需能访问本机的桌面代理。不要在日志、截图或仓库中保存真实密码和Cookie。

## 三、需要写入数据的完整回归

增删改、单接收人派发、并发冲突、改期确认、RAG发布等由后端集成测试和隔离浏览器流程覆盖。这些检查会重置 `schedule_test`，只能使用测试库，不能将其用于真实业务。

数据库容器应已启动，`schedule_test` 应已存在。首次需要时可执行：

```powershell
docker exec xushi-postgres psql -U schedule -d schedule -c 'CREATE DATABASE schedule_test'
```

数据库已存在时不必重复创建。加载本机配置，并显式指定测试库：

```powershell
Get-Content .env | ForEach-Object {
    if ($_ -match '^([A-Z][A-Z0-9_]*)=(.*)$') {
        [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2], 'Process')
    }
}
$taskDatabasePort = if ($env:DB_PORT) { $env:DB_PORT } else { '55432' }
$env:TEST_DB_URL = "jdbc:postgresql://127.0.0.1:$taskDatabasePort/schedule_test"
mvn -f backend/pom.xml test
```

通过后，在Java、Maven、Node.js和Docker均可从PATH找到的终端执行：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\checks\browser.ps1
```

浏览器脚本使用测试库、API18866和网页8877；结束后停止自己启动的服务。真实工作区的8765/18865不参与写入回归。测试模型使用本机模拟服务，不使用真实供应商密钥。

## 常见结果

| 结果 | 含义 |
| --- | --- |
| 连接失败 | 服务未启动，或地址/端口不正确 |
| 401 | 未登录、Cookie未携带或登录已失效 |
| 403 | 当前角色无权限，或写请求的Origin不在允许列表 |
| 409 | 资源版本已变化，或存在业务冲突，需读取最新状态 |
| 415 | 写请求缺少application/json；文件上传应使用multipart |
| 502 | 模型服务未返回可接受的结果，检查地址、模型、密钥及兼容性 |
| 503 | 可能为主密钥或加密配置不可用，以接口返回说明为准 |

数据库连接、部署与备份另见 [DEPLOYMENT.md](DEPLOYMENT.md)。
