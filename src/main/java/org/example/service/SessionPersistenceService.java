package org.example.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.example.entity.ChatMessage;
import org.example.entity.ChatSession;
import org.example.mapper.ChatMessageMapper;
import org.example.mapper.ChatSessionMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.*;

@Service
public class SessionPersistenceService {
    
    @Autowired
    private ChatSessionMapper sessionMapper;
    
    @Autowired
    private ChatMessageMapper messageMapper;

    @Autowired
    private RetryExecutor retryExecutor;

    @Autowired
    private TransactionTemplate transactionTemplate;
    
    private static final int MAX_HISTORY_PAIRS = 6;
    
    public ChatSession getOrCreateSession(Long userId, String sessionId) {
        LambdaQueryWrapper<ChatSession> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ChatSession::getUserId, userId)
                .eq(ChatSession::getSessionId, sessionId);
        ChatSession session = sessionMapper.selectOne(wrapper);
        
        if (session == null) {
            session = new ChatSession();
            session.setUserId(userId);
            session.setSessionId(sessionId);
            session.setCreatedAt(LocalDateTime.now());
            session.setUpdatedAt(LocalDateTime.now());
            sessionMapper.insert(session);
        }
        
        return session;
    }

    public ChatSession findSession(Long userId, String sessionId) {
        LambdaQueryWrapper<ChatSession> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ChatSession::getUserId, userId)
                .eq(ChatSession::getSessionId, sessionId);
        return sessionMapper.selectOne(wrapper);
    }
    
    public boolean saveMessagePair(Long userId, String sessionId, String requestId,
                                   String userQuestion, String aiAnswer) {
        return retryExecutor.execute("mysql-save-message-pair", () -> Boolean.TRUE.equals(
                transactionTemplate.execute(status -> saveMessagePairOnce(
                        userId, sessionId, requestId, userQuestion, aiAnswer))));
    }

    private boolean saveMessagePairOnce(Long userId, String sessionId, String requestId,
                                        String userQuestion, String aiAnswer) {
        if (messageMapper.countByRequestId(userId, sessionId, requestId) > 0) {
            return false;
        }
        long messageCount = messageMapper.countBySessionId(userId, sessionId);
        
        ChatMessage userMessage = new ChatMessage();
        userMessage.setUserId(userId);
        userMessage.setSessionId(sessionId);
        userMessage.setRequestId(requestId);
        userMessage.setRole("user");
        userMessage.setContent(userQuestion);
        userMessage.setMessageOrder((int) messageCount);
        userMessage.setCreatedAt(LocalDateTime.now());
        int userResult = messageMapper.insert(userMessage);
        System.out.println("[DEBUG] user message insert result: " + userResult);
        
        ChatMessage assistantMessage = new ChatMessage();
        assistantMessage.setUserId(userId);
        assistantMessage.setSessionId(sessionId);
        assistantMessage.setRequestId(requestId);
        assistantMessage.setRole("assistant");
        assistantMessage.setContent(aiAnswer);
        assistantMessage.setMessageOrder((int) messageCount + 1);
        assistantMessage.setCreatedAt(LocalDateTime.now());
        int assistantResult = messageMapper.insert(assistantMessage);
        System.out.println("[DEBUG] assistant message insert result: " + assistantResult);
        
        LambdaQueryWrapper<ChatSession> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ChatSession::getUserId, userId)
                .eq(ChatSession::getSessionId, sessionId);
        ChatSession session = sessionMapper.selectOne(wrapper);
        if (session != null) {
            session.setUpdatedAt(LocalDateTime.now());
            sessionMapper.updateById(session);
        }
        return true;
    }

    public String findSavedAnswer(Long userId, String sessionId, String requestId) {
        return messageMapper.findAssistantAnswer(userId, sessionId, requestId);
    }
    
    public List<Map<String, String>> getHistoryMessages(Long userId, String sessionId) {
        List<ChatMessage> latestMessages = messageMapper.selectLatestMessages(userId, sessionId, MAX_HISTORY_PAIRS * 2);
        
        Collections.reverse(latestMessages);
        
        List<Map<String, String>> history = new ArrayList<>();
        for (ChatMessage message : latestMessages) {
            Map<String, String> msgMap = new HashMap<>();
            msgMap.put("role", message.getRole());
            msgMap.put("content", message.getContent());
            history.add(msgMap);
        }
        
        return history;
    }

    @Transactional
    public void clearSession(Long userId, String sessionId) {
        messageMapper.deleteByUserIdAndSessionId(userId, sessionId);
        LambdaQueryWrapper<ChatSession> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ChatSession::getUserId, userId)
                .eq(ChatSession::getSessionId, sessionId);
        sessionMapper.delete(wrapper);
    }
}
