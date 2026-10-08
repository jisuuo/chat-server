package jissuo.chat.common.metrics;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** ADR-135: Spring을 모르는 domain 대신 빈의 공개 메서드에 단계를 표시한다. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface DeliveryStage {
    String value();
    String transport() default "";
}
