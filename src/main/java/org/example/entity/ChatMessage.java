package org.example.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("chat_message")
public class ChatMessage {
    
    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("user_id")
    private Long userId;
    
    @TableField("session_id")
    private String sessionId;

    @TableField("request_id")
    private String requestId;
    
    @TableField("role")
    private String role;
    
    @TableField("content")
    private String content;
    
    @TableField("message_order")
    private Integer messageOrder;
    
    @TableField("created_at")
    private LocalDateTime createdAt;
}
