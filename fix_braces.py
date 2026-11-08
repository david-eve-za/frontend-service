fpath = '/Users/davidgonzalez/Develop/frontend-service/generate_english_crawlers.py'
with open(fpath, 'r') as f:
    content = f.read()

# Fix search_method template - use literal strings
old_search1 = """    @Override
    public String getSearchUrlTemplate() {{
        return "''' + escape_java_string(parsed.search_url_template) + '''";
    }}"""

new_search1 = """    @Override
    public String getSearchUrlTemplate() {
        return "''' + escape_java_string(parsed.search_url_template) + '''";
    }"""

content = content.replace(old_search1, new_search1)

old_search2 = """    @Override
    public List<NovelSearchResult> search(String query) {{
        // TODO: Implementar búsqueda para ''' + source_id + '''
        // Python original usa: ''' + (parsed.search_url_template or "método personalizado") + '''
        log.warn("Search no implementado para {{}}", getSourceId());
        return List.of();
    }}"""

new_search2 = """    @Override
    public List<NovelSearchResult> search(String query) {
        // TODO: Implementar búsqueda para ''' + source_id + '''
        // Python original usa: ''' + (parsed.search_url_template or "método personalizado") + '''
        log.warn("Search no implementado para {}", getSourceId());
        return List.of();
    }"""

content = content.replace(old_search2, new_search2)

# Fix class declaration in template
content = content.replace(
    'public class {class_name} extends AbstractGeneratedCrawler {{',
    'public class {class_name} extends AbstractGeneratedCrawler {'
)

# Fix method endings
content = content.replace(
    'super.initializeCleaner();\n    }}',
    'super.initializeCleaner();\n    }'
)

content = content.replace(
    'return CHAPTER_BODY_SELECTOR;\n    }}',
    'return CHAPTER_BODY_SELECTOR;\n    }'
)

content = content.replace(
    'return CHAPTER_LIST_SELECTOR;\n    }}',
    'return CHAPTER_LIST_SELECTOR;\n    }'
)

with open(fpath, 'w') as f:
    f.write(content)

print("Fixed")