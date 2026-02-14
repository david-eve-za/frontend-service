# Google Java Style Guide Summary

This document summarizes key rules and best practices from the Google Java Style Guide.

## 1. Formatting
-   **Braces:** Follow Kernighan and Ritchie (K&R) style for non-empty blocks.
    -   Line break after opening brace and before closing brace.
-   **Block Indentation:** Increase by two spaces for each new block.
-   **One Statement Per Line:** Each statement should be followed by a line break.
-   **Column Limit:** 100 characters (soft limit 110, wrap lines longer than 120).
-   **Line-wrapping:** When code on a single line is divided, apply line-wrapping.
-   **Whitespace:** Specific rules apply to whitespace usage.
-   **Grouping Parentheses:** Recommended for clarity.

## 2. Naming Conventions
-   **`camelCase`:** For most variables, methods, and instance fields.
-   **`PascalCase` (Capitalized CamelCase):** For class and interface names.
-   **`CONSTANT_CASE`:** For constants (all uppercase with underscores).

## 3. Imports
-   **Wildcard Imports:** Avoid wildcard imports (`*`).

## 4. Switch Statements
-   Recommend using the new switch syntax (Java 17+) with arrows to avoid fallthrough.

## 5. Other Best Practices
-   **IDE Integration:** Many IDEs offer plugins to automatically format code according to this guide.

*Source: [Google Java Style Guide](https://google.github.io/styleguide/javaguide.html)*
