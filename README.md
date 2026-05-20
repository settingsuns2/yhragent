# Ragent

企业级 Agentic RAG 智能问答平台，基于 Java 17 + Spring Boot 3 + React 18 构建，覆盖从文档入库到智能问答的全链路能力。

![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)
![Java](https://img.shields.io/badge/Java-17-ff7f2a.svg)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.x-6db33f.svg)
![React](https://img.shields.io/badge/React-18-61dafb.svg)

## 核心功能

### 智能问答

- **多路检索引擎**：意图定向检索 + 全局向量检索并行执行，结果经去重、重排序后处理，兼顾精准度与召回率
- **意图识别与引导**：树形多级意图分类（领域→类目→话题），置信度不足时主动引导澄清
- **问题重写**：多轮对话自动补全上下文，复杂问题拆分为子问题分别检索
- **会话记忆管理**：保留近 N 轮对话，超限自动摘要压缩，控制 Token 成本
- **MCP 工具集成**：意图非知识检索时自动提参调用业务工具，检索与工具调用无缝融合

### 模型管理

- **多模型路由**：支持 DeepSeek、通义千问、SiliconFlow、Ollama 等多供应商
- **容错降级**：优先级调度、首包探测、健康检查、自动降级，单模型故障不影响服务
- **多模型类型**：Chat、Embedding、Rerank 三类模型独立配置与调度

### 文档入库 ETL

- **Pipeline 编排**：节点式流水线，支持抓取→解析→增强→分块→向量化→写入全流程
- **多数据源**：本地文件、HTTP URL、S3 对象存储、飞书文档
- **文档解析**：基于 Apache Tika，支持 PDF、Word、PPT、Excel 等常见格式
- **分块策略**：固定大小分块、结构感知分块，灵活配置

### 管理后台

- 知识库管理（创建、文档上传、分块查看）
- 意图树编辑（多级意图节点配置）
- 文档入库监控（Pipeline 配置、任务状态跟踪）
- 全链路追踪（重写、意图、检索、生成各环节 Trace 记录）
- Dashboard 数据概览
- 系统设置

![](assets/qa-home.png)

## 技术架构

后端按职责分为四个 Maven 模块：

<img src="assets/ragent-module-layering.png" width="50%" />

| 层面 | 技术选型 |
| --- | --- |
| 后端框架 | Java 17、Spring Boot 3.5.x、MyBatis Plus |
| 前端框架 | React 18、Vite、TypeScript |
| 关系数据库 | PostgreSQL |
| 向量数据库 | Milvus 2.6 / PGVector |
| 缓存/限流 | Redis + Redisson |
| 消息队列 | RocketMQ 5.x |
| 文档解析 | Apache Tika 3.x |

## 项目结构

```
ragent/
├── bootstrap/          # 业务模块（RAG核心、知识库、入库、管理后台）
├── framework/          # 通用基础框架（工具类、异常处理、雪花ID）
├── infra-ai/           # AI 基础设施（多模型客户端、路由、Embedding、Rerank）
├── mcp-server/         # MCP 工具服务器（文件管理、远程 MCP 代理）
├── frontend/           # React 前端
├── resources/
│   ├── database/       # 数据库 Schema 和初始化数据
│   └── docker/         # Docker Compose 编排文件
└── docs/               # 文档
```

## 快速开始

1. 准备基础设施（PostgreSQL、Redis、Milvus），可使用 `resources/docker/` 下的 Docker Compose 文件快速启动

2. 复制配置模板并填入你的 API Key：
```bash
cp bootstrap/src/main/resources/application.yaml.example bootstrap/src/main/resources/application.yaml
cp mcp-server/src/main/resources/application.yml.example mcp-server/src/main/resources/application.yml
```

3. 编辑 `application.yaml`，填入你的模型供应商 API Key

4. 启动后端服务：
```bash
./mvnw spring-boot:run -pl bootstrap
```

5. 启动前端：
```bash
cd frontend
npm install
npm run dev
```

## License

Apache License 2.0
