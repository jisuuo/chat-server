package jissuo.chat.auth;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 비동기 전송(Step 2)에서 값이 사라지지 않도록 ThreadLocal 대신 파라미터로 명시적으로 넘긴다 (ADR-006).
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CurrentUser {
}
