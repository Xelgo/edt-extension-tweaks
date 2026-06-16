package ru.xelgo.edt.contextlinks.core;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.xtext.resource.XtextResource;

import com._1c.g5.v8.dt.core.resource.extension.IBslResourceExtension;

/**
 * Feeds EDT BSL parser with an offset-preserving view of dirty configurator extension blocks.
 */
final class ContextLinksBslResourceExtension
    implements IBslResourceExtension
{
    @Override
    public InputStream replaceStreamIfNecessary(Resource resource, InputStream inputStream, Map<?, ?> options)
    {
        return normalize("resource", inputStream, getCharset(resource, options), describe(resource), resource, null, //$NON-NLS-1$
            options);
    }

    @Override
    public InputStream replaceStreamIfNecessary(IFile file, InputStream inputStream)
    {
        return normalize("file", inputStream, getCharset(file), describe(file), null, file, null); //$NON-NLS-1$
    }

    private static InputStream normalize(String stage, InputStream inputStream, Charset charset, String sourceName,
        Resource resource, IFile file, Map<?, ?> options)
    {
        if (!ContextLinksPreferences.isDirtyPreprocessorEnabled())
            return inputStream;

        long id = DirtyPreprocessorDiagnostics.nextId();
        long started = System.nanoTime();
        DirtyPreprocessorDiagnostics.logStart(id, "resource." + stage, //$NON-NLS-1$
            "source=" + sourceName + " charset=" + charset //$NON-NLS-1$ //$NON-NLS-2$
                + " stream=" + (inputStream != null ? inputStream.getClass().getName() : "NULL") //$NON-NLS-1$ //$NON-NLS-2$
                + " options=" + describeOptions(options)); //$NON-NLS-1$

        byte[] bytes;
        try
        {
            bytes = inputStream.readAllBytes();
        }
        catch (IOException e)
        {
            ContextLinks.logError("Failed to read BSL stream for dirty preprocessor normalization: " + sourceName, e); //$NON-NLS-1$
            return inputStream;
        }

        String source = new String(bytes, charset);
        DirtyPreprocessorNormalizer.NormalizationResult result = DirtyPreprocessorNormalizer.normalize(source);
        if (!result.isChanged())
        {
            DirtyPreprocessorDiagnostics.logEnd(id, "resource." + stage, started, //$NON-NLS-1$
                details(sourceName, bytes.length, source, result));
            return new ByteArrayInputStream(bytes);
        }

        DirtyPreprocessorSourceCache.remember(sourceName, source);
        DirtyPreprocessorSourceCache.remember(resource, source);
        DirtyPreprocessorSourceCache.remember(file, source);
        DirtyPreprocessorSourceCache.rememberNormalized(result.getText(), source);
        DirtyPreprocessorLogger.logNormalized(stage, sourceName, source, result);
        DirtyPreprocessorDiagnostics.logEnd(id, "resource." + stage, started, //$NON-NLS-1$
            details(sourceName, bytes.length, source, result));

        return new ByteArrayInputStream(result.getText().getBytes(charset));
    }

    private static String details(String sourceName, int byteLength, String source,
        DirtyPreprocessorNormalizer.NormalizationResult result)
    {
        return "source=" + sourceName //$NON-NLS-1$
            + " bytes=" + byteLength //$NON-NLS-1$
            + " length=" + (source != null ? source.length() : -1) //$NON-NLS-1$
            + " changed=" + (result != null && result.isChanged()) //$NON-NLS-1$
            + " insertMarkers=" + (result != null ? result.getInsertMarkers() : -1) //$NON-NLS-1$
            + " deleteBlocks=" + (result != null ? result.getDeleteBlocks() : -1) //$NON-NLS-1$
            + " maskedChars=" + (result != null ? result.getMaskedCharacters() : -1); //$NON-NLS-1$
    }

    private static String describeOptions(Map<?, ?> options)
    {
        if (options == null || options.isEmpty())
            return "{}"; //$NON-NLS-1$

        StringBuilder builder = new StringBuilder("{"); //$NON-NLS-1$
        int count = 0;
        for (Map.Entry<?, ?> entry : options.entrySet())
        {
            if (count > 0)
                builder.append(',');
            builder.append(entry.getKey()).append('=');
            Object value = entry.getValue();
            builder.append(value != null ? value.getClass().getName() + ':' + value : "NULL"); //$NON-NLS-1$
            count++;
            if (count >= 8)
            {
                builder.append(",..."); //$NON-NLS-1$
                break;
            }
        }
        builder.append('}');
        return builder.toString();
    }

    private static Charset getCharset(Resource resource, Map<?, ?> options)
    {
        Object encoding = options != null ? options.get(XtextResource.OPTION_ENCODING) : null;
        Charset charset = toCharset(encoding);
        if (charset != null)
            return charset;

        if (resource instanceof XtextResource)
        {
            charset = toCharset(((XtextResource)resource).getEncoding());
            if (charset != null)
                return charset;
        }

        return StandardCharsets.UTF_8;
    }

    private static Charset getCharset(IFile file)
    {
        if (file == null)
            return StandardCharsets.UTF_8;

        try
        {
            Charset charset = toCharset(file.getCharset(false));
            return charset != null ? charset : StandardCharsets.UTF_8;
        }
        catch (CoreException e)
        {
            ContextLinks.logDebug("EDT Extension Tweaks dirty preprocessor charset fallback for file=" //$NON-NLS-1$
                + describe(file) + " reason=" + e.getMessage()); //$NON-NLS-1$
            return StandardCharsets.UTF_8;
        }
    }

    private static Charset toCharset(Object encoding)
    {
        if (!(encoding instanceof String) || ((String)encoding).isBlank())
            return null;

        try
        {
            return Charset.forName((String)encoding);
        }
        catch (IllegalCharsetNameException | UnsupportedCharsetException e)
        {
            return null;
        }
    }

    private static String describe(Resource resource)
    {
        return resource != null && resource.getURI() != null ? resource.getURI().toString() : "NULL_RESOURCE"; //$NON-NLS-1$
    }

    private static String describe(IFile file)
    {
        return file != null ? file.getFullPath().toString() : "NULL_FILE"; //$NON-NLS-1$
    }
}
