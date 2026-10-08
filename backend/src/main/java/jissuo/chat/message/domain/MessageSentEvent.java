package jissuo.chat.message.domain;

/** ADR-132·135: fan-out 신호. 시작 시각과 통로를 실어 비동기로 바꿔도 전체 시간을 같은 뜻으로 잰다. */
public record MessageSentEvent(Message message, long startedNanos, String transport) {
}
