package jissuo.chat.auth;

import jissuo.chat.common.ChatException;
import jissuo.chat.common.ErrorCode;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

public class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentUser.class)
                && parameter.getParameterType() == AuthUser.class;
    }

    @Override
    public AuthUser resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                    NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        Object user = webRequest.getAttribute(AuthFilter.ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        if (user == null) {
            // 필터가 건너뛴 경로(/api/dev/** 등)에서 @CurrentUser를 쓰면 컨트롤러가 null을 받아 500이 나므로 401로 돌려준다
            throw new ChatException(ErrorCode.UNAUTHENTICATED);
        }
        return (AuthUser) user;
    }
}
