package jissuo.chat.message.infra.jpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import jissuo.chat.common.ChatProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class JpaMessageRepositoryConfigTest {

    @Test
    void jpa와_메시지_스키마_B를_함께_선택하면_기동에_실패한다() {
        new ApplicationContextRunner()
                .withUserConfiguration(JpaMessageRepositoryConfig.class)
                .withPropertyValues("chat.repository=jpa")
                .withBean(SpringDataMessageRepository.class, () -> mock(SpringDataMessageRepository.class))
                .withBean(ChatProperties.class, () -> new ChatProperties(
                        ChatProperties.Repository.JPA,
                        ChatProperties.MessageSchema.B,
                        ChatProperties.JoinBoundary.ID))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalStateException.class)
                            .hasRootCauseMessage("JPA 구현은 메시지 스키마 A만 지원한다");
                });
    }
}
