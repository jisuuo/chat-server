package jissuo.chat.message.api.ws;

/** C→S send 프레임. 응답 짝 맞춤 id는 두지 않는다(ADR-131, ADR-034, F33). */
record SendFrame(String type, Long roomId, String content) {
}
