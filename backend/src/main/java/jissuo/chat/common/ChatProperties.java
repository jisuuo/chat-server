package jissuo.chat.common;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 오타가 기본값으로 조용히 바뀌면 실험 조건이 틀린 채 측정되므로, enum으로 받아 없는 값이면 기동에 실패시킨다.
 */
@ConfigurationProperties("chat")
public record ChatProperties(
        @DefaultValue("jdbc") Repository repository,
        @DefaultValue("A") MessageSchema messageSchema,
        @DefaultValue("id") JoinBoundary joinBoundary
) {

    public enum Repository { JDBC }

    /** A: messages PK id 단독, B: messages_b PK (room_id, id) */
    public enum MessageSchema { A, B }

    /** ID: joined_message_id 기준, TIME: joined_at 기준 */
    public enum JoinBoundary { ID, TIME }
}
