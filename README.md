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
- **ReAct 工具调用**：基于 ReAct 模式的多轮工具编排，支持连续调用多个工具完成复杂任务
- **自由对话模式**：通用 Free Chat 模式，可直接调用命令行、Python 代码执行、网络搜索等工具

### Workspace 持久记忆

- **长期记忆**：跨会话持久化用户偏好、项目决策、执行规范等信息
- **记忆提取**：对话结束时自动从历史中提取值得长期保存的关键信息
- **记忆整理**：定期压缩合并重复记忆、淘汰过时条目，控制总字符数
- **Dreaming 机制**：后台定期整理 workspace 中的记忆文件，保持知识库精简

### MCP 工具服务

- **文件管理工具**：支持文件读写、追加、搜索替换、目录遍历、批量操作等 10 种操作
- **远程 MCP 代理**：通过 HTTP 协议代理外部 MCP 服务（Terminal、Code Interpreter、Editor 等）
- **自动注册与重连**：远程 MCP 服务自动发现注册，连接失败定时重试
- **文件上传**：前端支持单文件/批量文件上传到 MCP workspace

### 模型管理

- **多模型路由**：支持 DeepSeek、通义千问（百炼）、SiliconFlow、Ollama 等多供应商
- **DeepSeek 专属客户端**：针对 DeepSeek 模型特性定制的 Chat Client
- **百炼 Embedding 客户端**：针对阿里云百炼平台优化的向量化客户端，支持批量 Embedding
- **容错降级**：优先级调度、首包探测、健康检查、自动降级，单模型故障不影响服务
- **多模型类型**：Chat、Embedding、Rerank 三类模型独立配置与调度

### 文档入库 ETL

- **Pipeline 编排**：节点式流水线，支持抓取→解析→增强→分块→向量化→写入全流程
- **多数据源**：本地文件、HTTP URL、S3 对象存储、飞书文档
- **文档解析**：基于 Apache Tika，支持 PDF、Word、PPT、Excel 等常见格式
- **图片 OCR**：基于多模态 LLM 的图片文字识别，支持 PDF 中嵌入图片的自动提取与识别
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
