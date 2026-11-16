# AgentScope 学习项目

基于 Java 17、Spring Boot 4.1.2 和 AgentScope Java 2.0.3 的 Maven 多模块项目。

## 项目结构

```text
agent-scope-study/
├── pom.xml                 # 父 POM：聚合模块、管理依赖版本和编译配置
├── base/                   # 已有基础示例、Spring Boot 接口、测试及学习笔记
│   ├── pom.xml
│   ├── README.md
│   └── src/
├── rag/                    # 文档加载、向量检索与 RAG
├── rag-persistent/         # MyBatis-Plus + pgvector 片段持久化与 RAG API
├── mcp/                    # MCP 外部工具接入
├── multi-agent/            # 多 Agent 协作
└── skills/                 # Agent Skill 加载与使用
```

| 模块 | artifactId | 代码包 | 依赖与用途 |
| --- | --- | --- | --- |
| `base` | `agent-scope-study-base` | `com.laoshiren.agent` | 保留基础示例、Spring MVC 流式接口和 Spring Boot 测试 |
| `rag` | `agent-scope-study-rag` | `com.laoshiren.agent.rag` | Core、OpenAI 模型扩展和 `agentscope-extensions-rag-simple` |
| `rag-persistent` | `rag-persistent` | `com.laoshiren.agent.persistent` | MyBatis-Plus、PostgreSQL pgvector、AgentScope 向量化和问答；见 [运行说明](rag-persistent/README.md) |
| `mcp` | `agent-scope-study-mcp` | `com.laoshiren.agent.mcp` | Core 与 OpenAI 模型扩展；MCP SDK 由 Core 传递引入 |
| `multi-agent` | `agent-scope-study-multi-agent` | `com.laoshiren.agent.multiagent` | Core 与 OpenAI 模型扩展，用于后续多 Agent 示例 |
| `skills` | `agent-scope-study-skills` | `com.laoshiren.agent.skills` | Core 与 OpenAI 模型扩展，用于后续 Skill 示例 |

`base`、`rag` 和 `rag-persistent` 为 Spring Boot 模块。`rag` 已提供关键词检索、文本向量、Top K 和向量 RAG 示例；`rag-persistent` 提供知识片段持久化、检索和问答 API；`mcp`、`multi-agent`、`skills` 为普通 JAR 学习模块，已配置基础依赖。




| 模块 | artifactId                           | 
| --- |--------------------------------------| 
| `base` | [基础笔记](./base/README.md)             | 
| `rag` | [RAG基础](./rag/README.md)             | 
| `rag-persistent` | [RAG持久化](./rag-persistent/README.md) | 
| `mcp` | 未开始                                  | 
| `multi-agent` | 未开始      | 
| `skills` | 未开始           | 


