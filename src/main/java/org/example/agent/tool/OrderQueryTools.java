package org.example.agent.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 订单查询工具
 * 用于查询订单基本信息和物流信息
 */
@Component
@Slf4j
public class OrderQueryTools {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final Random random = new Random();

    /**
     * 查询订单基本信息
     * @param orderId 订单号
     * @return 订单信息
     */
    @Tool(description = "Query order basic information. This tool retrieves order details including order ID, status, amount, items, and creation time. Use this tool when user asks about their order status, order details, or order amount.")
    public String queryOrderInfo(@ToolParam(description = "The order ID to query") String orderId) {
        log.info("查询订单基本信息 - orderIdLength={}", orderId == null ? 0 : orderId.length());
        
        // 模拟订单数据
        OrderInfo orderInfo = new OrderInfo();
        orderInfo.setOrderId(orderId);
        orderInfo.setStatus(getRandomStatus());
        orderInfo.setAmount(String.format("%.2f", 100 + random.nextDouble() * 900));
        orderInfo.setItems(getRandomItems());
        orderInfo.setCreateTime(LocalDateTime.now().minusDays(random.nextInt(7)).format(FORMATTER));
        orderInfo.setPaymentTime(LocalDateTime.now().minusDays(random.nextInt(3)).format(FORMATTER));
        
        return orderInfo.toJson();
    }

    /**
     * 查询订单物流信息
     * @param orderId 订单号
     * @return 物流信息
     */
    @Tool(description = "Query order tracking information. This tool retrieves shipping details including tracking number, current status, carrier, and delivery timeline. Use this tool when user asks about order shipping status, delivery tracking, or logistics information.")
    public String queryOrderTracking(@ToolParam(description = "The order ID to track") String orderId) {
        log.info("查询订单物流信息 - orderIdLength={}", orderId == null ? 0 : orderId.length());
        
        // 模拟物流数据
        TrackingInfo trackingInfo = new TrackingInfo();
        trackingInfo.setOrderId(orderId);
        trackingInfo.setTrackingNumber("SF" + System.currentTimeMillis());
        trackingInfo.setCarrier("顺丰速运");
        trackingInfo.setStatus(getRandomTrackingStatus());
        trackingInfo.setEstimatedDelivery(LocalDateTime.now().plusDays(random.nextInt(3)).format(FORMATTER));
        trackingInfo.setTimeline(getRandomTimeline());
        
        return trackingInfo.toJson();
    }

    private String getRandomStatus() {
        String[] statuses = {"已支付", "待发货", "已发货", "运输中", "派送中", "已签收"};
        return statuses[random.nextInt(statuses.length)];
    }

    private String getRandomTrackingStatus() {
        String[] statuses = {"已揽收", "运输中", "到达中转站", "派送中", "已签收"};
        return statuses[random.nextInt(statuses.length)];
    }

    private List<OrderItem> getRandomItems() {
        List<OrderItem> items = new ArrayList<>();
        int count = 1 + random.nextInt(3);
        String[] productNames = {"智能音箱", "无线耳机", "平板电脑", "蓝牙耳机", "智能手表"};
        
        for (int i = 0; i < count; i++) {
            OrderItem item = new OrderItem();
            item.setName(productNames[random.nextInt(productNames.length)]);
            item.setQuantity(String.valueOf(1 + random.nextInt(2)));
            item.setPrice(String.format("%.2f", 50 + random.nextDouble() * 500));
            items.add(item);
        }
        return items;
    }

    private List<TrackingEvent> getRandomTimeline() {
        List<TrackingEvent> timeline = new ArrayList<>();
        int count = 3 + random.nextInt(4);
        
        LocalDateTime now = LocalDateTime.now();
        for (int i = count - 1; i >= 0; i--) {
            TrackingEvent event = new TrackingEvent();
            event.setTime(now.minusHours(i * 4 + random.nextInt(6)).format(FORMATTER));
            event.setLocation(getRandomLocation());
            event.setStatus(getRandomEventStatus());
            timeline.add(event);
        }
        return timeline;
    }

    private String getRandomLocation() {
        String[] locations = {"北京市朝阳区", "上海市浦东新区", "广州市天河区", "深圳市南山区", "杭州市西湖区", "成都市锦江区"};
        return locations[random.nextInt(locations.length)];
    }

    private String getRandomEventStatus() {
        String[] statuses = {
            "快件已揽收",
            "快件已发出",
            "到达中转中心",
            "正在派送",
            "快件已签收",
            "等待派送"
        };
        return statuses[random.nextInt(statuses.length)];
    }

    @Data
    public static class OrderInfo {
        private String orderId;
        private String status;
        private String amount;
        private List<OrderItem> items;
        private String createTime;
        private String paymentTime;

        public String toJson() {
            StringBuilder sb = new StringBuilder();
            sb.append("{");
            sb.append("\"orderId\":\"").append(orderId).append("\",");
            sb.append("\"status\":\"").append(status).append("\",");
            sb.append("\"amount\":\"").append(amount).append("\",");
            sb.append("\"createTime\":\"").append(createTime).append("\",");
            sb.append("\"paymentTime\":\"").append(paymentTime).append("\",");
            sb.append("\"items\":[");
            for (int i = 0; i < items.size(); i++) {
                OrderItem item = items.get(i);
                sb.append("{\"name\":\"").append(item.getName()).append("\",");
                sb.append("\"quantity\":\"").append(item.getQuantity()).append("\",");
                sb.append("\"price\":\"").append(item.getPrice()).append("\"}");
                if (i < items.size() - 1) sb.append(",");
            }
            sb.append("]");
            sb.append("}");
            return sb.toString();
        }
    }

    @Data
    public static class OrderItem {
        private String name;
        private String quantity;
        private String price;
    }

    @Data
    public static class TrackingInfo {
        private String orderId;
        private String trackingNumber;
        private String carrier;
        private String status;
        private String estimatedDelivery;
        private List<TrackingEvent> timeline;

        public String toJson() {
            StringBuilder sb = new StringBuilder();
            sb.append("{");
            sb.append("\"orderId\":\"").append(orderId).append("\",");
            sb.append("\"trackingNumber\":\"").append(trackingNumber).append("\",");
            sb.append("\"carrier\":\"").append(carrier).append("\",");
            sb.append("\"status\":\"").append(status).append("\",");
            sb.append("\"estimatedDelivery\":\"").append(estimatedDelivery).append("\",");
            sb.append("\"timeline\":[");
            for (int i = 0; i < timeline.size(); i++) {
                TrackingEvent event = timeline.get(i);
                sb.append("{\"time\":\"").append(event.getTime()).append("\",");
                sb.append("\"location\":\"").append(event.getLocation()).append("\",");
                sb.append("\"status\":\"").append(event.getStatus()).append("\"}");
                if (i < timeline.size() - 1) sb.append(",");
            }
            sb.append("]");
            sb.append("}");
            return sb.toString();
        }
    }

    @Data
    public static class TrackingEvent {
        private String time;
        private String location;
        private String status;
    }
}
