package ru.xelgo.edt.contextlinks.core;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Runtime patches for BSL documentation pages created by EDT internals.
 */
public final class ContextLinksBslDocumentationPatches
{
    private static volatile long invocationParameterInfoRevision;
    private static volatile long lastAvailableInvocationParameterInfoRevision;

    private ContextLinksBslDocumentationPatches()
    {
        // Utility class.
    }

    public static void enrichMethodParameterDescriptions(Object methodPage, List<String> commentLines)
    {
        if (methodPage == null || commentLines == null || commentLines.isEmpty())
            return;

        try
        {
            ParsedTemplateComment parsedComment = parseTemplateComment(commentLines);
            if (parsedComment.isEmpty())
                return;

            applyMethodDescriptionIfBlank(methodPage, parsedComment.methodDescription);

            Object hoverPagesObject = methodPage.getClass().getMethod("getHoverPages").invoke(methodPage); //$NON-NLS-1$
            if (!(hoverPagesObject instanceof List<?> hoverPages))
                return;

            for (Object hoverPage : hoverPages)
                enrichParamSet(hoverPage, parsedComment.parameterDescriptions);
        }
        catch (ReflectiveOperationException | RuntimeException e)
        {
            // Ignore unsupported EDT internals and leave the original documentation page unchanged.
        }
    }

    public static void recordInvocationParameterInfo(Object parameterInfo)
    {
        if (parameterInfo == null)
        {
            recordInvocationParameterInfo(false);
            return;
        }

        try
        {
            int pages = readListSizeField(parameterInfo, "pages"); //$NON-NLS-1$
            recordInvocationParameterInfo(pages > 0);
        }
        catch (RuntimeException e)
        {
            recordInvocationParameterInfo(false);
        }
    }

    public static int adjustInvocationParameterLookupOffset(int currentOffset)
    {
        return currentOffset > 0 ? currentOffset - 1 : currentOffset;
    }

    public static long getInvocationParameterInfoRevision()
    {
        return invocationParameterInfoRevision;
    }

    public static boolean hasAvailableInvocationParameterInfoSince(long revision)
    {
        return lastAvailableInvocationParameterInfoRevision > revision;
    }

    private static void recordInvocationParameterInfo(boolean available)
    {
        long revision = invocationParameterInfoRevision + 1;
        invocationParameterInfoRevision = revision;
        if (available)
            lastAvailableInvocationParameterInfoRevision = revision;
    }

    private static int readListSizeField(Object target, String fieldName)
    {
        Object value = readField(target, fieldName);
        return value instanceof List<?> list ? list.size() : -1;
    }

    private static Object readField(Object target, String fieldName)
    {
        try
        {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.get(target);
        }
        catch (ReflectiveOperationException | RuntimeException e)
        {
            return null;
        }
    }

    private static ParsedTemplateComment parseTemplateComment(List<String> commentLines)
    {
        StringBuilder methodDescription = new StringBuilder();
        Map<String, List<String>> parameterLines = new LinkedHashMap<>();
        boolean inParametersSection = false;
        String currentParameterName = null;

        for (String rawLine : commentLines)
        {
            String line = stripCommentMarker(rawLine);
            String trimmedLine = line.trim();

            if (isParametersHeading(trimmedLine))
            {
                inParametersSection = true;
                currentParameterName = null;
                continue;
            }

            if (inParametersSection && isSectionHeading(trimmedLine))
            {
                break;
            }

            if (!inParametersSection)
            {
                if (!trimmedLine.isEmpty())
                    appendHtmlLine(methodDescription, trimmedLine);
                continue;
            }

            if (trimmedLine.isEmpty())
                continue;

            String parameterName = parseParameterName(trimmedLine);
            if (parameterName != null)
            {
                currentParameterName = parameterName;
                addParameterDescription(parameterLines, currentParameterName,
                    extractParameterDescription(trimmedLine));
                continue;
            }

            if (currentParameterName != null)
            {
                addParameterDescription(parameterLines, currentParameterName,
                    extractContinuationDescription(trimmedLine));
            }
        }

        Map<String, String> parameterDescriptions = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : parameterLines.entrySet())
            parameterDescriptions.put(normalizeParameterName(entry.getKey()), toHtml(entry.getValue()));

        return new ParsedTemplateComment(methodDescription.toString(), parameterDescriptions);
    }

    private static void enrichParamSet(Object paramSet, Map<String, String> fallbackDescriptions)
        throws ReflectiveOperationException
    {
        if (paramSet == null || fallbackDescriptions.isEmpty())
            return;

        Method getParameter = paramSet.getClass().getMethod("getParameter", int.class); //$NON-NLS-1$
        Method setParams = paramSet.getClass().getMethod("setParams", List.class); //$NON-NLS-1$
        List<Object> parameters = new ArrayList<>();
        boolean changed = false;

        for (int index = 0; ; index++)
        {
            Object parameter = getParameter(getParameter, paramSet, index);
            if (parameter == null)
                break;

            String parameterName = asString(parameter.getClass().getMethod("getName").invoke(parameter)); //$NON-NLS-1$
            String currentDescription =
                asString(parameter.getClass().getMethod("getDefaultDescription").invoke(parameter)); //$NON-NLS-1$
            String fallbackDescription = fallbackDescriptions.get(normalizeParameterName(parameterName));
            if (!isMissingDescription(fallbackDescription))
            {
                changed |= applyValueDescriptionIfMissing(parameter, fallbackDescription);
                if (isMissingDescription(currentDescription))
                {
                    parameter = createParameterWithDescription(parameter, fallbackDescription);
                    changed = true;
                }
            }
            parameters.add(parameter);
        }

        if (changed)
            setParams.invoke(paramSet, parameters);
    }

    private static Object getParameter(Method getParameter, Object paramSet, int index)
        throws ReflectiveOperationException
    {
        try
        {
            return getParameter.invoke(paramSet, index);
        }
        catch (InvocationTargetException e)
        {
            Throwable cause = e.getCause();
            if (cause instanceof IndexOutOfBoundsException || cause instanceof IllegalArgumentException)
                return null;
            throw e;
        }
    }

    private static Object createParameterWithDescription(Object originalParameter, String description)
        throws ReflectiveOperationException
    {
        Class<?> parameterClass = originalParameter.getClass();
        String name = asString(parameterClass.getMethod("getName").invoke(originalParameter)); //$NON-NLS-1$
        Object value = parameterClass.getMethod("getValue").invoke(originalParameter); //$NON-NLS-1$
        Object optional = parameterClass.getMethod("getOptional").invoke(originalParameter); //$NON-NLS-1$
        Object passing = parameterClass.getMethod("getPassing").invoke(originalParameter); //$NON-NLS-1$
        Constructor<?> constructor = findParameterConstructor(parameterClass);
        return constructor.newInstance(name, value, description, optional, passing);
    }

    private static boolean applyValueDescriptionIfMissing(Object parameter, String description)
    {
        if (parameter == null || isMissingDescription(description))
            return false;

        try
        {
            Object value = parameter.getClass().getMethod("getValue").invoke(parameter); //$NON-NLS-1$
            if (value == null)
                return false;

            Field descriptionField = findField(value.getClass(), "description"); //$NON-NLS-1$
            if (descriptionField.getType() != String.class)
                return false;

            descriptionField.setAccessible(true);
            String currentDescription = asString(descriptionField.get(value));
            if (!isMissingDescription(currentDescription))
                return false;

            descriptionField.set(value, description);
            return true;
        }
        catch (ReflectiveOperationException | RuntimeException e)
        {
            return false;
        }
    }

    private static Field findField(Class<?> type, String name)
        throws NoSuchFieldException
    {
        Class<?> currentType = type;
        while (currentType != null)
        {
            try
            {
                return currentType.getDeclaredField(name);
            }
            catch (NoSuchFieldException e)
            {
                currentType = currentType.getSuperclass();
            }
        }
        throw new NoSuchFieldException(type.getName() + "." + name); //$NON-NLS-1$
    }

    private static Constructor<?> findParameterConstructor(Class<?> parameterClass)
        throws NoSuchMethodException
    {
        for (Constructor<?> constructor : parameterClass.getConstructors())
        {
            Class<?>[] parameterTypes = constructor.getParameterTypes();
            if (parameterTypes.length == 5 && parameterTypes[0] == String.class && parameterTypes[2] == String.class
                && parameterTypes[3] == Boolean.class && parameterTypes[4] == Boolean.class)
            {
                return constructor;
            }
        }
        throw new NoSuchMethodException(parameterClass.getName());
    }

    private static void applyMethodDescriptionIfBlank(Object methodPage, String description)
    {
        if (isMissingDescription(description))
            return;

        try
        {
            Method getDescription = methodPage.getClass().getDeclaredMethod("getDescription"); //$NON-NLS-1$
            getDescription.setAccessible(true);
            String currentDescription = asString(getDescription.invoke(methodPage));
            if (isMissingDescription(currentDescription))
                methodPage.getClass().getMethod("setDescription", String.class).invoke(methodPage, description); //$NON-NLS-1$
        }
        catch (ReflectiveOperationException | RuntimeException e)
        {
        }
    }

    private static String stripCommentMarker(String rawLine)
    {
        String line = rawLine != null ? rawLine.strip() : ""; //$NON-NLS-1$
        if (line.startsWith("//")) //$NON-NLS-1$
            return line.substring(2).strip();
        return line;
    }

    private static boolean isParametersHeading(String line)
    {
        String lower = line.toLowerCase(Locale.ROOT);
        return lower.startsWith("\u043f\u0430\u0440\u0430\u043c\u0435\u0442\u0440\u044b:") //$NON-NLS-1$
            || lower.startsWith("parameters:"); //$NON-NLS-1$
    }

    private static boolean isSectionHeading(String line)
    {
        String lower = line.toLowerCase(Locale.ROOT);
        return lower.startsWith("\u0432\u043e\u0437\u0432\u0440\u0430\u0449\u0430\u0435\u043c\u043e\u0435") //$NON-NLS-1$
            || lower.startsWith("\u0432\u043e\u0437\u0432\u0440\u0430\u0449\u0430\u0435\u0442") //$NON-NLS-1$
            || lower.startsWith("\u043f\u0440\u0438\u043c\u0435\u0440") //$NON-NLS-1$
            || lower.startsWith("\u0441\u043c. \u0442\u0430\u043a\u0436\u0435") //$NON-NLS-1$
            || lower.startsWith("returns:") //$NON-NLS-1$
            || lower.startsWith("return value:") //$NON-NLS-1$
            || lower.startsWith("example:") //$NON-NLS-1$
            || lower.startsWith("examples:"); //$NON-NLS-1$
    }

    private static String parseParameterName(String line)
    {
        int separator = line.indexOf(" - "); //$NON-NLS-1$
        if (separator <= 0)
            return null;

        String name = line.substring(0, separator).trim();
        if (!isIdentifierName(name))
            return null;

        return name;
    }

    private static boolean isIdentifierName(String value)
    {
        if (value.isEmpty())
            return false;

        for (int index = 0; index < value.length(); index++)
        {
            char character = value.charAt(index);
            if (!Character.isLetterOrDigit(character) && character != '_')
                return false;
        }
        return true;
    }

    private static String extractParameterDescription(String line)
    {
        int firstSeparator = line.indexOf(" - "); //$NON-NLS-1$
        if (firstSeparator < 0)
            return ""; //$NON-NLS-1$

        int secondSeparator = line.indexOf(" - ", firstSeparator + 3); //$NON-NLS-1$
        if (secondSeparator >= 0)
            return line.substring(secondSeparator + 3).trim();

        return line.substring(firstSeparator + 3).trim();
    }

    private static String extractContinuationDescription(String line)
    {
        String trimmedLine = line.trim();
        if (!trimmedLine.startsWith("-")) //$NON-NLS-1$
            return trimmedLine;

        String continuation = trimmedLine.substring(1).trim();
        int separator = continuation.indexOf(" - "); //$NON-NLS-1$
        if (separator >= 0)
            return continuation.substring(separator + 3).trim();
        return continuation;
    }

    private static void addParameterDescription(Map<String, List<String>> parameterLines, String name,
        String description)
    {
        if (description == null || description.isBlank())
            return;

        parameterLines.computeIfAbsent(name, key -> new ArrayList<>()).add(description);
    }

    private static void appendHtmlLine(StringBuilder html, String line)
    {
        if (html.length() > 0)
            html.append("<br>"); //$NON-NLS-1$
        html.append(escapeHtml(line));
    }

    private static String toHtml(List<String> lines)
    {
        StringBuilder html = new StringBuilder();
        for (String line : lines)
            appendHtmlLine(html, line);
        return html.toString();
    }

    private static String escapeHtml(String value)
    {
        StringBuilder result = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++)
        {
            char character = value.charAt(index);
            switch (character)
            {
            case '&':
                result.append("&amp;"); //$NON-NLS-1$
                break;
            case '<':
                result.append("&lt;"); //$NON-NLS-1$
                break;
            case '>':
                result.append("&gt;"); //$NON-NLS-1$
                break;
            default:
                result.append(character);
                break;
            }
        }
        return result.toString();
    }

    private static String normalizeParameterName(String name)
    {
        return name != null ? name.trim().toLowerCase(Locale.ROOT) : ""; //$NON-NLS-1$
    }

    private static String asString(Object value)
    {
        return value instanceof String ? (String)value : null;
    }

    private static boolean isMissingDescription(String value)
    {
        return value == null || value.isBlank() || "null".equalsIgnoreCase(value.trim()); //$NON-NLS-1$
    }

    private static final class ParsedTemplateComment
    {
        private final String methodDescription;
        private final Map<String, String> parameterDescriptions;

        ParsedTemplateComment(String methodDescription, Map<String, String> parameterDescriptions)
        {
            this.methodDescription = methodDescription;
            this.parameterDescriptions = parameterDescriptions;
        }

        boolean isEmpty()
        {
            return isMissingDescription(methodDescription) && parameterDescriptions.isEmpty();
        }
    }
}
