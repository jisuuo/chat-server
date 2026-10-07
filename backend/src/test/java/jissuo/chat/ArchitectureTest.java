package jissuo.chat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.base.DescribedPredicate;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Controller;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import jissuo.chat.auth.AuthUser;
import jissuo.chat.auth.CurrentUser;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

@AnalyzeClasses(packages = "jissuo.chat", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule room_does_not_depend_on_message = noClasses()
            .that().resideInAPackage("..room..")
            .should().dependOnClassesThat().resideInAPackage("..message..");

    @ArchTest
    static final ArchRule message_uses_only_room_domain = noClasses()
            .that().resideInAPackage("..message..")
            .should().dependOnClassesThat(new DescribedPredicate<JavaClass>("room의 domain 밖 클래스") {
                @Override
                public boolean test(JavaClass target) {
                    String targetPackage = target.getPackageName();
                    return (targetPackage.equals("jissuo.chat.room") || targetPackage.startsWith("jissuo.chat.room."))
                            && !targetPackage.equals("jissuo.chat.room.domain")
                            && !targetPackage.startsWith("jissuo.chat.room.domain.");
                }
            });

    @ArchTest
    static final ArchRule domain_is_independent = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..infra..", "..api..", "..application..", "..auth..", "..common..",
                    "org.springframework..");

    @ArchTest
    static final ArchRule application_does_not_depend_on_outer_layers = noClasses()
            .that().resideInAPackage("..application..")
            .should().dependOnClassesThat().resideInAnyPackage("..api..", "..infra..");

    @ArchTest
    static final ArchRule infra_does_not_depend_on_api_or_application = noClasses()
            .that().resideInAPackage("..infra..")
            .should().dependOnClassesThat().resideInAnyPackage("..api..", "..application..");

    @ArchTest
    static final ArchRule api_does_not_depend_on_infra = noClasses()
            .that().resideInAPackage("..api..")
            .should().dependOnClassesThat().resideInAPackage("..infra..");

    @ArchTest
    static final ArchRule room_and_message_do_not_depend_on_user = noClasses()
            .that().resideInAnyPackage("..room..", "..message..")
            .should().dependOnClassesThat().resideInAPackage("..user..");

    @ArchTest
    static final ArchRule user_does_not_depend_on_room_or_message = noClasses()
            .that().resideInAPackage("..user..")
            .should().dependOnClassesThat().resideInAnyPackage("..room..", "..message..");

    @ArchTest
    static final ArchRule auth_does_not_depend_on_domain = noClasses()
            .that().resideInAPackage("..auth..")
            .should().dependOnClassesThat().resideInAPackage("..domain..");

    // ADR-023: 기능 코드는 감사 구현을 몰라야 수신·저장 방식을 독립적으로 바꿀 수 있다.
    @ArchTest
    static final ArchRule nothing_depends_on_audit = noClasses()
            .that().resideOutsideOfPackage("..audit..")
            .should().dependOnClassesThat().resideInAPackage("..audit..");

    @ArchTest
    static void protected_api_handlers_require_current_user(JavaClasses classes) {
        List<String> violations = new ArrayList<>();
        List<String> protectedHandlers = new ArrayList<>();
        classes.stream().map(javaClass -> javaClass.reflect())
                .filter(type -> AnnotatedElementUtils.hasAnnotation(type, Controller.class))
                .forEach(type -> inspectController(type, protectedHandlers, violations));
        assertFalse(protectedHandlers.isEmpty(), "보호 API 핸들러를 하나도 찾지 못했다");
        assertTrue(violations.isEmpty(), () -> "@CurrentUser AuthUser가 없는 보호 API: " + violations);
    }

    @Test
    void inherited_api_handler_is_checked() {
        List<String> protectedHandlers = new ArrayList<>();
        List<String> violations = new ArrayList<>();
        inspectController(InheritedController.class, protectedHandlers, violations);
        assertEquals(List.of("InheritedController.inherited /api/inherited"), protectedHandlers);
        assertEquals(protectedHandlers, violations);
    }

    private static void inspectController(Class<?> type, List<String> protectedHandlers, List<String> violations) {
        RequestMapping classMapping = AnnotatedElementUtils.findMergedAnnotation(type, RequestMapping.class);
        for (Method method : ReflectionUtils.getUniqueDeclaredMethods(type)) {
            RequestMapping methodMapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
            if (methodMapping == null) {
                continue;
            }
            for (String classPath : paths(classMapping)) {
                for (String methodPath : paths(methodMapping)) {
                    String path = ("/" + classPath + "/" + methodPath).replaceAll("/+", "/");
                    if (isProtectedApiPath(path)) {
                        String handler = type.getSimpleName() + "." + method.getName() + " " + path;
                        protectedHandlers.add(handler);
                        if (Arrays.stream(method.getParameters()).noneMatch(
                                parameter -> parameter.getType() == AuthUser.class
                                        && parameter.isAnnotationPresent(CurrentUser.class))) {
                            violations.add(handler);
                        }
                    }
                }
            }
        }
    }

    static class ParentController {
        @GetMapping("/api/inherited")
        void inherited() {
        }
    }

    @RestController
    static class InheritedController extends ParentController {
    }

    private static String[] paths(RequestMapping mapping) {
        return mapping == null || mapping.path().length == 0 ? new String[]{""} : mapping.path();
    }

    private static boolean isProtectedApiPath(String path) {
        return path.startsWith("/api/") && !path.startsWith("/api/dev/");
    }
}
