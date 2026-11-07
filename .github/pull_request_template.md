## Pull Request Template

### Description
Brief description of the crawler/source added or modified.

### Type
- [ ] New SourceCrawler implementation
- [ ] Core interface/registry change
- [ ] Frontend integration
- [ ] Documentation
- [ ] Bug fix

### Source Information (if new crawler)
- **Source ID**: `source-id` (kebab-case)
- **Display Name**: `Display Name`
- **Language**: `es` / `en` / `multi` / other
- **Base URL**: `https://example.com/`
- **Pattern**: `SoupTemplate` / `WpCategoryTemplate` / `LegacyCrawler` / `API` / `Custom`
- **Rate Limit**: `N` RPM

### Testing
- [ ] Unit tests pass (`mvn test`)
- [ ] Integration tests pass (`mvn verify -Pintegration`)
- [ ] WireMock stubs for new source
- [ ] HTML fixtures in `src/test/resources/fixtures/<source>/`
- [ ] Playwright tests for frontend (if applicable)
- [ ] JaCoCo ≥ 80% on new code

### Checklist
- [ ] `SourceCrawler` implements all methods
- [ ] `SourceMetadata` populated correctly
- [ ] Rate limit configured in `application.yml`
- [ ] Flyway migration added (if schema change)
- [ ] ADR updated (if architectural decision)
- [ ] CHANGELOG.md updated
- [ ] Crawler fiche in `/docs/crawlers/<source>.md`
- [ ] No console errors in chrome-devtools
- [ ] Responsive tested (mobile/tablet/desktop)
- [ ] Accessibility: keyboard nav, ARIA, contrast

### Screenshots (if frontend changes)
| Desktop | Mobile |
|---------|--------|
| ![desktop](url) | ![mobile](url) |

### Breaking Changes
- [ ] Yes - Describe:
- [ ] No

### Rollback Plan
If this PR causes issues in production:
1. Disable via config: `app.crawler.{source-id}.enabled=false`
2. Remove `@Component` annotation (or use `@Profile("!prod")`)
3. Source disappears from `/api/sources` and frontend menu automatically