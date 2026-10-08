package jissuo.chat.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class ChatPropertiesTest {

    final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Config.class);

    @Test
    void 값이_없으면_jdbc_A_id가_기본값이다() {
        runner.run(context -> {
            var properties = context.getBean(ChatProperties.class);
            assertThat(properties.repository()).isEqualTo(ChatProperties.Repository.JDBC);
            assertThat(properties.messageSchema()).isEqualTo(ChatProperties.MessageSchema.A);
            assertThat(properties.joinBoundary()).isEqualTo(ChatProperties.JoinBoundary.ID);
        });
    }

    @Test
    void 설정한_값으로_바뀐다() {
        runner.withPropertyValues("chat.message-schema=B", "chat.join-boundary=time")
                .run(context -> {
                    var properties = context.getBean(ChatProperties.class);
                    assertThat(properties.messageSchema()).isEqualTo(ChatProperties.MessageSchema.B);
                    assertThat(properties.joinBoundary()).isEqualTo(ChatProperties.JoinBoundary.TIME);
                });
    }

    @Test
    void jpa를_고를_수_있다() {
        runner.withPropertyValues("chat.repository=jpa")
                .run(context -> assertThat(context.getBean(ChatProperties.class).repository())
                        .isEqualTo(ChatProperties.Repository.JPA));
    }

    @ParameterizedTest
    @ValueSource(strings = {"chat.repository=jdcb", "chat.message-schema=C", "chat.join-boundary=times"})
    void 없는_값이면_기동에_실패한다(String property) {
        runner.withPropertyValues(property)
                .run(context -> assertThat(context).hasFailed());
    }

    @Configuration
    @EnableConfigurationProperties(ChatProperties.class)
    static class Config {
    }
}
