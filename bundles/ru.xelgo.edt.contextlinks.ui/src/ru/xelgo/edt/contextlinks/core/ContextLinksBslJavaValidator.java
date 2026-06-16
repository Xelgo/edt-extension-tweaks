package ru.xelgo.edt.contextlinks.core;

import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;

import com._1c.g5.v8.dt.bsl.model.Method;
import com._1c.g5.v8.dt.bsl.validation.IMessages;
import com._1c.g5.v8.dt.bsl.validation.BslJavaValidator;
import com._1c.g5.v8.dt.mcore.McorePackage;
import com._1c.g5.v8.dt.validation.legacy.LegacyCheck;
import com._1c.g5.v8.dt.validation.legacy.ValidationIssueType;
import com._1c.g5.v8.dt.validation.legacy.ValidationSeverity;
import com.google.inject.Singleton;

/**
 * Keeps the native ChangeAndValidate check, but teaches it Configurator-style dirty blocks.
 */
@Singleton
public class ContextLinksBslJavaValidator
    extends BslJavaValidator
{
    private static final String METHOD_TEXT_DIFF_CODE = "method-text-has-differences-base-method"; //$NON-NLS-1$
    private static final int LOG_LIMIT = Integer.getInteger(
        ContextLinks.PLUGIN_ID + ".dirtyPreprocessor.validatorSuppressLogLimit", 20); //$NON-NLS-1$
    private static final AtomicInteger loggedCreations = new AtomicInteger();
    private static final AtomicInteger loggedCustomDiagnostics = new AtomicInteger();
    private static final AtomicInteger loggedMethodTextDiagnostics = new AtomicInteger();
    private static final AtomicInteger loggedSuppressions = new AtomicInteger();

    public ContextLinksBslJavaValidator()
    {
        if (loggedCreations.getAndIncrement() < 3)
            ContextLinks.logDebug("EDT Extension Tweaks BSL Java validator override created"); //$NON-NLS-1$
    }

    @Override
    protected void error(String message, EObject source, EStructuralFeature feature, String code, String... issueData)
    {
        if (shouldSuppressDirtyMethodTextDifference(source, code))
            return;

        super.error(message, source, feature, code, issueData);
    }

    @Override
    protected void error(String message, EObject source, EStructuralFeature feature, int index, String code,
        String... issueData)
    {
        if (shouldSuppressDirtyMethodTextDifference(source, code))
            return;

        super.error(message, source, feature, index, code, issueData);
    }

    @LegacyCheck(issueType = ValidationIssueType.ERROR, issueSeverity = ValidationSeverity.MAJOR)
    public void checkDirtyChangeAndValidateMethodText(Method method)
    {
        if (!ContextLinksPreferences.isDirtyPreprocessorEnabled())
            return;

        long id = DirtyPreprocessorDiagnostics.nextId();
        long started = System.nanoTime();
        ContextLinksBslValidatorPatches.logDirtyValidationProbe(method, "custom-check.enter"); //$NON-NLS-1$
        DirtyPreprocessorDiagnostics.logStart(id, "validator.custom-check", methodDetails(method)); //$NON-NLS-1$

        if (!ContextLinksBslValidatorPatches.mayHaveDirtyPreprocessor(method))
        {
            DirtyPreprocessorDiagnostics.logEnd(id, "validator.custom-check", started, //$NON-NLS-1$
                methodDetails(method) + " result=no-marker"); //$NON-NLS-1$
            return;
        }

        if (!ContextLinksBslValidatorPatches.hasDirtyPreprocessorMeaningfulDifference(method))
        {
            DirtyPreprocessorDiagnostics.logEnd(id, "validator.custom-check", started, //$NON-NLS-1$
                methodDetails(method) + " result=no-meaningful-diff"); //$NON-NLS-1$
            return;
        }

        if (loggedCustomDiagnostics.getAndIncrement() < LOG_LIMIT)
        {
            String methodName = method != null && method.getName() != null ? method.getName() : "UNKNOWN"; //$NON-NLS-1$
            ContextLinks.logDebug("EDT Extension Tweaks emits dirty ChangeAndValidate method text diagnostic method=" //$NON-NLS-1$
                + methodName);
        }

        super.error(IMessages.INSTANCE.method_text_has_differences_to_base_method(), method,
            McorePackage.Literals.NAMED_ELEMENT__NAME, METHOD_TEXT_DIFF_CODE);
        DirtyPreprocessorDiagnostics.logEnd(id, "validator.custom-check", started, //$NON-NLS-1$
            methodDetails(method) + " result=diagnostic"); //$NON-NLS-1$
    }

    private static boolean shouldSuppressDirtyMethodTextDifference(EObject source, String code)
    {
        if (!ContextLinksPreferences.isDirtyPreprocessorEnabled())
            return false;

        if (!METHOD_TEXT_DIFF_CODE.equals(code))
            return false;

        if (loggedMethodTextDiagnostics.getAndIncrement() < LOG_LIMIT)
        {
            String sourceType = source != null ? source.getClass().getName() : "NULL"; //$NON-NLS-1$
            ContextLinks.logDebug("EDT Extension Tweaks ChangeAndValidate diagnostic intercepted source=" //$NON-NLS-1$
                + sourceType + " code=" + code); //$NON-NLS-1$
        }

        if (!(source instanceof Method method))
            return false;

        ContextLinksBslValidatorPatches.logDirtyValidationProbe(method, "native-diagnostic.intercepted"); //$NON-NLS-1$

        if (!ContextLinksBslValidatorPatches.hasOnlyDirtyPreprocessorDifference(method))
            return false;

        if (loggedSuppressions.getAndIncrement() < LOG_LIMIT)
        {
            String methodName = method.getName() != null ? method.getName() : "UNKNOWN"; //$NON-NLS-1$
            ContextLinks.logDebug("EDT Extension Tweaks suppressed dirty ChangeAndValidate method text diagnostic method=" //$NON-NLS-1$
                + methodName);
        }
        return true;
    }

    private static String methodDetails(Method method)
    {
        return "method=" + (method != null && method.getName() != null ? method.getName() : "UNKNOWN") //$NON-NLS-1$ //$NON-NLS-2$
            + " resource=" + (method != null && method.eResource() != null ? method.eResource().getURI() : "NO_RESOURCE"); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
