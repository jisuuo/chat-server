package jissuo.chat.message.api.ws;

/** C→S send 프레임. 응답 짝 맞춤 id는 두지 않는다(계획 7 세부 3, ADR-034, F33). */
record SendFrame(String type, Long roomId, String content) {
}
