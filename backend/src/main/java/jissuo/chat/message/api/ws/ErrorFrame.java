package jissuo.chat.message.api.ws;

import jissuo.chat.common.ErrorCode;

/** 오류는 보낸 세션에만 전달하며 문구는 REST 응답과 같다(ADR-131). */
record ErrorFrame(String type, Long roomId, String code, String message) {

    static ErrorFrame of(Long roomId, ErrorCode code) {
        return new ErrorFrame("error", roomId, code.name(), code.message());
    }
}
