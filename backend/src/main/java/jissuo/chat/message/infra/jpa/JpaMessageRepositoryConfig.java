package jissuo.chat.message.infra.jpa;

import jissuo.chat.common.ChatProperties;
import jissuo.chat.message.domain.MessageRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class JpaMessageRepositoryConfig {

    @Bean
    @ConditionalOnProperty(prefix = "chat", name = "repository", havingValue = "jpa")
    MessageRepository messageRepository(SpringDataMessageRepository messages, ChatProperties properties) {
        if (properties.messageSchema() != ChatProperties.MessageSchema.A) {
            throw new IllegalStateException("JPA 구현은 메시지 스키마 A만 지원한다");
        }
        return new JpaMessageRepository(messages, properties.joinBoundary() == ChatProperties.JoinBoundary.TIME);
    }
}
