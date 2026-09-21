package com.college.backlog.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every endpoint is role-guarded unless it is named here as deliberately not. Authorization comes
 * from four layers — SecurityConfig URL rules, class-level {@code @PreAuthorize}, method-level
 * {@code @PreAuthorize}, and service-level CallerScope/ProctorScopeService — and nothing else
 * asserts the layering holds. Cypress cannot: every spec stubs its API calls, so no spec can observe
 * a 403.
 *
 * <p><b>Its value is the endpoints that do not exist yet.</b> A new handler added without an
 * annotation fails this test, and the author has to put it in one of the two lists below
 * DELIBERATELY rather than forget. That is the whole point — do not "fix" a failure by pasting the
 * new path into a list without deciding it belongs there.
 *
 * <p>This checks that a rule EXISTS, not that it is the right one. Whether HOD may reach a given
 * endpoint belongs to the role-matrix suite; it is deliberately not duplicated here.
 */
@SpringBootTest
@ActiveProfiles("test")
class EndpointAuthorizationInventoryTest {

    /** The six {@code permitAll} entries in SecurityConfig. Anything added here must be added there
     *  too — the two lists are kept in step by hand, which is why the rot check below exists. */
    private static final Set<String> PUBLIC_ENDPOINTS = Set.of(
            "POST /api/auth/login",
            "POST /api/auth/logout",
            "POST /api/student/auth/login",
            "POST /api/student/auth/logout",
            "GET /api/departments",
            "GET /api/registration-status");

    /**
     * Authenticated, but deliberately role-agnostic: no {@code @PreAuthorize} and no {@code
     * permitAll}, so it lands on {@code anyRequest().authenticated()}. Changing your OWN password is
     * every staff role's business, and the caller's identity comes from the JWT, never the body.
     * A third category, not an oversight — a two-bucket guard would report this as a violation and
     * the honest fix would be to name it, which is what this is.
     */
    private static final Set<String> AUTHENTICATED_ANY_ROLE = Set.of(
            "POST /api/auth/change-password",
            // Same category and same reasoning: renaming your OWN account is every staff role's
            // business, the target is the caller's own row, and the identity comes from the JWT.
            // Renaming SOMEONE ELSE is a different endpoint, PATCH /api/admin/users/{username},
            // which is ADMIN-only and role-guarded.
            "POST /api/auth/change-username");

    @Autowired
    private RequestMappingHandlerMapping handlerMapping;

    @Test
    void everyEndpointIsRoleGuardedOrExplicitlyExempt() {
        Set<String> unguarded = new TreeSet<>();
        Set<String> all = new TreeSet<>();

        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry
                : handlerMapping.getHandlerMethods().entrySet()) {
            HandlerMethod handler = entry.getValue();
            // Spring's own handlers (BasicErrorController) are not ours to annotate.
            if (!handler.getBeanType().getPackageName().startsWith("com.college.backlog")) {
                continue;
            }
            Set<String> descriptors = descriptorsOf(entry.getKey());
            all.addAll(descriptors);
            if (!hasPreAuthorize(handler)) {
                unguarded.addAll(descriptors);
            }
        }

        // Guard against a vacuous pass: an empty or tiny inventory would satisfy every assertion
        // below while proving nothing.
        assertThat(all)
                .as("handler inventory — a near-empty one means the mapping was not read")
                .hasSizeGreaterThan(40)
                .contains("POST /api/register");

        assertThat(unguarded)
                .as("endpoints with no @PreAuthorize; add the annotation, or list it above "
                    + "deliberately as public / authenticated-any-role")
                .containsExactlyInAnyOrderElementsOf(union(PUBLIC_ENDPOINTS, AUTHENTICATED_ANY_ROLE));
    }

    /** An exemption for a path that no longer exists silently exempts nothing and hides a rename. */
    @Test
    void noExemptionOutlivesItsEndpoint() {
        Set<String> all = new TreeSet<>();
        handlerMapping.getHandlerMethods().forEach((info, handler) -> {
            if (handler.getBeanType().getPackageName().startsWith("com.college.backlog")) {
                all.addAll(descriptorsOf(info));
            }
        });

        assertThat(all).as("stale entries in PUBLIC_ENDPOINTS / AUTHENTICATED_ANY_ROLE")
                .containsAll(union(PUBLIC_ENDPOINTS, AUTHENTICATED_ANY_ROLE));
    }

    private static boolean hasPreAuthorize(HandlerMethod handler) {
        return AnnotatedElementUtils.hasAnnotation(handler.getMethod(), PreAuthorize.class)
                || AnnotatedElementUtils.hasAnnotation(handler.getBeanType(), PreAuthorize.class);
    }

    /** "GET /api/departments". One handler can map several methods and patterns, hence a set. */
    private static Set<String> descriptorsOf(RequestMappingInfo info) {
        Set<String> patterns = info.getPathPatternsCondition() != null
                ? info.getPathPatternsCondition().getPatternValues()
                : Set.of();
        Set<RequestMethod> methods = info.getMethodsCondition().getMethods();

        Set<String> descriptors = new LinkedHashSet<>();
        for (String pattern : patterns) {
            if (methods.isEmpty()) {
                descriptors.add("ANY " + pattern);
            } else {
                methods.forEach(m -> descriptors.add(m + " " + pattern));
            }
        }
        return descriptors;
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> merged = new TreeSet<>(a);
        merged.addAll(b);
        return merged;
    }
}
