package com.laoshiren.agent.mcp;

import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

@Slf4j
public class McpClient {

    public static void main(String[] args) {
        McpClientWrapper client = McpClientBuilder.create("order-client")
                .streamableHttpTransport("http://127.0.0.1:9080/mcp")
                .buildSync();

        try (client) {
            // 1. 初始化连接
            client.initialize().block();
            // 2. 获取服务端提供的工具
            List<McpSchema.Tool> tools = client.listTools().block();


            for (McpSchema.Tool tool : tools) {
                System.out.println("工具：" + tool.name());
                System.out.println("说明：" + tool.description());
            }


            // 3. 调用订单查询工具

            Map<String, Object> mcpRequest = Map.of("orderId", "A001");

            McpSchema.CallToolResult result = client
                    .callTool("query_order", mcpRequest)
                    .block();

            log.info("isError = {}" ,result.isError());
            log.info("response: {}" , result);
        }

    }

}
