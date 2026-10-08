package jissuo.chat.message.api.ws;

import jissuo.chat.auth.QueryUserIdHandshakeInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration(proxyBeanMethods = false)
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final ChatWebSocketHandler handler;
    private final QueryUserIdHandshakeInterceptor authentication;

    public WebSocketConfig(ChatWebSocketHandler handler, QueryUserIdHandshakeInterceptor authentication) {
        this.handler = handler;
        this.authentication = authentication;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // 계획 7 세부 1, ADR-028: 허용 origin을 지정하지 않아 Spring 기본(같은 origin만)을 쓴다
        registry.addHandler(handler, "/ws").addInterceptors(authentication);
    }
}
