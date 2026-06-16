package ru.xelgo.edt.contextlinks.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.core.resources.IFile;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.resource.Resource;

/**
 * Keeps original BSL text that produced a normalized AST view.
 */
final class DirtyPreprocessorSourceCache
{
    private static final int MAX_CACHE_ENTRIES = Integer.getInteger(
        ContextLinks.PLUGIN_ID + ".dirtyPreprocessor.sourceCacheEntries", 256); //$NON-NLS-1$
    private static final ConcurrentHashMap<String, String> sources = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, String> normalizedSources = new ConcurrentHashMap<>();

    private DirtyPreprocessorSourceCache()
    {
        // Utility class.
    }

    static void remember(String sourceName, String source)
    {
        if (sourceName == null || source == null)
            return;

        put(sourceName, source);
        if (sourceName.startsWith("/")) //$NON-NLS-1$
            put("platform:/resource" + sourceName, source); //$NON-NLS-1$
        else if (sourceName.startsWith("platform:/resource/")) //$NON-NLS-1$
            put(sourceName.substring("platform:/resource".length()), source); //$NON-NLS-1$
    }

    static void remember(Resource resource, String source)
    {
        if (resource == null || resource.getURI() == null || source == null)
            return;

        URI uri = resource.getURI();
        remember(uri.toString(), source);
        if (uri.isPlatformResource())
            remember(uri.toPlatformString(true), source);
        if (uri.isFile())
            remember(uri.toFileString(), source);
    }

    static void remember(IFile file, String source)
    {
        if (file == null || source == null)
            return;

        remember(file.getFullPath().toString(), source);
        if (file.getLocation() != null)
            remember(file.getLocation().toOSString(), source);
    }

    static void rememberNormalized(String normalizedSource, String source)
    {
        if (normalizedSource == null || source == null)
            return;

        if (normalizedSources.size() > MAX_CACHE_ENTRIES)
            normalizedSources.clear();
        normalizedSources.put(keyOf(normalizedSource), source);
    }

    static String get(Resource resource)
    {
        if (resource == null || resource.getURI() == null)
            return null;

        URI uri = resource.getURI();
        String source = sources.get(uri.toString());
        if (source != null)
            return source;

        if (uri.isPlatformResource())
        {
            source = sources.get(uri.toPlatformString(true));
            if (source != null)
                return source;
        }

        if (uri.isFile())
            return sources.get(uri.toFileString());

        return null;
    }

    static String getByNormalized(String normalizedSource)
    {
        if (normalizedSource == null)
            return null;

        return normalizedSources.get(keyOf(normalizedSource));
    }

    private static void put(String key, String source)
    {
        if (key == null || key.isBlank())
            return;

        if (sources.size() > MAX_CACHE_ENTRIES)
            sources.clear();
        sources.put(key, source);
    }

    private static String keyOf(String text)
    {
        try
        {
            MessageDigest digest = MessageDigest.getInstance("SHA-256"); //$NON-NLS-1$
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(bytes.length * 2 + 16);
            builder.append(text.length()).append(':');
            for (byte value : bytes)
            {
                builder.append(Character.forDigit((value >>> 4) & 0x0F, 16));
                builder.append(Character.forDigit(value & 0x0F, 16));
            }
            return builder.toString();
        }
        catch (NoSuchAlgorithmException e)
        {
            return text.length() + ":" + text.hashCode(); //$NON-NLS-1$
        }
    }
}
