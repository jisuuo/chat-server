package jissuo.chat.message.domain;

/**
 * 전달 시간을 재는 시작점 (계획 7 세부 7A). ThreadLocal은 비동기 전달로 바꿀 때 끊기므로(F30과 같은 이유) 값으로 넘긴다.
 * transport: rest | ws | internal(서비스를 직접 부르는 실험 코드)
 */
public record DeliveryOrigin(String transport, long startedNanos) {

    public static DeliveryOrigin start(String transport) {
        return new DeliveryOrigin(transport, System.nanoTime());
    }
}
