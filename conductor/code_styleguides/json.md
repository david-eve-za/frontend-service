# Google JSON Style Guide Summary

This document summarizes key rules and best practices from the Google JSON Style Guide. The guide focuses on standardizing JSON APIs at Google.

## 1. General Principles
-   **Consistency:** Be consistent with existing API patterns.
-   **Readability:** Prefer human-readable formats.
-   **Simplicity:** Keep structures simple and straightforward.

## 2. Naming Conventions
-   **Field Names:** Use `lowerCamelCase` for all field names.
    -   Example: `firstName`, `lastLoginTime`
-   **Acronyms:** Treat acronyms as ordinary words for casing.
    -   Example: `rpcMethod`, not `RPCMethod`

## 3. Data Types
-   **Timestamps:** Represent timestamps as strings in RFC 3339 format (e.g., `"YYYY-MM-DDTHH:MM:SSZ"`).
-   **Durations:** Represent durations as strings, with unit suffix (e.g., `"3.5s"`, `"10ms"`).
-   **Enums:** Represent enum values as strings using `UPPER_SNAKE_CASE`.

## 4. Structure and Representation
-   **Root Element:** A JSON response should typically be a JSON object (`{}`) or an array (`[]`), not a bare literal.
-   **Empty Values:** Omit fields with empty values (e.g., `null`, empty string, empty array) unless their presence conveys specific meaning.
-   **Plural Nouns:** For collections of resources, use plural field names.
    -   Example: `users: [...]`, `orders: [...]`
-   **Resource Names:** If a field represents a resource name, follow the pattern `parent/ID/child/ID`.

## 5. Versioning
-   API versions should be indicated in the URL (e.g., `/v1/resource`).

*Source: [Google JSON Style Guide](https://google.github.io/styleguide/jsoncstyle.html)*
