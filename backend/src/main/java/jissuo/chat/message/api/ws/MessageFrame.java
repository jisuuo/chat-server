package jissuo.chat.message.api.ws;

import jissuo.chat.message.api.MessageResponse;

/** S→C {"type":"message","message":{...}} (ADR-131) */
record MessageFrame(String type, MessageResponse message) {

    static MessageFrame of(MessageResponse message) {
        return new MessageFrame("message", message);
    }
}
