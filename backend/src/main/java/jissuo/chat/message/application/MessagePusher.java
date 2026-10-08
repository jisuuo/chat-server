package jissuo.chat.message.application;

import java.util.List;
import jissuo.chat.message.domain.DeliveryOrigin;
import jissuo.chat.message.domain.Message;

/** 계획 7 세부 5: application은 WebSocket을 모른다. 구현은 message/api/ws에 있다. */
public interface MessagePusher {

    // origin은 push 단계 시간에 통로 태그를 붙이는 데 쓴다 (계획 7 세부 7A)
    void push(List<Long> userIds, Message message, DeliveryOrigin origin);
}
