/*
 * Copyright 2000-2020 Vaadin Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.vaadin.flow.server.connect.auth;

import javax.annotation.security.DenyAll;
import javax.annotation.security.PermitAll;
import javax.annotation.security.RolesAllowed;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.vaadin.flow.server.VaadinService;

/**
 * Component used for checking role-based ACL in Vaadin Endpoints.
 * <p>
 * For each request that is trying to access the method in the corresponding
 * Vaadin Endpoint, the permission check is carried on.
 * <p>
 * It looks for {@link AnonymousAllowed} {@link PermitAll}, {@link DenyAll} and
 * {@link RolesAllowed} annotations in endpoint methods and classes containing
 * these methods (no super classes' annotations are taken into account).
 * <p>
 * Method-level annotation override Class-level ones.
 * <p>
 * In the next example, since the class is denied to all, method1 is not
 * accessible to anyone, method2 can be executed by any authorized used, method3
 * is only allowed to the accounts having the ROLE_USER authority and method4 is
 * available for every user, including anonymous ones that don't provide any
 * token in their requests.
 *
 * <pre class="code">
 * &#64;Endpoint
 * &#64;DenyAll
 * public class DemoEndpoint {
 *
 *     public void method1() {
 *     }
 *
 *     &#64;PermitAll
 *     public void method2() {
 *     }
 *
 *     &#64;RolesAllowed("ROLE_USER")
 *     public void method3() {
 *     }
 *
 *     &#64;AnonymousAllowed
 *     public void method4() {
 *     }
 * }
 * </pre>
 *
 */
public class VaadinConnectAccessChecker {

    private boolean xsrfProtectionEnabled = true;

    /**
     * Check that the endpoint is accessible for the current user.
     *
     * @param method
     *            the Vaadin endpoint method to check ACL
     * @return an error String with an issue description, if any validation
     *         issues occur, {@code null} otherwise
     * @param request
     *            the request that triggers the <code>method</code> invocation
     */
    public String check(Method method, HttpServletRequest request) {
        if (request.getUserPrincipal() != null) {
            return verifyAuthenticatedUser(method, request);
        } else {
            return verifyAnonymousUser(method, request);
        }
    }

    /**
     * Gets the entity to check for Vaadin endpoint security restrictions.
     *
     * @param method
     *            the method to analyze, not {@code null}
     * @return the entity that is responsible for security settings for the
     *         method passed
     * @throws IllegalArgumentException
     *             if the method is not public
     */
    public AnnotatedElement getSecurityTarget(Method method) {
        if (!Modifier.isPublic(method.getModifiers())) {
            throw new IllegalArgumentException(String.format(
                    "The method '%s' is not public hence cannot have a security target",
                    method));
        }
        return hasSecurityAnnotation(method) ? method
                : method.getDeclaringClass();
    }

    private String verifyAnonymousUser(Method method,
            HttpServletRequest request) {
        if (getSecurityTarget(method).isAnnotationPresent(
                AnonymousAllowed.class) && canAccessMethod(method, request)) {
            return null;
        }

        return "Anonymous access is not allowed";
    }

    private String verifyAuthenticatedUser(Method method,
            HttpServletRequest request) {
        if (canAccessMethod(method, request)) {
            return null;
        }

        if (isDevMode()) {
            // suggest access control annotations in dev mode
            return "Unauthorized access to Vaadin endpoint; "
                    + "to enable endpoint access use one of the following "
                    + "annotations: @AnonymousAllowed, @PermitAll, "
                    + "@RolesAllowed";
        } else {
            return "Unauthorized access to Vaadin endpoint";
        }
    }

    private boolean isDevMode() {
        VaadinService vaadinService = VaadinService.getCurrent();
        return (vaadinService != null && !vaadinService
                .getDeploymentConfiguration().isProductionMode());
    }

    private boolean canAccessMethod(Method method, HttpServletRequest request) {
        return validateCsrfTokenInRequest(request)
                && annotationAllowsAccess(getSecurityTarget(method), request);
    }

    /**
     * Validates the CSRF token that is included in the request.
     * <p>
     * Checks that the CSRF token in the request matches the expected one that
     * is stored in the HTTP session.
     * <p>
     * Note! If there is no session, this method will always return
     * {@code true}.
     * <p>
     * Note! If CSRF protection is disabled, this method will always return
     * {@code true}.
     * 
     * @param request
     *            the request to validate
     * @return {@code true} if the CSRF token is ok or checking is disabled or
     *         there is no HTTP session, {@code false} otherwise
     */
    private boolean validateCsrfTokenInRequest(HttpServletRequest request) {
        if (!xsrfProtectionEnabled) {
            return true;
        }

        HttpSession session = request.getSession(false);
        if (session == null) {
            return true;
        }

        String csrfTokenInSession = (String) session
                .getAttribute(VaadinService.getCsrfTokenAttributeName());
        if (csrfTokenInSession == null) {
            if (getLogger().isInfoEnabled()) {
                getLogger().info(
                        "Unable to verify CSRF token for endpoint request, got null token in session");
            }

            return false;
        }

        String csrfTokenInRequest = request.getHeader("X-CSRF-Token");
        if (csrfTokenInRequest == null || !MessageDigest.isEqual(
                csrfTokenInSession.getBytes(StandardCharsets.UTF_8),
                csrfTokenInRequest.getBytes(StandardCharsets.UTF_8))) {
            if (getLogger().isInfoEnabled()) {
                getLogger().info("Invalid CSRF token in endpoint request");
            }

            return false;
        }

        return true;
    }

    private boolean annotationAllowsAccess(
            AnnotatedElement annotatedClassOrMethod,
            HttpServletRequest request) {
        if (annotatedClassOrMethod.isAnnotationPresent(DenyAll.class)) {
            return false;
        }
        if (annotatedClassOrMethod
                .isAnnotationPresent(AnonymousAllowed.class)) {
            return true;
        }
        RolesAllowed rolesAllowed = annotatedClassOrMethod
                .getAnnotation(RolesAllowed.class);
        if (rolesAllowed == null) {
            return annotatedClassOrMethod.isAnnotationPresent(PermitAll.class);
        } else {
            return roleAllowed(rolesAllowed, request);
        }
    }

    private boolean roleAllowed(RolesAllowed rolesAllowed,
            HttpServletRequest request) {
        for (String role : rolesAllowed.value()) {
            if (request.isUserInRole(role)) {
                return true;
            }
        }

        return false;
    }

    private boolean hasSecurityAnnotation(Method method) {
        return method.isAnnotationPresent(AnonymousAllowed.class)
                || method.isAnnotationPresent(PermitAll.class)
                || method.isAnnotationPresent(DenyAll.class)
                || method.isAnnotationPresent(RolesAllowed.class);
    }

    /**
     * Enable or disable XSRF token checking in endpoints.
     *
     * @param xsrfProtectionEnabled
     *            enable or disable protection.
     */
    public void enableCsrf(boolean xsrfProtectionEnabled) {
        this.xsrfProtectionEnabled = xsrfProtectionEnabled;
    }

    private static Logger getLogger() {
        return LoggerFactory.getLogger(VaadinConnectAccessChecker.class);
    }
}
