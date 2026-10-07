package jissuo.chat.message.infra.jdbc;

import jissuo.chat.common.ChatProperties;
import jissuo.chat.message.domain.MessageRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

@Configuration(proxyBeanMethods = false)
public class MessageRepositoryConfig {

    @Bean
    @ConditionalOnProperty(prefix = "chat", name = "repository", havingValue = "jdbc", matchIfMissing = true)
    MessageRepository messageRepository(JdbcClient jdbc, ChatProperties properties) {
        String table = properties.messageSchema() == ChatProperties.MessageSchema.A ? "messages" : "messages_b";
        JoinBoundaryMode mode = properties.joinBoundary() == ChatProperties.JoinBoundary.ID
                ? JoinBoundaryMode.ID : JoinBoundaryMode.TIME;
        return new JdbcMessageRepository(jdbc, table, mode);
    }
}
