# Google XML Document Format Style Guide Summary

This document summarizes key rules and best practices from the Google XML Document Format Style Guide.

## 1. General Principles
-   **Character Encoding:** Primarily use UTF-8 encoding.
-   **Consistency:** Maintain consistent mapping of namespace URIs to prefixes.
-   **Extensibility:** Design for future extensions.

## 2. Structure and Content
-   **Namespaces:** Declare namespaces in the root element.
-   **Element Content:** Elements must contain either nothing, character content, or child elements. **Avoid mixed content.**
-   **Repeating Elements:** Avoid using elements that merely wrap repeating child elements (they don't add value).
-   **Boolean Values:** Express boolean values as `"true"` or `"false"` (matching `xsd:boolean`).
-   **Dates:** Represent dates using RFC 3339 format (subset of ISO 8601, `xsd:dateTime`). Prefer UTC times.
-   **Attributes:**
    -   Do not depend on attribute order.
    -   Avoid overloading elements with too many attributes (max ~10); use child elements instead for better extensibility.
    -   Attributes should not hold values where line breaks are significant.

## 3. Whitespace
-   Be mindful of whitespace in values. Document formats should provide rules for whitespace handling, as parsers and application frameworks may strip it.

## 4. Reusability
-   When creating new XML formats, consider reusing existing formats, especially those allowing extensions. If extending, follow its implicit style and use prescribed elements/attributes sensibly.

*Source: [Google XML Document Format Style Guide](https://google.github.io/styleguide/xmlcstyle.html)*
