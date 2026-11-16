# 基础学习

本模块保留已有的 `quickstart` 示例、Spring Boot 聊天接口和测试。工程结构及构建命令见 [项目说明](../README.md)。

在项目根目录执行 `mvn -pl base spring-boot:run` 可启动 HTTP 接口；基础示例仍可直接运行各自的 `main()`。

# 第一章

## 前言
### 第一站：看懂并运行最简单的聊天示例

先打开 [BasicChatExample.java (line 61)]()。

重点理解这几个配置：
```java
ReActAgent.builder()
.name("Assistant")
.sysPrompt("You are a helpful AI assistant.")
.model("dashscope:qwen-plus")
.toolkit(new Toolkit())
.build();
```
对应的含义是：


| 配置                        | 	你需要理解的内容               |
|---------------------------|-------------------|
| ReActAgent	                    | 负责组织模型推理和工具执行的 Agent    |
| sysPrompt                 | 	定义助手的角色、目标和行为要求 |
| model | 	指定实际调用的大模型   | 
| Toolkit | 	提供 Agent 可以调用的 Java 工具   | 
| UserMessage | 	表示用户输入   | 
| streamEvents | 	获取执行过程中的事件，示例用它显示增量文本   | 


运行本项目时，将根目录的 `pom.xml` 作为 Maven 项目导入 IDEA，再运行 `base` 模块下示例的 `main()`。
本地示例使用 `BaseExample.openAIChatModel()` 中配置的模型服务。官方源码位于项目根目录旁边的 `../agentscope-java`；如果运行其中默认使用通义千问的官方示例，需要设置 `DASHSCOPE_API_KEY`。

第一步的验收目标：能收到回复，能修改提示词改变助手的行为，能找到流式文本在哪里打印。

### 第二站：学习工具调用，这是理解 Agent 的关键

接着看 [ToolCallingExample.java (line 60)]()。

重点看三件事：

- 如何用 @Tool 和 @ToolParam 描述一个 Java 方法。
- 如何通过 toolkit.registerTool(...) 注册工具。
- 模型提出工具调用后，框架如何执行方法并把结果交回模型。
  可以把主要流程理解为：
  用户提问
  ↓
  模型根据输入决定是否调用工具
  ↓
  框架执行对应的 Java 方法
  ↓
  工具结果交回模型
  ↓
  模型继续处理，直到生成回答或触发停止条件
  建议你把示例里的工具换成 queryOrder(orderId)，先返回一份固定的订单数据，再提问：“查询订单 A001 的状态。”

验收目标：在工具方法里打断点，亲眼看到模型生成的参数进入 Java 方法。

### 第三站：理解多轮对话和状态保存

阅读 [StateExample.java (line 71)]()，重点认识：

- AgentState：保存对话上下文等运行状态。
- AgentStateStore：负责状态的存取。
- 会话身份：让后续请求找到对应的历史状态。
  练习：先告诉助手“我的订单号是 A001”，再问“帮我查询刚才的订单”；随后尝试重启程序、恢复同一会话。

### 有了运行体验，再读核心源码

推荐围绕一次工具调用追踪下面几个文件：
源码入口 带着什么问题看
[ReActAgent.java (line 3113)]    reasoning() 和 acting() 怎样形成循环？
[Toolkit.java]    工具怎样注册、查找和调用？
[Model.java]    框架如何抽象不同模型？
[HarnessAgent.java]    如何在基础 Agent 上组合工作区、记忆等工程能力？

ReActAgent 文件比较大，第一次只跟着断点看推理和工具执行的主线即可。

### 最后，在你的 Spring Boot 项目里完成一个小应用

建议逐步做到：
普通问答 → 查询订单工具 → 多轮对话 → HTTP 接口 → 流式输出
之后再根据实际需求学习 RAG、MCP、多 Agent、沙箱和分布式部署。

另外，截止2026年11月12日，README 的依赖示例使用 2.0.3。学习时要留意源码与依赖版本是否一致，尤其是网上教程中的 API。

## quickstart

### 引入依赖

```xml

<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-core</artifactId>
    <version>${agentscope.version}</version>
</dependency>
<dependency>
<groupId>io.agentscope</groupId>
<artifactId>agentscope-extensions-model-openai</artifactId>
<version>${agentscope.version}</version>
</dependency>
```

### BasicChatExample

需要先创建模型客户端，但不一定要注册成 Spring Bean。 Bean 是交给 Spring 容器创建和管理的对象；如果你独立运行 main()
，可以直接创建对象。(docs.spring.io)

对于你现在的 BasicChatExample，我建议先用普通 main()，理解这三个步骤：

① 创建模型客户端：配置如何连接内部千问

你引入的扩展提供了 [OpenAIChatModel (line 246)]()，可以这样配置：

```java
OpenAIChatModel model = OpenAIChatModel.builder()
        .baseUrl("http://内部服务地址:端口/v1")
        .modelName("服务端配置的模型名称")
        .apiKey(System.getenv("INTERNAL_LLM_API_KEY"))
        .stream(true)
        .build();
```

地址、模型名称、鉴权信息需要按你们内部服务填写。

② 创建 Agent：把模型客户端交给它

```java
ReActAgent agent = ReActAgent.builder()
        .name("assistant")
        .sysPrompt("你是一个中文助手，请简洁回答。")
        .model(model)
        .toolkit(new Toolkit())
        .build();
```

③ 发起一次对话

```java
Msg reply = agent.call(new UserMessage("你好，请介绍一下自己")).block();
System.out.println(reply.getTextContent());
```

### ToolCallingExample

这次的目标是：用户说“查询订单 A001”，模型提出工具调用，AgentScope 执行你的 Java 方法，再把查询结果交给模型生成回答。

在 quickstart 包下新建 ToolCallingExample.java，沿用你已经跑通的模型配置：

```java
public static class OrderTools {

    @Tool(name = "query_order", description = "根据订单号查询订单状态", readOnly = true)
    public String queryOrder(
            @ToolParam(name = "orderId", description = "订单号，例如 A001") String orderId) {
        System.out.println("[工具被调用] orderId=" + orderId);
        // 演示数据，后续可以替换成数据库或业务接口
        if ("A001".equals(orderId)) {
            return "订单 A001：已发货，商品为机械键盘。";
        }
        return "未查询到订单：" + orderId;
    }
}
```

```java
public static void main(String[] args) {

    Toolkit toolkit = new Toolkit();
    toolkit.registerTool(new OrderTools());

    OpenAIChatModel openAIChatModel = openAIChatModel();

    ReActAgent reActAgent = reActAgent(openAIChatModel, toolkit);

    Msg reply = reActAgent.call(
            new UserMessage("帮我查询订单 A001 的状态，发货了没有")
    ).block();

    System.out.println(reply.getTextContent());

}
```

这段代码重点理解三个地方。AgentScope 会根据注解生成工具描述和参数结构，提供给模型；`registerTool()` 负责把方法注册到工具容器。

| 代码                             | 	作用               |
|--------------------------------|-------------------|
| @Tool	                         | 告诉模型工具叫什么、能做什么    |
| @ToolParam                     | 	告诉模型参数叫什么、应该填写什么 |
| registerTool(new OrderTools()) | 	让框架能够找到并执行这个工具   | 

模型负责选择工具并生成参数，Java 方法由 AgentScope 执行。 你可以在 queryOrder() 的第一行打断点，观察 orderId 是否为 "A001"。

运行后做三次练习：

1. 查询 A001：观察是否进入工具方法，并返回已发货。
2. 查询 A002：观察是否根据工具结果回答“未查询到”。
3. 输入“你好”：观察是否直接回答，无需查询订单。

### 多轮对话与会话隔离

下一步做第三个案例：多轮对话与会话隔离。

把前面的工具调用接起来，完成这段对话：

```text
第一轮：我的订单号是 A001。
第二轮：帮我查询刚才那个订单。
```

第二轮需要从会话历史中找到订单号，再调用工具。AgentScope 用 AgentState 保存历史，通过 RuntimeContext 中的用户和会话身份定位对应状态。

```java
public static void main(String[] args) {
    OpenAIChatModel openAIChatModel = openAIChatModel();

    Toolkit toolkit = toolkit();

    ReActAgent reActAgent = reActAgent(openAIChatModel, toolkit, null);

    RuntimeContext sessionA = RuntimeContext.builder()
            .userId("alice")
            .sessionId("session-a")
            .build();

    chat(reActAgent, sessionA, "我的订单号是 A001");
    chat(reActAgent, sessionA, "我的订单号是 A002");
    chat(reActAgent, sessionA, "帮我查询刚才那个订单的状态。");
    chat(reActAgent, sessionA, "已发货的订单号是多少。");


    RuntimeContext sessionB = RuntimeContext.builder()
            .userId("alice")
            .sessionId("session-b")
            .build();

    chat(reActAgent, sessionB, "我的订单号是什么？");
}
```

这次重点理解三个对象：

| 对象              | 	职责                             |
|-----------------|---------------------------------|
| RuntimeContext  | 	携带本次请求的 userId、`sessionId` 等信息 |
| AgentState      | 	保存该会话的历史、工具调用等状态               |
| AgentStateStore | 	保存和加载状态；本例使用内存实现               |

运行后观察：

1. session-a 的第二轮能否提取出 A001，进入 queryOrder()。
2. session-b 是否要求你提供订单号。
3. 回到 session-a 后，是否仍然记得 A001。

### 持久化

将会话保存到文件，重启程序后恢复对话。

这次使用 JsonFileAgentStateStore 保存状态；后续调用会根据用户和会话身份自动加载已保存的历史。

```java
public static void main(String[] args) {
    AgentStateStore agentStateStore = agentFileStateStore();


    OpenAIChatModel openAIChatModel = openAIChatModel();

    Toolkit toolkit = toolkit();

    ReActAgent reActAgent = reActAgent(openAIChatModel, toolkit, agentStateStore);

    RuntimeContext sessionA = RuntimeContext.builder()
            .userId("alice")
            .sessionId("session-a")
            .build();

    chat(reActAgent, sessionA, "我的订单号是 A001");
    chat(reActAgent, sessionA, "我的订单号是 A002");
    chat(reActAgent, sessionA, "帮我查询刚才那个订单的状态。");


    RuntimeContext sessionB = RuntimeContext.builder()
            .userId("alice")
            .sessionId("session-b")
            .build();

    chat(reActAgent, sessionB, "我的订单号是什么？");
}
```

分两次运行来验证，第二次直接提问：

| 操作     | 	输入               | 	观察目标            |
|--------|-------------------|------------------|
| 第一次运行  | 	我的订单号是 A001，请记住。 | 	收到回复后，输入 exit   |
| 再次启动程序 | 	我的订单号是什么？        | 	能从保存的历史中找到 A001 |
| 继续提问   | 	帮我查询这个订单的状态      | 	从历史提取订单号，再调用工具  |

关键配置只有这一处：
new JsonFileAgentStateStore(stateDirectory)
ReActAgent 负责调用前加载状态、调用结束后保存状态，你无需手动序列化聊天记录。恢复时应使用同一个存储目录、`userId` 和
sessionId。

还可以把 sessionId 改成 "another-session" 再运行，观察它是否要求重新提供订单号。

### Stream 流式输出

之前使用：

```java
Msg reply = agent.call(...).block();
```
这次改成：

```java
agent.streamEvents(...)
        .doOnNext(event -> {
            // 每收到一个事件，就处理一次
        })
        .blockLast();
```

`call()` 返回最终消息；`streamEvents()` 返回执行过程中的事件流。`blockLast()` 会启动订阅并等待流结束，期间 doOnNext() 持续处理事件

```java
public class StreamingChatExample extends BaseExample {
    
    public static void main(String[] args) {
        RuntimeContext context = RuntimeContext.builder()
                .userId("alice")
                .sessionId("streaming-demo")
                .build();
        try (ReActAgent agent = reActAgent(openAIChatModel(), toolkit(), agentStateStore())) {
            agent.streamEvents("帮我查询订单 A001 的状态。", context)
                    .doOnNext(StreamingChatExample::handleEvent)
                    .blockLast();
        }
    }
    
    private static void handleEvent(AgentEvent event) {
        if (event instanceof AgentStartEvent) {
            System.out.println("\t[Agent 开始]");
        } else if (event instanceof ModelCallStartEvent) {
            System.out.println("\t[模型调用开始]");
        } else if (event instanceof TextBlockDeltaEvent text) {
            // 不换行，将收到的文本片段连续显示
            System.out.print(text.getDelta());
            System.out.flush();
        } else if (event instanceof ToolCallStartEvent tool) {
            System.out.println("\t[模型请求工具] " + tool.getToolCallName());
        } else if (event instanceof ToolResultEndEvent result) {
            System.out.printf("\t[工具结果] %s，状态：%s%n", result.getToolCallName(), result.getState());
        } else if (event instanceof AgentEndEvent) {
            System.out.println("\t[Agent 结束]");
        }
    }
}
```

这里处理了三类信息：Agent 生命周期、模型文本增量，以及工具调用和结果。事件还提供调用 ID 等字段，方便关联一次工具请求及其结果。


运行后观察调用顺序

如果模型选择调用订单工具，输出可能类似:

```text
[Agent 开始]
[模型调用开始]

[模型请求工具] query_order
[工具被调用] orderId=A001
[工具结果] query_order，状态：SUCCESS

[模型调用开始]
订单 A001 已发货，商品为机械键盘。
[Agent 结束]
```

注意观察两次 `[模型调用开始]`：模型先提出工具调用，收到工具结果后，再生成回答。一次 Agent 调用可以包含多次模型推理和工具执行。

这里也修正一下前面的表述：ToolCallEndEvent 表示工具调用参数接收完成；工具最终结果就绪要看 ToolResultEndEvent。 本例打印后者的状态。

做两个小实验 

* 将问题改成“用 200 字介绍一下机械键盘”，观察文本是否分片显示。每个片段可能包含多个字符。
* 在 handleEvent() 第一行临时加入下面这句，查看完整事件顺序：
```java
System.out.print("\n[事件类型] " + event.getType());
```

### 结构化输出

这一课的目标是：让 Agent 把一句自然语言提取成 Java 对象，方便业务代码直接使用。 AgentScope 支持在调用时指定输出类型，再从回复中取出对应对象。

例如输入：
```text
张三购买了 2 个机械键盘，订单号为 A001。
```
我们希望得到
```java
orderInfo.orderId     // "A001"
orderInfo.productName // "机械键盘"
orderInfo.quantity    // 2
orderInfo.buyer       // "张三"
```

```java
public class StructuredOutputExample extends BaseExample {

    public static void main(String[] args) {
        RuntimeContext context = RuntimeContext.builder()
                .userId("alice")
                .sessionId("structured-output-demo")
                .build();

        String input = """
                请从下面的文本中提取订单信息：
                
                张三 购买了 2 个机械键盘，订单号为 A001。
                
                字段含义：
                orderId：订单号
                productName：商品名称
                quantity：购买数量
                buyer：购买人
                
                只提取文本明确提供的信息。
                """;

        try (ReActAgent agent = reActAgent(openAIChatModel(), null, agentStateStore())) {

            // 指定本次回复的结构
            Msg reply = agent.call(input, OrderInfo.class, context).block();

            if (reply == null || !reply.hasStructuredData()) {
                throw new IllegalStateException("本次回复未包含结构化数据");
            }

            // 将结构化结果转换为 Java 对象
            OrderInfo orderInfo = reply.getStructuredData(OrderInfo.class);
            System.out.println("订单号：" + orderInfo.orderId);
            System.out.println("商品：" + orderInfo.productName);
            System.out.println("数量：" + orderInfo.quantity);
            System.out.println("购买人：" + orderInfo.buyer);
        }
    }
}
```
重点理解两处代码。

第一处，指定输出结构：
```java
Msg reply = agent.call(input, OrderInfo.class, context).block();
```
传入的 OrderInfo.class 会用于生成 JSON Schema，也就是向模型描述需要哪些字段、字段分别是什么类型。
第二处，取出对象：
```java
OrderInfo orderInfo = reply.getStructuredData(OrderInfo.class);
```

getStructuredData() 从消息的结构化数据中转换出指定的 Java 对象。后续可以直接访问 orderInfo.quantity 等字段。

这次第二个参数传 null，你的 [BaseExample.java]() 会创建空的 Toolkit。案例中的订单信息来自输入文本。


### Middleware

你可以把它理解成：在 Agent 执行的不同阶段，加入一段统一处理逻辑。例如记录日志、统计耗时、调整提示词。

先做一个小案例：记录每次模型调用的耗时。

```java
public class MiddlewareExample extends BaseExample {

    public static void main(String[] args) {
        RuntimeContext context = RuntimeContext.builder()
                .userId("alice")
                .sessionId("middleware-demo")
                .build();

        try (ReActAgent agent = reActAgent(openAIChatModel(), toolkit(), agentStateStore(), modelTimingMiddleware())) {
            chat(agent, context, "帮我查询订单 A001 的状态。");
        }
    }


    public static MiddlewareBase modelTimingMiddleware() {
        return new ModelTimingMiddleware();
    }

    public static class ModelTimingMiddleware implements MiddlewareBase {

        @Override
        public Flux<AgentEvent> onModelCall(
                Agent agent,
                RuntimeContext context,
                ModelCallInput input,
                Function<ModelCallInput, Flux<AgentEvent>> next) {
            return Flux.defer(() -> {
                long start = System.nanoTime();

                System.out.printf("[模型调用开始] agent=%s，session=%s%n", agent.getName(), context.getSessionId());

                return next.apply(input)
                        .doOnError(error -> System.err.println("[模型调用异常] " + error.getMessage()))
                        .doFinally(signal -> {
                            long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
                            System.out.printf("[模型调用结束] 状态=%s，耗时=%d ms%n", signal, elapsedMillis);
                        });
            });
        }
    }
}
```
这次直接用 Builder 创建 Agent，是为了增加这行中间件注册；模型、工具和 chat()

```java
.middleware(new ModelTimingMiddleware())
```
重点理解中间件里的三处代码。

1. Flux.defer(...)：等这条流被订阅、准备执行时，再记录开始时间。这样每次执行都有自己的计时变量。
2. next.apply(input)：把输入交给下一个中间件，最终继续执行模型调用。它相当于“继续往下走”。这个委托方式也是 [MiddlewareBase.java] 默认实现的行为。
3. doFinally(...)：流完成、报错或被取消后，记录结束状态和耗时。这里统计的是整次模型调用到流结束的时间。

为什么查询订单可能出现两组计时日志？

通常执行过程是：

```text
第一次模型调用：生成 query_order 工具请求
执行查询订单工具
第二次模型调用：根据工具结果生成回答
```

因此，`onModelCall()` 记录的是每次模型调用。如果想统计用户这个问题从开始到结束的总耗时，可以使用 onAgent()。各入口的范围如下

| 入口 | 处理范围 |
|---|---|
| `onAgent()` | 整次 Agent 执行 |
| `onReasoning()` | 推理阶段 |
| `onModelCall()` | 模型 API 调用 |
| `onActing()` | 工具执行阶段 |
| `onSystemPrompt()` | 系统提示词转换 |


### springboot

目标是把之前的控制台程序变成：

```text
HTTP 请求 → Controller → Agent → 持续返回文本片段
```
第一步：把模型和会话存储交给 Spring 管理。

```java
@Configuration
public class AgentConfig {

    @Bean
    public OpenAIChatModel chatModel() {
        return BaseExample.openAIChatModel();
    }

    @Bean
    public AgentStateStore chatStateStore() {
        return BaseExample.agentStateStore();
    }

    @Bean
    public Toolkit toolkit() {
        return BaseExample.toolkit();
    }

    @Bean(destroyMethod = "close")
    public ReActAgent reActAgent(OpenAIChatModel chatModel,
                                 AgentStateStore chatStateStore,
                                 Toolkit toolkit) {
        return BaseExample.reActAgent(chatModel, toolkit, chatStateStore, new MiddlewareExample.ModelTimingMiddleware());
    }
}
```

第二步：提供 SSE 接口。

```java
@RestController
@RequiredArgsConstructor
public class ChatController {

    private final ReActAgent reActAgent;

    @GetMapping(path = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> stream(
            @RequestParam("message") String message,
            @RequestParam(name = "sessionId", defaultValue = "web-demo") String sessionId) {

        RuntimeContext context = RuntimeContext.builder()
                .userId("alice")
                .sessionId(sessionId)
                .build();

        // 每次请求只创建执行流，共享 Agent 由 Spring 在容器关闭时释放。
        return reActAgent.streamEvents(message, context)
                .subscribeOn(Schedulers.boundedElastic())
                .ofType(TextBlockDeltaEvent.class)
                .map(TextBlockDeltaEvent::getDelta);
    }

}
```

重点理解这三个地方。
1. 声明接口输出 SSE：Spring 会把返回的文本片段编码成 SSE，并逐段发送给客户端。
```java
produces = MediaType.TEXT_EVENT_STREAM_VALUE
```

2. 从 Agent 事件中提取文本： 接口只输出文本增量。工具调用和计时日志仍然可以在服务端控制台观察。
```java
.ofType(TextBlockDeltaEvent.class)
.map(TextBlockDeltaEvent::getDelta)
```

3. 共享 Agent 的生命周期由 Spring 管理。每次请求创建自己的 RuntimeContext 和执行流，Controller 返回流后，Spring 负责订阅并发送 SSE 响应。流完成、报错或取消时结束本次订阅，Agent 继续供后续请求使用。容器关闭时，通过 Bean 的销毁方法释放 Agent：
```java
@Bean(destroyMethod = "close")
```

如果需要记录本次流的结束状态，可以使用 `doFinally`，在其中记录日志或清理请求级资源。


| 对象 | 生命周期 |
|---|---|
| 模型客户端 | Spring 容器中共享 |
| 会话存储 | Spring 容器中共享 |
| Agent | Spring 容器中共享，容器关闭时调用 close |


## 后续学习

完成后，再按业务方向选
* RAG：给助手提供退换货政策、产品手册等知识，练习检索后回答。
* MCP：接入外部工具服务，练习复用已有能力。
* Harness 与多 Agent：学习工作区、长期记忆和子 Agent 等工程能力。

