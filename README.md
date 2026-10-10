# AgentHub 智能平台

> 基于 Spring Boot、Spring AI、MCP 与 Milvus 构建的 Multi-Agent 智能应用平台，支持动态路由、RAG 知识库问答、复杂任务编排、工具调用、长会话记忆与全链路可观测。

## 项目简介

AgentHub 面向传统 AI 应用流程单一、链路线性、复杂任务难以拆解与恢复等问题，构建基于 Multi-Agent 架构的智能应用平台。系统由 Router Agent 根据用户任务类型、复杂度和工具依赖动态选择执行链路：

- 简单问答、单步工具调用和即时推理任务进入 **ReAct Agent**。
- 多阶段、强依赖、需要计划和校验的复杂任务进入 **Plan-and-Execute Agent**。
- 意图不明确或关键参数缺失时触发澄清；低置信度路由默认回退 ReAct。

平台同时集成企业知识库 RAG、MCP 外部工具、Agent Skills、Checkpoint Saver、记忆管理和 Prometheus/Grafana 可观测能力，形成可扩展、可追踪、可恢复的 Agent 应用运行底座。

## 核心能力

### Multi-Agent 动态路由

- 设计 Router Agent，对用户请求进行任务类型识别、复杂度判断、工具依赖分析和上下文完整性检查。
- 动态调度 ReAct 或 Plan-and-Execute 架构，避免所有任务都走固定链路。
- 通过规则兜底、结构化 Schema 校验、路由白名单和低置信度降级策略提升路由稳定性。

### MCP 工具集成

- 通过 MCP 集成外部工具，统一管理远程工具与本地方法工具。
- 设计工具注册、发现、装配和调用机制，使 Agent 能自动选择合适工具完成任务。
- 支持内部文档检索、时间查询、订单查询、日志查询、指标查询、Skill 脚本执行等工具能力。

### RAG 知识库问答

- 支持多格式文档 ETL、分片、向量化和 Milvus 存储。
- 基于向量检索构建知识库问答链路，并通过多路召回与重排序优化知识召回质量。
- 搭建 RAG 评测集，知识库 Top-5 召回准确率达到 86%。

### 长任务与长会话管理

- 基于 Checkpoint Saver 支持任务中断恢复，复杂任务可从中间状态继续执行。
- 设计记忆管理机制，保障多轮对话上下文连贯。
- 通过滑动窗口、Token 预算控制和动态摘要压缩长会话上下文，降低上下文冗余对模型输出的影响。

### Prompt 与 Agent Skills

- 结合 Prompt 工程约束 Agent 输出结构、工具选择和异常处理方式。
- 通过 Agent Skills 扩展复杂任务执行能力，例如脚本执行、税务计算、知识资源读取等。
- 支持 Skill 描述、资源读取、脚本执行和结果回传。

### 全链路可观测

- 基于 Prometheus、Grafana、Tempo、Loki、OpenTelemetry 构建 Agent 全链路观测平台。
- 记录路由结果、执行耗时、工具调用、模型调用、SSE 首 Token 延迟和异常信息。
- 设计异常重试、超时控制、工具调用上限、模型调用上限等兜底机制，提升服务稳定性。

## 技术栈

| 类型 | 技术 |
| --- | --- |
| 后端框架 | Spring Boot 3.2.0 |
| Agent 框架 | Spring AI, Spring AI Alibaba Agent Framework |
| 模型服务 | DashScope |
| 向量数据库 | Milvus |
| 对象存储 | MinIO |
| 数据库 | MySQL |
| 工具协议 | MCP |
| 可观测 | Prometheus, Grafana, Tempo, Loki, OpenTelemetry |
| 文档处理 | PDFBox, 自定义文档 ETL |
| 前端 | HTML, CSS, JavaScript, SSE |

## 系统架构

```text
用户 / Web 页面
  ↓
ChatController
  ↓
Router Agent
  ├─ 规则兜底
  ├─ LLM 结构化路由
  ├─ Schema 校验
  └─ 路由白名单
  ↓
Agent Dispatch
  ├─ ReAct Agent
  │   ├─ 本地方法工具
  │   ├─ MCP 工具
  │   ├─ Agent Skills
  │   └─ RAG 检索
  │
  └─ Plan-and-Execute Agent
      ├─ Planner Agent
      ├─ Executor Agent
      └─ Supervisor Agent
  ↓
工具 / 知识库 / 数据源
  ├─ Milvus
  ├─ MySQL
  ├─ MinIO
  ├─ Prometheus
  ├─ CLS / 日志工具
  └─ Skill 脚本
```

## 核心模块

```text
src/main/java/org/example/
├── controller/
│   └── ChatController.java              # 统一对话、流式对话、文件上传等接口
├── agent/
│   ├── router/                          # Router Agent 与动态派发
│   └── tool/                            # 本地 Agent 工具
├── service/
│   ├── ChatService.java                 # ReAct Agent 构建与执行
│   ├── AiOpsService.java                # Planner/Executor/Supervisor 编排
│   ├── RagService.java                  # RAG 问答服务
│   ├── VectorIndexService.java          # 文档向量化入库
│   ├── VectorSearchService.java         # 向量检索
│   ├── DocumentChunkService.java        # 文档切分
│   └── SkillRegistry.java               # Agent Skills 注册
├── tool/
│   └── skill/                           # Skill 工具：读取 Skill、资源、执行脚本等
├── observability/
│   ├── AgentTelemetry.java              # Trace、Metric 封装
│   ├── AgentRuntimeGuard.java           # 运行时限制与保护
│   └── ToolObservabilityAspect.java     # 工具调用观测
└── config/                              # 安全、Milvus、DashScope、文件上传等配置
```

## 主要接口

### 流式对话

```http
POST /api/chat_stream
Content-Type: application/json

{
  "Id": "session-123",
  "Question": "总结上传文档中的核心内容"
}
```

说明：

- 支持 SSE 流式输出。
- 自动进入 Router Agent。
- 根据任务复杂度动态选择 ReAct 或 Plan-and-Execute。

### 普通对话

```http
POST /api/chat
Content-Type: application/json

{
  "Id": "session-123",
  "Question": "查找产品使用说明"
}
```

返回中包含 Agent 答案以及路由信息：

```json
{
  "success": true,
  "answer": "...",
  "route": "REACT",
  "taskType": "document_qa",
  "complexity": "MEDIUM",
  "routeConfidence": 0.78,
  "routeReason": "文档问答可由 ReAct 根据检索结果即时决策"
}
```

### 文件上传

```http
POST /api/upload
Content-Type: multipart/form-data

file=@document.pdf
```

说明：

- 支持 `txt`、`md`、`pdf`。
- 上传后执行文档解析、分片、Embedding 和 Milvus 入库。

### 会话管理

```http
POST /api/chat/clear
GET  /api/chat/session/{sessionId}
```

### 健康检查

```http
GET /milvus/health
GET /actuator/health
GET /actuator/prometheus
```

## 快速开始

### 环境要求

- JDK 17
- Maven 3.9+
- Docker / Docker Compose
- MySQL 8.x
- DashScope API Key

### 配置环境变量

项目支持从 `.env` 读取配置：

```bash
DB_PASSWORD=your-mysql-password
DASHSCOPE_API_KEY=your-dashscope-api-key
JWT_SECRET=replace-with-at-least-32-random-characters
TELEMETRY_HMAC_SECRET=replace-with-a-separate-random-secret
MINIO_ENABLED=true
MINIO_ENDPOINT=http://localhost:9000
MINIO_ACCESS_KEY=your-minio-access-key
MINIO_SECRET_KEY=your-minio-secret-key
MINIO_BUCKET=superbiz-documents
```

### 初始化数据库

```bash
mysql -uroot -p < sql/createTable.sql
```

如需补充迁移：

```bash
mysql -uroot -p ai_assistant_db < sql/migrate_request_id.sql
mysql -uroot -p ai_assistant_db < sql/migrate_user_isolation.sql
```

### 启动 Milvus 与 MinIO

```bash
docker compose -f vector-database.yml up -d
```

包含：

- Milvus
- MinIO
- etcd
- Attu

Attu 地址：

```text
http://localhost:8000
```

### 启动应用

```bash
mvn spring-boot:run
```

如果本机默认 JDK 不是 17，可以显式指定：

```bash
JAVA_HOME=/path/to/jdk17 mvn spring-boot:run
```

访问：

```text
http://localhost:9900
```

## 可观测平台

启动 Prometheus、Grafana、Tempo、Loki 和 OpenTelemetry Collector：

```bash
docker compose -f observability/docker-compose.yml up -d
```

Grafana 默认地址：

```text
http://localhost:3000
```

核心观测指标：

- Agent 路由结果
- Agent 执行耗时
- 模型调用次数
- 工具调用次数
- SSE 首 Token 延迟
- 工具异常与超时
- 工作流成功率与失败率

## 项目成果

- 构建基于 Multi-Agent 的动态路由智能应用平台，支持 ReAct 与 Plan-and-Execute 两类执行链路。
- 完成 MCP 工具接入、Agent Skills 扩展、RAG 知识库问答、任务恢复、记忆管理和全链路观测。
- 通过多路召回、重排序和评测集建设优化知识库效果，Top-5 召回准确率达到 86%。
- 通过异常重试、超时控制、调用上限和降级策略提升服务稳定性。

## 常用命令

```bash
# 启动向量数据库
make up

# 启动 Spring Boot 服务
make start

# 停止服务
make stop

# 上传 aiops-docs 文档
make upload

# 启动可观测平台
make observability-up
```

## 许可证

MIT
