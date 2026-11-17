package com.laoshiren.agent.mcpserver.service.impl;

import com.laoshiren.agent.mcpserver.service.OrderService;
import org.springframework.stereotype.Service;

@Service
public class OrderServiceImpl implements OrderService {

    @Override
    public String queryOrderById(String orderId) {
        return switch (orderId) {
            case "A001" -> "订单 " + orderId + "：已发货，商品为机械键盘。";
            case "A002" -> "订单 " + orderId + "：待发货，商品为无线鼠标。";
            default -> "未查询到订单：" + orderId;
        };
    }
}
