package jissuo.chat.message.application;

import java.util.List;
import jissuo.chat.message.domain.Message;

public record MessagePage(List<Message> messages, boolean hasMore) {
}
