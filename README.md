# 序时企业版 · Vue / Spring Boot / PostgreSQL

本版本将原 Python 后端迁为 Java Spring Boot，前端迁为 Vue 3 + TypeScript + Vue Router + Pinia，数据库迁为 PostgreSQL。前后端可独立构建与部署，运行时不依赖 Python。

核心功能包括真实注册登录、团队负责人权限、管理员加密模型配置、多轮 AI 澄清、已有日程的上下文改期、带解释的候选排期、单接收人安排、准备清单、执行反馈、通知、日志、日历导出和协作统计。保留深色导航、浅色工作区及统一蓝色操作控件。

新增企业知识库：权限内的混合检索、文档版本发布、带引用问答及需要明确采用的日程建议。接入与部署步骤见 [RAG 使用与部署说明](RAG_GUIDE.md)，`examples/` 提供可修改后上传的演示资料。

**“带上全组参会”仅写入指定接收人的一项日程，由接收人组织成员。** 系统不会给全组自动创建日程或发送通知。

## 开始使用

本机需要 Java 17+、Maven 3.9+、Node.js 22.12+ 和已启动的 Docker Desktop。本机启动兼容 Windows 自带的 PowerShell 5.1，也支持 PowerShell 7。

在本目录运行 `./start.ps1`，或双击 `start.cmd`，访问 `http://127.0.0.1:8765/`。首次启动会生成本机数据库密码、启动 PostgreSQL，并构建和启动前后端。首次使用在本机注册管理员。

如果终端提示无法识别 `pwsh`，在本目录执行 `powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\start.ps1`，或双击 `start.cmd`；启动入口会在找不到 PowerShell 7 时自动使用 Windows PowerShell。

已有版本的数据导入、生产构建、AI 配置与备份见 [部署说明](DEPLOYMENT.md)。当前架构、事务与兼容边界见 [架构说明](ARCHITECTURE.md)，操作流程见 [使用说明](USER_GUIDE.md)。

## 工程布局

- `frontend/src/pages/`：十个工作页面及登录注册。
- `frontend/src/components/`：日程编辑器、详情及实体表单。
- `frontend/src/stores/`：登录、共享工作数据与当前 AI 会话；对话草稿同时保存到服务器。
- `frontend/src/services/`、`domain/`：HTTP 客户端、类型、日期与状态转换。
- `backend/src/main/java/com/xushi/`：按认证、组织、日程、AI、协作、数据迁移拆分。
- `backend/src/main/resources/db/migration/`：Flyway 数据库版本脚本。
- `backend/src/main/java/com/xushi/knowledge/`：资料入库、权限检索、向量接口、引用问答及后台任务。
- `database/`：与 PostgreSQL 17 Alpine 数据卷兼容的 pgvector 镜像构建。
- `backend/src/test/`：使用独立 PostgreSQL 测试库的检查。
- `checks/browser.cjs`：隔离浏览器业务与布局检查。
- `compose.yml`：本机 PostgreSQL；`compose.production.yml`：可选的 API 与 Nginx 容器部署。

源码包不包含 `.env`、旧数据库、PostgreSQL 数据卷、依赖目录、运行日志或个人数据。旧 Python 版本留在原来的 `multiuser` 目录，作为数据迁移对照。
