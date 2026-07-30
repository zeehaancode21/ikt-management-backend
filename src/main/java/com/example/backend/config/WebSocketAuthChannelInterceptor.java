package com.example.backend.config;

import com.example.backend.security.JwtUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

import java.security.Principal;
import java.util.List;

@Component
public class WebSocketAuthChannelInterceptor implements ChannelInterceptor {

    @Autowired
    private JwtUtil jwtUtil;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor =
                MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

        if (accessor != null && StompCommand.CONNECT.equals(accessor.getCommand())) {
            List<String> authHeaders = accessor.getNativeHeader("Authorization");
            String authHeader = (authHeaders != null && !authHeaders.isEmpty()) ? authHeaders.get(0) : null;

            // Previously: a missing/malformed Authorization header just skipped
            // setting a principal and let the CONNECT through anyway. Combined
            // with /ws/**, /topic/**, /app/** being permitAll() in SecurityConfig,
            // that meant STOMP connections needed no valid auth at all. Now we
            // require a valid Bearer token on every CONNECT.
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                throw new IllegalArgumentException("Missing WebSocket auth token");
            }

            String token = authHeader.substring(7);
            try {
                if (!jwtUtil.isTokenValid(token)) {
                    throw new IllegalArgumentException("Invalid WebSocket token");
                }
                String username = jwtUtil.extractUsername(token);
                Principal principal = () -> username;
                accessor.setUser(principal);
            } catch (IllegalArgumentException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalArgumentException("Invalid WebSocket token");
            }
        }
        return message;
    }
}