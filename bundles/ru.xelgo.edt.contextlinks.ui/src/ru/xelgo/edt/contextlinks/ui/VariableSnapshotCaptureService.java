package ru.xelgo.edt.contextlinks.ui;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.xml.parsers.DocumentBuilderFactory;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.core.runtime.Path;
import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.model.IErrorReportingExpression;
import org.eclipse.debug.core.model.IExpression;
import org.eclipse.debug.core.model.IIndexedValue;
import org.eclipse.debug.core.model.IStackFrame;
import org.eclipse.debug.core.model.IThread;
import org.eclipse.debug.core.model.IValue;
import org.eclipse.debug.core.model.IVariable;
import org.eclipse.debug.core.model.IWatchExpression;
import org.eclipse.debug.ui.DebugUITools;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import com._1c.g5.v8.dt.debug.core.model.IBslStackFrame;
import com._1c.g5.v8.dt.debug.core.model.IBslVariable;
import com._1c.g5.v8.dt.debug.core.model.values.BslValueType;
import com._1c.g5.v8.dt.debug.core.model.values.IBslIndexedValue;
import com._1c.g5.v8.dt.debug.core.model.values.IBslPrimitiveValue;
import com._1c.g5.v8.dt.debug.core.model.values.IBslValue;

import ru.xelgo.edt.contextlinks.core.ContextLinks;
import ru.xelgo.edt.contextlinks.core.ContextLinksPreferences;

/**
 * Captures currently evaluated BSL expressions into JSON.
 */
final class VariableSnapshotCaptureService
{
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"); //$NON-NLS-1$
    private static final long BSL_CHILDREN_EVALUATION_TIMEOUT_MS = 3000L;
    private static final long BSL_CHILDREN_EVALUATION_POLL_MS = 50L;

    VariableSnapshot capture()
    {
        int maxDepth = ContextLinksPreferences.getVariableSnapshotsMaxDepth();
        int collectionLimit = ContextLinksPreferences.getVariableSnapshotsCollectionLimit();
        IExpression[] expressions = DebugPlugin.getDefault().getExpressionManager().getExpressions();
        IStackFrame frame = currentStackFrame();
        LocationInfo location = currentLocation(frame);
        String timestamp = TIMESTAMP_FORMAT.format(LocalDateTime.now());
        String id = UUID.randomUUID().toString();

        StringBuilder json = new StringBuilder(8192);
        json.append("{\n"); //$NON-NLS-1$
        appendStringField(json, 1, "id", id, true); //$NON-NLS-1$
        appendStringField(json, 1, "timestamp", timestamp, true); //$NON-NLS-1$
        appendStringField(json, 1, "kind", "variableSnapshot", true); //$NON-NLS-1$ //$NON-NLS-2$
        json.append(indent(1)).append("\"location\": "); //$NON-NLS-1$
        appendLocation(json, location);
        json.append(",\n"); //$NON-NLS-1$
        json.append(indent(1)).append("\"settings\": {\n"); //$NON-NLS-1$
        json.append(indent(2)).append("\"maxDepth\": ").append(maxDepth).append(",\n"); //$NON-NLS-1$ //$NON-NLS-2$
        json.append(indent(2)).append("\"collectionLimit\": ").append(collectionLimit).append('\n'); //$NON-NLS-1$
        json.append(indent(1)).append("},\n"); //$NON-NLS-1$
        json.append(indent(1)).append("\"expressions\": [\n"); //$NON-NLS-1$
        for (int i = 0; i < expressions.length; i++)
        {
            if (i > 0)
                json.append(",\n"); //$NON-NLS-1$
            appendExpression(json, expressions[i], maxDepth, collectionLimit, frame, location, 2);
        }
        json.append('\n').append(indent(1)).append("]\n"); //$NON-NLS-1$
        json.append('}');

        return new VariableSnapshot(id, timestamp, location.summary, expressions.length, json.toString());
    }

    private void appendExpression(StringBuilder json, IExpression expression, int maxDepth, int collectionLimit,
        IStackFrame frame, LocationInfo location, int indent)
    {
        json.append(indent(indent)).append("{\n"); //$NON-NLS-1$
        appendStringField(json, indent + 1, "name", expression.getExpressionText(), true); //$NON-NLS-1$
        appendStringField(json, indent + 1, "expression", expression.getExpressionText(), true); //$NON-NLS-1$
        if (expression instanceof IWatchExpression)
        {
            IWatchExpression watchExpression = (IWatchExpression)expression;
            json.append(indent(indent + 1)).append("\"enabled\": ").append(watchExpression.isEnabled()).append(",\n"); //$NON-NLS-1$ //$NON-NLS-2$
            json.append(indent(indent + 1)).append("\"pending\": ").append(watchExpression.isPending()).append(",\n"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (expression instanceof IErrorReportingExpression && ((IErrorReportingExpression)expression).hasErrors())
        {
            appendErrors(json, (IErrorReportingExpression)expression, indent + 1);
            json.append(",\n"); //$NON-NLS-1$
        }

        appendValueFields(json, expression.getValue(), maxDepth, collectionLimit, expression.getExpressionText(), frame,
            location, indent + 1, 0);
        json.append('\n').append(indent(indent)).append('}');
    }

    private void appendValueFields(StringBuilder json, IValue value, int maxDepth, int collectionLimit,
        String expressionPath, IStackFrame frame, LocationInfo location, int indent, int depth)
    {
        if (value == null)
        {
            appendStringField(json, indent, "type", "unknown", true); //$NON-NLS-1$ //$NON-NLS-2$
            appendStringField(json, indent, "value", "Значение не вычислено", true); //$NON-NLS-1$ //$NON-NLS-2$
            appendStringField(json, indent, "presentation", "Значение не вычислено", false); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }

        String presentation = safeValueString(value);
        ChildrenInfo childrenInfo = readChildren(value, collectionLimit);
        SyntheticChild[] syntheticChildren =
            readSyntheticFormChildren(value, expressionPath, frame, location, collectionLimit);
        boolean hasVariables = childrenInfo.variables.length > 0 || syntheticChildren.length > 0
            || childrenInfo.truncated || safeHasVariables(value);
        boolean reference = isNonExpandableReference(value);
        boolean depthTruncated = hasVariables && depth >= maxDepth;
        boolean canExpand = hasVariables && !reference && !depthTruncated;

        appendStringField(json, indent, "type", safeType(value), true); //$NON-NLS-1$
        json.append(indent(indent)).append("\"value\": "); //$NON-NLS-1$
        appendJsonValue(json, value, presentation, expressionPath, frame);
        json.append(",\n"); //$NON-NLS-1$
        appendStringField(json, indent, "presentation", presentation, true); //$NON-NLS-1$
        json.append(indent(indent)).append("\"childrenTruncated\": ") //$NON-NLS-1$
            .append(reference || depthTruncated || childrenInfo.truncated);

        if (canExpand)
        {
            json.append(",\n"); //$NON-NLS-1$
            appendChildren(json, childrenInfo.variables, syntheticChildren, maxDepth, collectionLimit, frame, location,
                expressionPath, indent, depth);
        }
    }

    private void appendChildren(StringBuilder json, IVariable[] variables, SyntheticChild[] syntheticChildren,
        int maxDepth, int collectionLimit, IStackFrame frame, LocationInfo location, String parentExpressionPath,
        int indent, int depth)
    {
        json.append(indent(indent)).append("\"children\": [\n"); //$NON-NLS-1$
        for (int i = 0; i < variables.length; i++)
        {
            if (i > 0)
                json.append(",\n"); //$NON-NLS-1$
            appendVariable(json, variables[i], maxDepth, collectionLimit, frame, location, parentExpressionPath,
                indent + 1, depth + 1);
        }
        for (int i = 0; i < syntheticChildren.length; i++)
        {
            if (variables.length > 0 || i > 0)
                json.append(",\n"); //$NON-NLS-1$
            appendSyntheticChild(json, syntheticChildren[i], maxDepth, collectionLimit, frame, location, indent + 1,
                depth + 1);
        }
        json.append('\n').append(indent(indent)).append(']');
    }

    private void appendVariable(StringBuilder json, IVariable variable, int maxDepth, int collectionLimit,
        IStackFrame frame, LocationInfo location, String parentExpressionPath, int indent, int depth)
    {
        String variableName = safeVariableName(variable);
        String expressionPath = safeVariableExpression(variable, parentExpressionPath, variableName);
        json.append(indent(indent)).append("{\n"); //$NON-NLS-1$
        appendStringField(json, indent + 1, "name", variableName, true); //$NON-NLS-1$
        if (expressionPath != null)
            appendStringField(json, indent + 1, "expression", expressionPath, true); //$NON-NLS-1$
        appendValueFields(json, safeVariableValue(variable), maxDepth, collectionLimit, expressionPath, frame, location,
            indent + 1, depth);
        json.append('\n').append(indent(indent)).append('}');
    }

    private void appendSyntheticChild(StringBuilder json, SyntheticChild child, int maxDepth, int collectionLimit,
        IStackFrame frame, LocationInfo location, int indent, int depth)
    {
        json.append(indent(indent)).append("{\n"); //$NON-NLS-1$
        appendStringField(json, indent + 1, "name", child.name, true); //$NON-NLS-1$
        appendStringField(json, indent + 1, "expression", child.expression, true); //$NON-NLS-1$
        appendValueFields(json, child.value, maxDepth, collectionLimit, child.expression, frame, location, indent + 1,
            depth);
        json.append('\n').append(indent(indent)).append('}');
    }

    private void appendErrors(StringBuilder json, IErrorReportingExpression expression, int indent)
    {
        String[] errors = expression.getErrorMessages();
        json.append(indent(indent)).append("\"errors\": ["); //$NON-NLS-1$
        for (int i = 0; i < errors.length; i++)
        {
            if (i > 0)
                json.append(", "); //$NON-NLS-1$
            json.append(VariableSnapshotJson.quote(errors[i]));
        }
        json.append(']');
    }

    private void appendJsonValue(StringBuilder json, IValue value, String presentation, String expressionPath,
        IStackFrame frame)
    {
        String referenceUuid = readReferenceUuid(value, expressionPath, frame);
        if (referenceUuid != null)
        {
            json.append(VariableSnapshotJson.quote(referenceUuid));
            return;
        }

        if (value instanceof IBslPrimitiveValue)
        {
            Object primitive = ((IBslPrimitiveValue)value).getPrimitiveValue();
            if (primitive instanceof Number || primitive instanceof Boolean)
            {
                json.append(primitive);
                return;
            }
        }
        json.append(VariableSnapshotJson.quote(presentation));
    }

    private String readReferenceUuid(IValue value, String expressionPath, IStackFrame frame)
    {
        if (!isDataReference(value) || expressionPath == null || frame == null)
            return null;

        IValue uuidValue = evaluateExpression("Строка(" + expressionPath + ".УникальныйИдентификатор())", frame); //$NON-NLS-1$ //$NON-NLS-2$
        if (uuidValue == null)
            return null;

        String uuid = safeValueString(uuidValue);
        if (uuid == null || uuid.isBlank())
            return null;

        return trimQuotes(uuid);
    }

    private ChildrenInfo readChildren(IValue value, int collectionLimit)
    {
        try
        {
            if (value instanceof IIndexedValue)
            {
                return readIndexedChildren((IIndexedValue)value, collectionLimit);
            }
            IVariable[] variables = value.getVariables();
            variables = waitForLazyBslChildren(value, variables);
            if (variables.length <= collectionLimit)
                return new ChildrenInfo(variables, false);
            IVariable[] limited = new IVariable[collectionLimit];
            System.arraycopy(variables, 0, limited, 0, collectionLimit);
            return new ChildrenInfo(limited, true);
        }
        catch (DebugException e)
        {
            ContextLinks.logError("Failed to read variables for variable snapshot", e); //$NON-NLS-1$
            return new ChildrenInfo(new IVariable[0], false);
        }
    }

    private ChildrenInfo readIndexedChildren(IIndexedValue indexedValue, int collectionLimit)
        throws DebugException
    {
        int size = Math.max(0, indexedValue.getSize());
        IVariable[] indexedVariables = indexedValue.getVariables(0, Math.min(size, collectionLimit));
        indexedVariables = waitForLazyBslChildren((IValue)indexedValue, indexedVariables);

        IVariable[] contextVariables = readIndexedContextVariables(indexedValue);
        contextVariables = waitForLazyBslContextChildren(indexedValue, contextVariables);

        return new ChildrenInfo(concat(contextVariables, indexedVariables), size > collectionLimit);
    }

    private IVariable[] readIndexedContextVariables(IIndexedValue indexedValue)
        throws DebugException
    {
        if (indexedValue instanceof IBslIndexedValue)
            return ((IBslIndexedValue)indexedValue).getContextVariables();

        return new IVariable[0];
    }

    private IVariable[] waitForLazyBslContextChildren(IIndexedValue indexedValue, IVariable[] initialVariables)
        throws DebugException
    {
        if (!(indexedValue instanceof IBslIndexedValue))
            return initialVariables;

        IBslIndexedValue bslValue = (IBslIndexedValue)indexedValue;
        if (initialVariables.length > 0 && bslValue.isEvaluated())
            return initialVariables;

        long startedAt = System.currentTimeMillis();
        while (bslValue.isPending() && System.currentTimeMillis() - startedAt < BSL_CHILDREN_EVALUATION_TIMEOUT_MS)
        {
            sleepQuietly();
        }

        return bslValue.getContextVariables();
    }

    private IVariable[] waitForLazyBslChildren(IValue value, IVariable[] initialVariables)
        throws DebugException
    {
        if (!(value instanceof IBslValue))
            return initialVariables;

        IBslValue bslValue = (IBslValue)value;
        if (!bslValue.hasVariables())
            return initialVariables;

        if (initialVariables.length > 0 && bslValue.isEvaluated())
            return initialVariables;

        long startedAt = System.currentTimeMillis();
        while (bslValue.isPending() && System.currentTimeMillis() - startedAt < BSL_CHILDREN_EVALUATION_TIMEOUT_MS)
        {
            sleepQuietly();
        }

        return value.getVariables();
    }

    private void sleepQuietly()
    {
        try
        {
            Thread.sleep(BSL_CHILDREN_EVALUATION_POLL_MS);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
    }

    private IVariable[] concat(IVariable[] first, IVariable[] second)
    {
        if (first.length == 0)
            return second;
        if (second.length == 0)
            return first;

        IVariable[] result = new IVariable[first.length + second.length];
        System.arraycopy(first, 0, result, 0, first.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    private SyntheticChild[] readSyntheticFormChildren(IValue value, String expressionPath, IStackFrame frame,
        LocationInfo location, int collectionLimit)
    {
        if (frame == null || location == null || expressionPath == null || !isClientApplicationForm(value))
            return new SyntheticChild[0];

        List<String> names = readFormAttributeNames(location.source, collectionLimit);
        if (names.isEmpty())
            return new SyntheticChild[0];

        List<SyntheticChild> children = new ArrayList<>();
        for (String name : names)
        {
            String childExpression = expressionPath + "." + name; //$NON-NLS-1$
            IValue childValue = evaluateExpression(childExpression, frame);
            if (childValue == null && "ЭтотОбъект".equals(expressionPath)) //$NON-NLS-1$
            {
                childExpression = name;
                childValue = evaluateExpression(childExpression, frame);
            }
            if (childValue != null)
                children.add(new SyntheticChild(name, childExpression, childValue));
        }

        return children.toArray(new SyntheticChild[0]);
    }

    private boolean isClientApplicationForm(IValue value)
    {
        return "ФормаКлиентскогоПриложения".equals(safeType(value)); //$NON-NLS-1$
    }

    private List<String> readFormAttributeNames(String source, int collectionLimit)
    {
        IFile formFile = findSiblingFormFile(source);
        if (formFile == null || !formFile.exists())
            return List.of();

        try
        {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); //$NON-NLS-1$
            Element root = factory.newDocumentBuilder().parse(formFile.getContents()).getDocumentElement();
            NodeList attributes = root.getElementsByTagName("attributes"); //$NON-NLS-1$
            List<String> names = new ArrayList<>();
            for (int i = 0; i < attributes.getLength() && names.size() < collectionLimit; i++)
            {
                Element attribute = (Element)attributes.item(i);
                if (hasChildText(attribute, "main", "true")) //$NON-NLS-1$ //$NON-NLS-2$
                    continue;

                String name = firstChildText(attribute, "name"); //$NON-NLS-1$
                if (name != null && !name.isBlank())
                    names.add(name);
            }
            return names;
        }
        catch (Exception e)
        {
            ContextLinks.logError("Failed to read form attributes for variable snapshot", e); //$NON-NLS-1$
            return List.of();
        }
    }

    private IFile findSiblingFormFile(String source)
    {
        if (source == null || !source.startsWith("platform:/resource/")) //$NON-NLS-1$
            return null;

        String resourcePath = source;
        int fragment = resourcePath.indexOf('#');
        if (fragment >= 0)
            resourcePath = resourcePath.substring(0, fragment);
        resourcePath = resourcePath.substring("platform:/resource".length()); //$NON-NLS-1$

        IResource resource = ResourcesPlugin.getWorkspace().getRoot().findMember(Path.fromPortableString(resourcePath));
        if (resource == null || resource.getParent() == null)
            return null;

        IResource form = resource.getParent().findMember("Form.form"); //$NON-NLS-1$
        return form instanceof IFile ? (IFile)form : null;
    }

    private boolean hasChildText(Element parent, String tagName, String expected)
    {
        String value = firstChildText(parent, tagName);
        return expected.equals(value);
    }

    private String firstChildText(Element parent, String tagName)
    {
        NodeList children = parent.getElementsByTagName(tagName);
        if (children.getLength() == 0)
            return null;
        return children.item(0).getTextContent();
    }

    private IValue evaluateExpression(String expressionText, IStackFrame frame)
    {
        IWatchExpression expression = DebugPlugin.getDefault().getExpressionManager().newWatchExpression(expressionText);
        expression.setExpressionContext(frame);
        expression.evaluate();

        long startedAt = System.currentTimeMillis();
        while (expression.isPending() && System.currentTimeMillis() - startedAt < BSL_CHILDREN_EVALUATION_TIMEOUT_MS)
            sleepQuietly();

        try
        {
            if (expression instanceof IErrorReportingExpression && ((IErrorReportingExpression)expression).hasErrors())
            {
                return null;
            }
            return expression.getValue();
        }
        finally
        {
            expression.dispose();
        }
    }

    private boolean safeHasVariables(IValue value)
    {
        try
        {
            return value.hasVariables();
        }
        catch (DebugException e)
        {
            return false;
        }
    }

    private IValue safeVariableValue(IVariable variable)
    {
        try
        {
            return variable.getValue();
        }
        catch (DebugException e)
        {
            ContextLinks.logError("Failed to read variable value for snapshot", e); //$NON-NLS-1$
            return null;
        }
    }

    private String safeVariableName(IVariable variable)
    {
        try
        {
            return variable.getName();
        }
        catch (DebugException e)
        {
            return "unknown"; //$NON-NLS-1$
        }
    }

    private String safeVariableExpression(IVariable variable, String parentExpressionPath, String variableName)
    {
        if (variable instanceof IBslVariable)
        {
            try
            {
                String expression = ((IBslVariable)variable).toWatchExpression();
                if (expression != null && !expression.isBlank())
                    return expression;
            }
            catch (Exception e)
            {
                // Fall back to the parent expression path below.
            }
        }

        if (parentExpressionPath == null || parentExpressionPath.isBlank() || variableName == null
            || variableName.isBlank())
            return null;

        if (variableName.startsWith("[") && variableName.endsWith("]")) //$NON-NLS-1$ //$NON-NLS-2$
            return parentExpressionPath + variableName;

        return parentExpressionPath + "." + variableName; //$NON-NLS-1$
    }

    private String safeType(IValue value)
    {
        try
        {
            if (value instanceof IBslValue)
                return ((IBslValue)value).getValueTypeName();
            return value.getReferenceTypeName();
        }
        catch (DebugException e)
        {
            return "unknown"; //$NON-NLS-1$
        }
    }

    private String safeValueString(IValue value)
    {
        try
        {
            if (value instanceof IBslValue)
                return ((IBslValue)value).getDetailString();
            return value.getValueString();
        }
        catch (DebugException e)
        {
            return "<error: " + e.getMessage() + ">"; //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private boolean isNonExpandableReference(IValue value)
    {
        if (!(value instanceof IBslValue) || ((IBslValue)value).getType() != BslValueType.REFERENCE)
            return false;

        return isDataReference(value) || isEnumReference(value);
    }

    private boolean isDataReference(IValue value)
    {
        if (!(value instanceof IBslValue) || ((IBslValue)value).getType() != BslValueType.REFERENCE)
            return false;

        String type = safeType(value);
        return type.contains("Ссылка"); //$NON-NLS-1$
    }

    private boolean isEnumReference(IValue value)
    {
        String type = safeType(value);
        return type.startsWith("Перечисление."); //$NON-NLS-1$
    }

    private String trimQuotes(String value)
    {
        String result = value.trim();
        if (result.length() >= 2 && result.startsWith("\"") && result.endsWith("\"")) //$NON-NLS-1$ //$NON-NLS-2$
            return result.substring(1, result.length() - 1);
        return result;
    }

    private IStackFrame currentStackFrame()
    {
        return adaptStackFrame(DebugUITools.getDebugContext());
    }

    private LocationInfo currentLocation(IStackFrame frame)
    {
        if (frame == null)
            return new LocationInfo("Неизвестное место остановки", "", -1, ""); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        String frameName = safeFrameName(frame);
        int line = safeLine(frame);
        String source = frame instanceof IBslStackFrame && ((IBslStackFrame)frame).getSource() != null
            ? ((IBslStackFrame)frame).getSource().toString()
            : ""; //$NON-NLS-1$
        String summary = frameName + (line > 0 ? ", строка " + line : ""); //$NON-NLS-1$ //$NON-NLS-2$
        return new LocationInfo(summary, source, line, safeDebugTargetName(frame));
    }

    private IStackFrame adaptStackFrame(Object context)
    {
        if (context instanceof IStackFrame)
            return (IStackFrame)context;
        if (context instanceof IThread)
        {
            try
            {
                return ((IThread)context).getTopStackFrame();
            }
            catch (DebugException e)
            {
                return null;
            }
        }
        if (context instanceof IAdaptable)
        {
            Object frame = ((IAdaptable)context).getAdapter(IStackFrame.class);
            if (frame instanceof IStackFrame)
                return (IStackFrame)frame;
        }
        return null;
    }

    private String safeFrameName(IStackFrame frame)
    {
        try
        {
            return frame.getName();
        }
        catch (DebugException e)
        {
            return "Неизвестный стековый кадр"; //$NON-NLS-1$
        }
    }

    private int safeLine(IStackFrame frame)
    {
        try
        {
            return frame.getLineNumber();
        }
        catch (DebugException e)
        {
            return -1;
        }
    }

    private String safeDebugTargetName(IStackFrame frame)
    {
        try
        {
            return frame.getDebugTarget() != null ? frame.getDebugTarget().getName() : ""; //$NON-NLS-1$
        }
        catch (DebugException e)
        {
            return ""; //$NON-NLS-1$
        }
    }

    private void appendLocation(StringBuilder json, LocationInfo location)
    {
        json.append("{\n"); //$NON-NLS-1$
        appendStringField(json, 2, "summary", location.summary, true); //$NON-NLS-1$
        appendStringField(json, 2, "source", location.source, true); //$NON-NLS-1$
        json.append(indent(2)).append("\"line\": ").append(location.line).append(",\n"); //$NON-NLS-1$ //$NON-NLS-2$
        appendStringField(json, 2, "debugTarget", location.debugTarget, false); //$NON-NLS-1$
        json.append('\n').append(indent(1)).append('}');
    }

    private void appendStringField(StringBuilder json, int indent, String name, String value, boolean comma)
    {
        json.append(indent(indent)).append(VariableSnapshotJson.quote(name)).append(": ") //$NON-NLS-1$
            .append(VariableSnapshotJson.quote(value));
        if (comma)
            json.append(',');
        json.append('\n');
    }

    private String indent(int indent)
    {
        return "  ".repeat(indent); //$NON-NLS-1$
    }

    private static final class LocationInfo
    {
        final String summary;
        final String source;
        final int line;
        final String debugTarget;

        LocationInfo(String summary, String source, int line, String debugTarget)
        {
            this.summary = summary;
            this.source = source;
            this.line = line;
            this.debugTarget = debugTarget;
        }
    }

    private static final class ChildrenInfo
    {
        final IVariable[] variables;
        final boolean truncated;

        ChildrenInfo(IVariable[] variables, boolean truncated)
        {
            this.variables = variables;
            this.truncated = truncated;
        }
    }

    private static final class SyntheticChild
    {
        final String name;
        final String expression;
        final IValue value;

        SyntheticChild(String name, String expression, IValue value)
        {
            this.name = name;
            this.expression = expression;
            this.value = value;
        }
    }
}
