# Task 1 완료조건 (Mock Authentication Infrastructure)

- [x] 1. `back/src/main/java/com/chatserver/security/MockUserIdAuthenticationFilter.java`가 존재하며 `OncePerRequestFilter`를 상속하고 헤더 이름을 나타내는 `USER_ID_HEADER` 상수(`"X-User-Id"`)를 정의한다.
- [x] 2. `X-User-Id` 헤더가 존재하고 공백이 아닌 값일 때 `SecurityContextHolder`에 해당 값을 principal name으로, authorities는 빈 컬렉션으로 하는 `Authentication`이 설정된다.
- [x] 3. `X-User-Id` 헤더가 아예 없는 요청에서는 `SecurityContextHolder`에 인증 정보가 설정되지 않는다.
- [x] 4. `X-User-Id` 헤더 값이 공백 문자열(예: `" "`, `"\t"`)일 때도 헤더가 없는 경우와 동일하게 인증 정보가 설정되지 않는다.
- [x] 5. 헤더 존재/부재/공백 세 경우 모두에서 예외 없이 `filterChain.doFilter(request, response)`가 호출된다.
- [x] 6. `back/src/main/java/com/chatserver/security/RestAuthenticationEntryPoint.java`가 `@Component`로 등록된 `AuthenticationEntryPoint` 구현체이며, 인증 실패 시 HTTP 401 상태 코드와 `Content-Type: application/json`(또는 `application/json;charset=UTF-8`) 응답을 반환한다. (수정: `response.setCharacterEncoding("UTF-8")` 추가 — 원래 컨테이너 기본값 ISO-8859-1로 나가던 버그 수정)
- [x] 7. 401 응답 바디가 Jackson `ObjectMapper`로 직렬화된 정확히 `{"code":"UNAUTHORIZED","message":"Authentication required","details":null}` JSON 구조와 일치한다.
- [x] 8. `back/src/main/java/com/chatserver/security/SecurityConfig.java`는 `@Configuration @EnableWebSecurity`이며, `SecurityFilterChain` 빈 설정에서 CSRF가 비활성화되고 세션 생성 정책이 `SessionCreationPolicy.STATELESS`로 설정되어 있다.
- [x] 9. `curl`로 `X-User-Id` 헤더 없이 `/api/**` 하위 경로에 요청하면 401과 지정된 JSON 바디가 반환되고, `/api/**`가 아닌 경로(예: `/actuator/health`, `/`)는 `X-User-Id` 헤더 없이도 401이 아닌 응답(permitAll)이 반환된다. (curl로 실측: /api/whatever → 401 + JSON, / → 404, /api/whatever + 헤더 → 404 non-401)
- [x] 10. `MockUserIdAuthenticationFilter`가 `SecurityConfig`에서 `UsernamePasswordAuthenticationFilter` 이전 순서로 등록되어 있고, 컨트롤러 코드는 `java.security.Principal`만 참조하며 `MockUserIdAuthenticationFilter` 타입을 import하거나 직접 의존하지 않는다. (아직 컨트롤러 없음 — grep 결과 필터 타입 참조는 security 패키지 2개 파일뿐)
