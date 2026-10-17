# 企业知识库与 RAG 使用、部署说明

RAG 将已发布、有效且有权访问的资料作为回答依据。知识资料不会替代日程数据库、权限规则或最终冲突校验；“带上全组”仍只向指定负责人创建一项日程。

## 管理员首次使用

1. 进入“账户与管理”，配置并测试现有的问答模型。
2. 配置“知识库向量模型”的基础地址、模型名称、实际返回维度、密钥。与聊天接口分别配置；基础地址不包含 `/embeddings`。测试成功后再处理资料。接口需返回浮点数组；本系统校验返回数量、索引、维度及有限非零向量。
3. 进入“企业知识库”，新建知识库，设置全员、指定团队或私有范围，也可另外授权指定用户。管理员可管理所有知识库。
4. 上传 PDF、DOCX、Markdown 或 UTF-8 TXT。单文件不超过10MB，PDF不超过200页，提取正文最多30万字符、600片段。扫描件需先识别文字；旧 `.doc` 文件先转换为 `.docx`。
5. 只有明确勾选“允许将正文片段发送至已配置的向量及问答模型服务”的版本，才可发送至这些模型。未勾选的版本只在本站解析并提供关键词检索，普通用户检索始终不调用外部模型。
6. 等待后台处理，进入“版本与发布”，检查提取正文，必要时下载该版本原文核对。确认无误后发布。
7. 切换“知识问答”，输入问题，查看回答及引用。点击来源卡片，服务器重新校验权限和资料有效性，再返回对应原文。
8. 需要安排工作时点击“带入日程助手”，补充真实接收人、日期和地点。也可在日程助手勾选“结合企业知识库提供安排建议”。每项建议需要点击“采用建议”，不会自动补入未知时长或清单；确认派发时再次检查实时冲突。

没有向量配置时，允许模型处理的资料仍可通过关键词检索进入问答。没有问答模型或模型失败时展示可核对的原文来源。没有可靠依据时明确说明资料不足 。

## 版本与权限

原文和解析片段按版本保存。上传新版本、处理失败都不会替换当前发布版本；只有管理员发布才会切换。版本只能在生效日期当天或之后、失效日期之前发布，未来版本先保存，生效当天人工发布。

权限继承知识库。检索在 SQL 中限制访问范围，只读取当前发布且有效的版本；引用预览和下载也各自检查权限。修改知识库权限、发布替换、停用或删除资料后，旧问答再次读取会隐藏原回答和建议，要求重新检索。已送达浏览器并被用户阅读的信息无法远程撤回。

问答记录按发起人隔离，其他管理员也不能读取别人的记录。审计保存操作类型和资料编号，不记录密钥、原始片段或模型服务商错误正文。资料本身、解析片段和问答正文属于业务数据，数据库和文件备份按同等权限保护。

## 部署与升级

数据库镜像由 `database/Dockerfile` 构建，在 `postgres:17-alpine` 中安装固定版本的 pgvector，保留原 PostgreSQL 主版本及 Alpine 基础。Flyway V4 创建向量扩展及知识库相关表。已有数据库需先备份，再构建和重建容器，不删除数据卷。

```powershell
docker exec xushi-postgres pg_dump -U schedule -d schedule -Fc -f /tmp/before-rag.dump
docker cp xushi-postgres:/tmp/before-rag.dump ./before-rag.dump
./stop.ps1
docker compose build postgres
docker compose up -d postgres
./start.ps1
```

以上适用于随项目部署的 PostgreSQL 17。使用自管 PostgreSQL 时，先由数据库管理员安装与实际 PostgreSQL 主版本对应的 pgvector 扩展，再启动后端执行迁移。先在数据库副本上演练恢复和升级。

本机原文默认保存在 `.runtime/knowledge`，不进入源码包。生产 Compose 使用独立的 `knowledge_files` 持久卷，容器以应用账号运行，原文目录和文件限制访问权限。多实例必须共享原文持久存储和加密主密钥；数据库任务表通过租约和 `SKIP LOCKED` 领取任务。

| 配置 | 含义 |
| --- | --- |
| `KNOWLEDGE_DIRECTORY` | 原文持久目录，默认 `.runtime/knowledge` |
| `KNOWLEDGE_WORKER_ENABLED` | 是否领取入库任务，默认 `true` |
| `DB_PORT` | 本机数据库映射端口，默认55432；若被Windows保留可改用空闲端口 |
| `TEST_DB_URL` | 自动化测试专用数据库地址，必须指向 `schedule_test` |

向量配置采用与现有模型配置相同的 AES-256-GCM 加密方案，读取接口不返回密钥。主密钥沿用 `AI_CONFIG_MASTER_KEY` 或 `AI_CONFIG_KEY_FILE`。备份需要同时保存 PostgreSQL、原文目录、主密钥，并将主密钥与数据库备份分开保管。不要删除密钥来“重新配置”。

更换向量服务地址、模型或维度会产生新的向量空间标识。旧资料的向量不会参与新空间检索，关键词检索仍可用。逐个版本执行“重新处理”，完成后原子替换其片段和向量；密钥轮换不改变向量空间。重新处理已发布版本时，旧片段保留到新结果成功提交，失败也保留原有可检索内容。

后台任务遇到暂时性向量接口错误最多尝试3次；进程异常退出后，租约过期任务可被继续领取。解析失败需人工修正文档并重试或上传新版本。入库网络等待不占用日程写事务，生成回答也不直接派发安排。

Nginx 对上传路径允许11MB请求，后端仍将文件限制为10MB，普通JSON接口维持原有体积限制。知识问答及辅助日程的代理等待上限调整为150秒。

## 检索、引用与评估

首版采用中文短语/二字词及英文标识符匹配与精确向量检索，再通过 RRF 融合排序、去重，最多提供6个片段。分段上限700字符、重叠80字符，尽量在句号处断开。当前不依赖外部搜索服务或重排序服务，也没有自动生成 HNSW 索引；增加近似索引前应针对权限过滤范围验证召回。

模型输出拆分为带来源的事实与建议；服务器检查来源编号来自本次检索、引用文字确实出现在片段中，并限制建议类型和长度。此检查不能证明所有生成语义都正确，使用者仍可核对原文；模型提供商或知识资料变更后需重新评估。文档中的指令不能触发系统写入或改变业务权限。

“知识服务使用情况”记录近30天次数、检索与完整响应平均耗时、依据不足次数、使用反馈，以及服务商确实返回的问答Token数。没有Token用量时显示“未返回”；不虚构费用。向量处理费用、重试产生的费用和最终账单以模型服务商记录为准。

自动检查包括：真实 PostgreSQL 向量运算、资料上传与发布、跨用户权限、引用失效、外部发送许可、失败版本保留旧发布、不同向量空间隔离、多轮时长确认和单接收人派发；PDF/Word解析、向量接口协议另有单元检查。浏览器检查使用隔离测试库及本地兼容接口，不能代表真实供应商的回答质量。

浏览器检查依赖已固定在前端开发依赖中。先执行 `npm ci --prefix frontend`；Windows优先使用已安装的Chrome，也可设置 `PLAYWRIGHT_EXECUTABLE`。其他环境可在frontend目录执行 `npx playwright install chromium`。在隔离的 `schedule_test` 空库启动API（18866）与Vite（8877，`API_PROXY_TARGET=http://127.0.0.1:18866`），并将8877加入API的 `ALLOWED_ORIGINS`，再从enterprise目录依次执行 `node checks/browser.cjs` 和 `node checks/rag-browser.cjs`。前者注册测试账号，后者上传测试资料并使用本机模拟模型；两者都拒绝运行在正常8765端口。

Windows可在后端测试通过后直接运行 `./checks/browser.ps1`：它会重置专用的 `schedule_test` 数据库，自动启动隔离服务、执行两组浏览器检查，再停止本次创建的进程。该库只用于自动检查。

实际部署前，建议用企业自己的60—100个问题建立评估集，覆盖制度、项目名、简称、模糊表达、无答案、过期资料、权限隔离和文档内恶意指令。分别记录正确资料是否进入前6名、首位命中、回答是否受引用支持、应拒答时是否拒答、越权片段数量、延迟和Token数。参数调整以这组问题的实际结果为准。

## 主要接口

| 路径（均位于 `/api`） | 用途 |
| --- | --- |
| `GET/POST /knowledge/bases`、`PUT /knowledge/bases/{id}` | 知识库及权限 |
| `GET /knowledge/documents` | 可访问资料列表 |
| `POST /knowledge/documents`、`POST /knowledge/documents/{id}/versions` | multipart上传，字段为file和JSON字符串metadata |
| `GET /knowledge/documents/{id}/versions` | 管理员查看版本和任务状态 |
| `GET /knowledge/versions/{id}/preview?offset=0`、`GET /knowledge/versions/{id}/file` | 管理员发布前检查正文或下载原文 |
| `POST /knowledge/documents/{id}/publish`、`unpublish`、`delete` | 带资料revision的版本操作 |
| `POST /knowledge/versions/{id}/retry` | 重试处理或重新生成向量 |
| `POST /knowledge/search`、`POST /knowledge/ask` | 权限检索、管理员生成问答 |
| `GET /knowledge/chunks/{id}` | 重新校验权限的已发布引用 |
| `GET /knowledge/documents/{id}/file` | 下载当前有效发布原文 |
| `GET /knowledge/queries/{id}`、`POST /knowledge/queries/{id}/feedback` | 本人问答记录、反馈 |
| `GET /knowledge/stats` | 管理员统计 |
| `GET/PUT/DELETE /admin/embedding-config`、`POST /admin/embedding-config/test` | 向量模型配置与测试 |

现有 `/ai/parse` 新增 `use_knowledge` 或本人 `knowledge_query_id`，只生成辅助依据及待确认建议，不绕过现有日程派发接口。
