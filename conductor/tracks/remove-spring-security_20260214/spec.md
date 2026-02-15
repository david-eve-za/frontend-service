# Track: Remove Spring Security and its Configurations

## Overview
This track aims to completely remove Spring Security and all its associated configurations, annotations, and utility classes from the `frontend-service` project. The decision to remove Spring Security is based on the fact that it is no longer needed and to simplify the project's architecture and reduce overhead.

## Functional Requirements
- Remove the `spring-boot-starter-security` dependency from `pom.xml`.
- Remove all Spring Security-related configuration classes (e.g., `SecurityConfig.java`).
- Remove any Spring Security annotations (e.g., `@PreAuthorize`, `@Secured`, `@EnableWebSecurity`) from controllers, service methods, or other Java classes.
- Identify and remove any utility classes or interfaces whose sole purpose was to support Spring Security functionalities (e.g., custom user details services, security filters that are no longer relevant).
- Ensure the application builds and runs without any security-related errors after removal.

## Non-Functional Requirements
- The application should start up and function correctly without Spring Security.
- The removal should not introduce any new errors or warnings in the build process or at runtime.
- Performance should not degrade as a result of the removal (ideally, it should improve due to reduced overhead).

## Acceptance Criteria
- The `spring-boot-starter-security` dependency is absent from `pom.xml`.
- No Spring Security configuration classes are present in the project.
- No Spring Security annotations are used in the codebase.
- No security-related utility classes exclusively for Spring Security remain.
- The application compiles and runs successfully.
- All existing functionalities (that do not depend on Spring Security) continue to work as expected.

## Out of Scope
- Implementing a new authentication or authorization mechanism.
- Modifying existing business logic that was previously protected by Spring Security (these endpoints will now be publicly accessible).
