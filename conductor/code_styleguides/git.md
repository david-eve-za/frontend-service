# Google-Inspired Git Best Practices

This document outlines Git best practices inspired by Google's engineering principles, with a strong emphasis on clear and consistent commit messages. While there isn't a single official "Google Git Style Guide," these practices align with their focus on maintainability and readability.

## 1. Commit Message Guidelines
Inspired by conventional commits and Google's project-specific guidelines (e.g., Blockly), commit messages should be:

-   **Concise and Descriptive:** Clearly state the purpose of the commit.
-   **Structured:** Follow a `<type>: <description>` format.
    -   **`<type>`:** Categorizes the change (e.g., `feat`, `fix`, `docs`, `style`, `refactor`, `test`, `chore`).
    -   **`<description>`:** A brief, imperative statement summarizing the change. Start with a capital letter and do not end with a period.
-   **Optional Body:** Provide more detailed explanations if necessary, explaining the *why* and *how* of the change.
-   **Optional Footer:** Reference issues, pull requests, or other related work.

### Examples of Commit Messages:
-   `feat(authentication): Add OAuth2 login support`
-   `fix(ui): Correct modal display on mobile devices`
-   `docs(api): Update README with new endpoint details`
-   `style(dashboard): Refactor CSS for better readability`
-   `refactor(core): Extract common utility functions`
-   `test(parser): Add edge case tests for CSV parsing`
-   `chore(deps): Update Spring Boot to 3.x`

## 2. Atomic Commits
-   Each commit should represent a single logical change. Avoid combining unrelated changes into one commit. This simplifies review and debugging.

## 3. Branching Strategy
-   Prefer a simple branching strategy (e.g., Git Flow or GitHub Flow) that promotes clear isolation of work and easy integration. (Specific strategy may vary by project).

## 4. Code Reviews
-   All changes should be reviewed by at least one other developer before being merged. (Google's "CL" and "LGTM" culture).

*Source: Inspired by Google Engineering Practices and Project Guidelines*
