package jissuo.chat.message.api;

import java.util.List;
import jissuo.chat.message.application.MessagePage;

public record MessagePageResponse(List<MessageResponse> messages, boolean hasMore) {

    static MessagePageResponse from(MessagePage page) {
        return new MessagePageResponse(page.messages().stream().map(MessageResponse::from).toList(), page.hasMore());
    }
}
