package jissuo.chat.message.api.ws;

import jissuo.chat.message.api.MessageResponse;

/** S→C {"type":"message","message":{...}} (계획 7 세부 3) */
record MessageFrame(String type, MessageResponse message) {

    static MessageFrame of(MessageResponse message) {
        return new MessageFrame("message", message);
    }
}
