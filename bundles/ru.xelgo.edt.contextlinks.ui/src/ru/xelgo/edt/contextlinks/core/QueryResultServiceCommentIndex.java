package ru.xelgo.edt.contextlinks.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.xtext.resource.XtextResource;

/**
 * Parses service regions with query result schemas from a BSL resource.
 */
final class QueryResultServiceCommentIndex
{
    private static final String REGION_MARKER = "edt_tweaks_query_result_"; //$NON-NLS-1$
    private static final String LINE_COMMENT = "//"; //$NON-NLS-1$
    private static final String TYPE_SEPARATOR = "|"; //$NON-NLS-1$
    private static final QueryResultServiceCommentIndex EMPTY =
        new QueryResultServiceCommentIndex(Map.of());
    private static final Map<Resource, CacheEntry> CACHE = Collections.synchronizedMap(new WeakHashMap<>());

    private final Map<String, ResultTable> tablesByKey;

    private QueryResultServiceCommentIndex(Map<String, ResultTable> tablesByKey)
    {
        this.tablesByKey = Collections.unmodifiableMap(new LinkedHashMap<>(tablesByKey));
    }

    static QueryResultServiceCommentIndex get(EObject object)
    {
        Resource resource = object != null ? object.eResource() : null;
        if (!(resource instanceof XtextResource xtextResource) || xtextResource.getParseResult() == null
            || xtextResource.getParseResult().getRootNode() == null)
            return EMPTY;

        String text = xtextResource.getParseResult().getRootNode().getText();
        if (text == null || text.isBlank() || !text.contains(REGION_MARKER))
            return EMPTY;

        int hash = text.hashCode();
        int length = text.length();
        synchronized (CACHE)
        {
            CacheEntry cached = CACHE.get(resource);
            if (cached != null && cached.hash == hash && cached.length == length)
                return cached.index;

            QueryResultServiceCommentIndex index = parse(text);
            CACHE.put(resource, new CacheEntry(hash, length, index));
            return index;
        }
    }

    ResultTable getTable(String key)
    {
        if (key == null || key.isBlank())
            return null;
        return tablesByKey.get(normalizeKey(key));
    }

    boolean isEmpty()
    {
        return tablesByKey.isEmpty();
    }

    private static QueryResultServiceCommentIndex parse(String text)
    {
        Map<String, ResultTableBuilder> builders = new LinkedHashMap<>();
        ResultTableBuilder currentTable = null;
        boolean inRegion = false;
        int offset = 0;

        while (offset <= text.length())
        {
            int lineBreak = text.indexOf('\n', offset);
            int lineEnd = lineBreak >= 0 ? lineBreak : text.length();
            if (lineEnd > offset && text.charAt(lineEnd - 1) == '\r')
                lineEnd--;
            String line = text.substring(offset, lineEnd);
            String trimmed = line.trim();
            String lower = trimmed.toLowerCase(Locale.ROOT);

            if (isRegionStart(lower))
            {
                inRegion = true;
                currentTable = null;
            }
            else if (inRegion && isRegionEnd(lower))
            {
                inRegion = false;
                currentTable = null;
            }
            else if (inRegion && trimmed.startsWith(LINE_COMMENT))
            {
                String comment = trimmed.substring(LINE_COMMENT.length()).trim();
                if (comment.startsWith("*")) //$NON-NLS-1$
                {
                    if (currentTable != null)
                        currentTable.addColumn(parseColumn(comment, offset));
                }
                else if (!comment.isBlank())
                {
                    currentTable = new ResultTableBuilder(comment);
                    builders.put(normalizeKey(comment), currentTable);
                }
            }

            if (lineBreak < 0)
                break;
            offset = lineBreak + 1;
        }

        Map<String, ResultTable> tables = new LinkedHashMap<>();
        builders.forEach((key, builder) -> {
            ResultTable table = builder.build();
            if (!table.getColumns().isEmpty())
                tables.put(key, table);
        });
        return tables.isEmpty() ? EMPTY : new QueryResultServiceCommentIndex(tables);
    }

    private static boolean isRegionStart(String lower)
    {
        return lower.startsWith("#\u043e\u0431\u043b\u0430\u0441\u0442\u044c " + REGION_MARKER) //$NON-NLS-1$
            || lower.startsWith("#region " + REGION_MARKER); //$NON-NLS-1$
    }

    private static boolean isRegionEnd(String lower)
    {
        return lower.startsWith("#\u043a\u043e\u043d\u0435\u0446\u043e\u0431\u043b\u0430\u0441\u0442\u0438") //$NON-NLS-1$
            || lower.startsWith("#endregion"); //$NON-NLS-1$
    }

    private static Column parseColumn(String comment, int offset)
    {
        String body = comment.substring(1).trim();
        int separator = body.indexOf('-');
        if (separator < 0)
            return new Column(body, List.of(), offset);

        String name = body.substring(0, separator).trim();
        String type = body.substring(separator + 1).trim();
        if (type.endsWith("-")) //$NON-NLS-1$
            type = type.substring(0, type.length() - 1).trim();

        List<String> typeNames = type.isBlank() ? List.of() : splitTypes(type);
        return new Column(name, typeNames, offset);
    }

    private static List<String> splitTypes(String value)
    {
        List<String> result = new ArrayList<>();
        for (String item : value.split("\\" + TYPE_SEPARATOR)) //$NON-NLS-1$
        {
            String name = item.trim();
            if (!name.isEmpty())
                result.add(name);
        }
        return result;
    }

    private static String normalizeKey(String key)
    {
        return key.replaceAll("\\s+", "") //$NON-NLS-1$ //$NON-NLS-2$
            .toLowerCase(Locale.ROOT);
    }

    static final class ResultTable
    {
        private final String key;
        private final List<Column> columns;

        private ResultTable(String key, List<Column> columns)
        {
            this.key = key;
            this.columns = Collections.unmodifiableList(new ArrayList<>(columns));
        }

        String getKey()
        {
            return key;
        }

        List<Column> getColumns()
        {
            return columns;
        }
    }

    static final class Column
    {
        private final String name;
        private final List<String> typeNames;
        private final int offset;

        private Column(String name, List<String> typeNames, int offset)
        {
            this.name = name != null ? name.trim() : ""; //$NON-NLS-1$
            this.typeNames = Collections.unmodifiableList(new ArrayList<>(typeNames));
            this.offset = offset;
        }

        String getName()
        {
            return name;
        }

        List<String> getTypeNames()
        {
            return typeNames;
        }

        int getOffset()
        {
            return offset;
        }
    }

    private static final class ResultTableBuilder
    {
        private final String key;
        private final List<Column> columns = new ArrayList<>();

        ResultTableBuilder(String key)
        {
            this.key = key;
        }

        void addColumn(Column column)
        {
            if (column != null && !column.getName().isBlank())
                columns.add(column);
        }

        ResultTable build()
        {
            return new ResultTable(key, columns);
        }
    }

    private static final class CacheEntry
    {
        private final int hash;
        private final int length;
        private final QueryResultServiceCommentIndex index;

        CacheEntry(int hash, int length, QueryResultServiceCommentIndex index)
        {
            this.hash = hash;
            this.length = length;
            this.index = index;
        }
    }
}
