package jissuo.chat.message.api.ws;

import jissuo.chat.common.ErrorCode;

/** WS 접속 로그(계획 7 세부 7B)가 처리 결과를 기록할 수 있도록 돌려준다. */
public record FrameOutcome(String type, Long roomId, String result) {

    public static FrameOutcome ok(String type, Long roomId) {
        return new FrameOutcome(type, roomId, "ok");
    }

    public static FrameOutcome rejected(String type, Long roomId, ErrorCode code) {
        return new FrameOutcome(type, roomId, code.name());
    }
}
