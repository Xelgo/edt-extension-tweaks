package ru.xelgo.edt.contextlinks.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * Small JSON helper for snapshot persistence.
 */
final class VariableSnapshotJson
{
    private VariableSnapshotJson()
    {
        // Utility class.
    }

    static String quote(String value)
    {
        if (value == null)
            return "null"; //$NON-NLS-1$

        StringBuilder result = new StringBuilder(value.length() + 2);
        result.append('"');
        for (int i = 0; i < value.length(); i++)
        {
            char ch = value.charAt(i);
            switch (ch)
            {
            case '"':
                result.append("\\\""); //$NON-NLS-1$
                break;
            case '\\':
                result.append("\\\\"); //$NON-NLS-1$
                break;
            case '\n':
                result.append("\\n"); //$NON-NLS-1$
                break;
            case '\r':
                result.append("\\r"); //$NON-NLS-1$
                break;
            case '\t':
                result.append("\\t"); //$NON-NLS-1$
                break;
            default:
                if (ch < 0x20)
                    result.append(String.format("\\u%04x", Integer.valueOf(ch))); //$NON-NLS-1$
                else
                    result.append(ch);
                break;
            }
        }
        result.append('"');
        return result.toString();
    }

    static String snapshotsArray(List<VariableSnapshot> snapshots)
    {
        StringBuilder result = new StringBuilder();
        result.append("[\n"); //$NON-NLS-1$
        for (int i = 0; i < snapshots.size(); i++)
        {
            if (i > 0)
                result.append(",\n"); //$NON-NLS-1$
            result.append(snapshots.get(i).json);
        }
        result.append("\n]\n"); //$NON-NLS-1$
        return result.toString();
    }

    static List<VariableSnapshot> parseSnapshots(String json)
    {
        List<VariableSnapshot> result = new ArrayList<>();
        if (json == null || json.isBlank())
            return result;

        for (String objectJson : splitTopLevelObjects(json))
        {
            String location = readLocation(objectJson);
            result.add(new VariableSnapshot(readString(objectJson, "id"), readString(objectJson, "timestamp"), //$NON-NLS-1$ //$NON-NLS-2$
                location, readExpressionCount(objectJson), objectJson));
        }
        return result;
    }

    private static List<String> splitTopLevelObjects(String json)
    {
        List<String> result = new ArrayList<>();
        boolean inString = false;
        boolean escaped = false;
        int depth = 0;
        int start = -1;
        for (int i = 0; i < json.length(); i++)
        {
            char ch = json.charAt(i);
            if (escaped)
            {
                escaped = false;
                continue;
            }
            if (ch == '\\' && inString)
            {
                escaped = true;
                continue;
            }
            if (ch == '"')
            {
                inString = !inString;
                continue;
            }
            if (inString)
                continue;

            if (ch == '{')
            {
                if (depth == 0)
                    start = i;
                depth++;
            }
            else if (ch == '}')
            {
                depth--;
                if (depth == 0 && start >= 0)
                {
                    result.add(json.substring(start, i + 1));
                    start = -1;
                }
            }
        }
        return result;
    }

    private static String readLocation(String json)
    {
        String locationObject = readBalanced(json, "location", '{', '}'); //$NON-NLS-1$
        String summary = readString(locationObject, "summary"); //$NON-NLS-1$
        return summary == null || summary.isBlank() ? "Неизвестное место остановки" : summary; //$NON-NLS-1$
    }

    private static int readExpressionCount(String json)
    {
        String expressions = readBalanced(json, "expressions", '[', ']'); //$NON-NLS-1$
        return expressions == null || expressions.isBlank() ? 0 : splitTopLevelObjects(expressions).size();
    }

    private static String readString(String json, String name)
    {
        int valueStart = findValueStart(json, name);
        if (valueStart < 0 || valueStart >= json.length() || json.charAt(valueStart) != '"')
            return ""; //$NON-NLS-1$

        StringBuilder result = new StringBuilder();
        boolean escaped = false;
        for (int i = valueStart + 1; i < json.length(); i++)
        {
            char ch = json.charAt(i);
            if (escaped)
            {
                result.append(ch == 'n' ? '\n' : ch == 'r' ? '\r' : ch == 't' ? '\t' : ch);
                escaped = false;
            }
            else if (ch == '\\')
            {
                escaped = true;
            }
            else if (ch == '"')
            {
                return result.toString();
            }
            else
            {
                result.append(ch);
            }
        }
        return result.toString();
    }

    private static String readBalanced(String json, String name, char open, char close)
    {
        int valueStart = findValueStart(json, name);
        if (valueStart < 0 || valueStart >= json.length() || json.charAt(valueStart) != open)
            return ""; //$NON-NLS-1$

        boolean inString = false;
        boolean escaped = false;
        int depth = 0;
        for (int i = valueStart; i < json.length(); i++)
        {
            char ch = json.charAt(i);
            if (escaped)
            {
                escaped = false;
                continue;
            }
            if (ch == '\\' && inString)
            {
                escaped = true;
                continue;
            }
            if (ch == '"')
            {
                inString = !inString;
                continue;
            }
            if (inString)
                continue;

            if (ch == open)
                depth++;
            else if (ch == close)
            {
                depth--;
                if (depth == 0)
                    return json.substring(valueStart, i + 1);
            }
        }
        return ""; //$NON-NLS-1$
    }

    private static int findValueStart(String json, String name)
    {
        if (json == null)
            return -1;

        String key = '"' + name + '"';
        int keyIndex = json.indexOf(key);
        if (keyIndex < 0)
            return -1;
        int colon = json.indexOf(':', keyIndex + key.length());
        if (colon < 0)
            return -1;
        int valueStart = colon + 1;
        while (valueStart < json.length() && Character.isWhitespace(json.charAt(valueStart)))
            valueStart++;
        return valueStart;
    }
}
