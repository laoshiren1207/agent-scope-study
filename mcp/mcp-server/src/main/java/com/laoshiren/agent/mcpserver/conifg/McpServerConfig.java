package com.laoshiren.agent.mcpserver.conifg;

import com.laoshiren.agent.mcpserver.service.OrderService;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;

@Configuration
public class McpServerConfig {


    @Bean
    public McpSchema.Tool queryOrderTool() {
        String inputSchema = """
                {
                  "type": "object",
                  "properties": {
                    "orderId": {
                      "type": "string",
                      "description": "订单号，例如 A001"
                    }
                  },
                  "required": ["orderId"],
                  "additionalProperties": false
                }
                """;

        return McpSchema.Tool.builder()
                .name("query_order")
                .description("根据订单号查询订单状态和商品信息")
                .inputSchema(McpJsonMapper.getDefault(), inputSchema)
                .annotations(new McpSchema.ToolAnnotations(
                        "订单查询",
                        true,   // readOnlyHint：只查询，不修改数据
                        null,
                        null,
                        null,
                        null
                ))
                .build();
    }


    @Bean
    public McpServerFeatures.SyncToolSpecification queryOrderSpecification(McpSchema.Tool queryOrderTool, OrderService orderService) {

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(queryOrderTool)
                .callHandler((exchange, request) -> {
                    // 1. 取出客户端传入的订单号
                    Object rawOrderId = request.arguments() == null ? null : request.arguments().get("orderId");

                    // 2. 检查订单号
                    if (!(rawOrderId instanceof String orderId) || orderId.isBlank()) {
                        return McpSchema.CallToolResult.builder()
                                .addTextContent("orderId 必须是非空字符串")
                                .isError(true)
                                .build();
                    }

                    // 3. 调用你已经写好的业务方法
                    String result = orderService.queryOrderById(orderId);

                    // 4. 将业务结果包装成 MCP 工具结果
                    return McpSchema.CallToolResult.builder()
                            .addTextContent(result)
                            .isError(false)
                            .build();
                })
                .build();
    }

    @Bean
    public HttpServletStreamableServerTransportProvider mcpTransportProvider() {
        return HttpServletStreamableServerTransportProvider.builder()
                .jsonMapper(McpJsonMapper.getDefault())
                .mcpEndpoint("/mcp")
                .build();
    }

    @Bean(destroyMethod = "close")
    public McpSyncServer mcpServer(
            HttpServletStreamableServerTransportProvider mcpTransportProvider,
            McpServerFeatures.SyncToolSpecification queryOrderSpecification) {

        return McpServer.sync(mcpTransportProvider)
                .serverInfo("order-mcp-server", "1.0.0")
                .capabilities(McpSchema.ServerCapabilities.builder()
                        .tools(false)
                        .build())
                .tools(queryOrderSpecification)
                .build();
    }

    @Bean
    @DependsOn("mcpServer")
    public ServletRegistrationBean<HttpServletStreamableServerTransportProvider> mcpServletRegistration(
            HttpServletStreamableServerTransportProvider mcpTransportProvider) {

        ServletRegistrationBean<HttpServletStreamableServerTransportProvider> registration
                = new ServletRegistrationBean<>(mcpTransportProvider, "/mcp");

        registration.setName("mcpServlet");
        registration.setAsyncSupported(true);
        registration.setLoadOnStartup(1);

        return registration;
    }

}
