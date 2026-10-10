package org.example.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.example.entity.ChatMessage;

import java.util.List;

public interface ChatMessageMapper extends BaseMapper<ChatMessage> {
    
    @Select("SELECT * FROM chat_message WHERE user_id = #{userId} AND session_id = #{sessionId} ORDER BY message_order DESC LIMIT #{limit}")
    List<ChatMessage> selectLatestMessages(@Param("userId") Long userId, @Param("sessionId") String sessionId, @Param("limit") int limit);
    
    @Select("SELECT COUNT(*) FROM chat_message WHERE user_id = #{userId} AND session_id = #{sessionId}")
    long countBySessionId(@Param("userId") Long userId, @Param("sessionId") String sessionId);

    @Select("SELECT content FROM chat_message WHERE user_id = #{userId} AND session_id = #{sessionId} " +
            "AND request_id = #{requestId} AND role = 'assistant' LIMIT 1")
    String findAssistantAnswer(@Param("userId") Long userId,
                               @Param("sessionId") String sessionId,
                               @Param("requestId") String requestId);

    @Select("SELECT COUNT(*) FROM chat_message WHERE user_id = #{userId} AND session_id = #{sessionId} " +
            "AND request_id = #{requestId}")
    long countByRequestId(@Param("userId") Long userId,
                          @Param("sessionId") String sessionId,
                          @Param("requestId") String requestId);

    @org.apache.ibatis.annotations.Delete("DELETE FROM chat_message WHERE user_id = #{userId} AND session_id = #{sessionId}")
    int deleteByUserIdAndSessionId(@Param("userId") Long userId, @Param("sessionId") String sessionId);
}
