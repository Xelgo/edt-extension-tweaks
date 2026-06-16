package ru.xelgo.edt.contextlinks.core;

import java.io.IOException;
import java.util.Map;

import org.eclipse.equinox.service.weaving.ISupplementerRegistry;
import org.eclipse.equinox.service.weaving.IWeavingService;
import org.eclipse.equinox.service.weaving.IWeavingServiceFactory;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.osgi.framework.Bundle;
import org.osgi.framework.wiring.BundleRevision;

/**
 * Narrow weaving service for EDT Query Wizard internals.
 */
final class ContextLinksQueryWizardWeavingServiceFactory
    implements IWeavingServiceFactory
{
    private static final String QUERY_WIZARD_UI_BUNDLE = "com._1c.g5.v8.dt.qw.ui"; //$NON-NLS-1$
    private static final String SOURCES_EDIT_PROVIDER =
        "com/_1c/g5/v8/dt/qw/ui/editproviders/SourcesEditProvider"; //$NON-NLS-1$
    private static final String QUERY_WIZARD_ADOPT_SUPPORT =
        "com/_1c/g5/v8/dt/internal/qw/ui/QueryWizardAdoptSupport"; //$NON-NLS-1$
    private static final String PATCHES = "ru/xelgo/edt/contextlinks/core/ContextLinksQueryWizardPatches"; //$NON-NLS-1$

    @Override
    public IWeavingService createWeavingService(ClassLoader classLoader, Bundle bundle, BundleRevision bundleRevision,
        ISupplementerRegistry supplementerRegistry)
    {
        if (!ContextLinksPreferences.isQueryWizardEnabled())
            return null;

        if (bundle == null)
            return null;

        String symbolicName = bundle.getSymbolicName();
        if (!QUERY_WIZARD_UI_BUNDLE.equals(symbolicName))
            return null;

        ContextLinks.logDebug("EDT Extension Tweaks weaving active for " + symbolicName); //$NON-NLS-1$
        return new QueryWizardWeavingService();
    }

    private static final class QueryWizardWeavingService
        implements IWeavingService
    {
        @Override
        public void flushGeneratedClasses(ClassLoader classLoader)
        {
            // No generated classes.
        }

        @Override
        public boolean generatedClassesExistFor(ClassLoader classLoader, String className)
        {
            return false;
        }

        @Override
        public Map<String, byte[]> getGeneratedClassesFor(String className)
        {
            return Map.of();
        }

        @Override
        public String getKey()
        {
            return "ru.xelgo.edt.contextlinks.qw"; //$NON-NLS-1$
        }

        @Override
        public byte[] preProcess(String className, byte[] bytes, ClassLoader classLoader)
            throws IOException
        {
            if (!ContextLinksPreferences.isQueryWizardEnabled())
                return null;

            String internalClassName = className != null ? className.replace('.', '/') : null;
            if (SOURCES_EDIT_PROVIDER.equals(internalClassName))
                return patchSourcesEditProvider(bytes);
            if (QUERY_WIZARD_ADOPT_SUPPORT.equals(internalClassName))
                return patchQueryWizardAdoptSupport(bytes);
            return null;
        }

        private byte[] patchSourcesEditProvider(byte[] bytes)
        {
            ClassNode classNode = read(bytes);
            for (MethodNode method : classNode.methods)
            {
                if (!"equalsDbViewFromQueryNames".equals(method.name) //$NON-NLS-1$
                    || !"(Lcom/_1c/g5/v8/dt/metadata/dbview/DbViewElement;Lcom/_1c/g5/v8/dt/metadata/dbview/DbViewElement;)Z" //$NON-NLS-1$
                        .equals(method.desc))
                {
                    continue;
                }

                method.instructions.clear();
                method.tryCatchBlocks.clear();
                method.localVariables.clear();
                method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
                method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
                method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, PATCHES,
                    "equalsDbViewFromQueryNamesOrLogical", //$NON-NLS-1$
                    "(Lcom/_1c/g5/v8/dt/metadata/dbview/DbViewElement;Lcom/_1c/g5/v8/dt/metadata/dbview/DbViewElement;)Z", //$NON-NLS-1$
                    false));
                method.instructions.add(new InsnNode(Opcodes.IRETURN));
                method.maxStack = 2;
                method.maxLocals = Math.max(method.maxLocals, 3);
                ContextLinks.logDebug("EDT Extension Tweaks patched Query Wizard table matching"); //$NON-NLS-1$
                return write(classNode);
            }
            return null;
        }

        private byte[] patchQueryWizardAdoptSupport(byte[] bytes)
        {
            ClassNode classNode = read(bytes);
            boolean patched = false;
            for (MethodNode method : classNode.methods)
            {
                if (!"collectObjectsToAdopt".equals(method.name) || !"()Ljava/util/Set;".equals(method.desc)) //$NON-NLS-1$ //$NON-NLS-2$
                    continue;

                for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null;
                    instruction = instruction.getNext())
                {
                    if (instruction.getOpcode() != Opcodes.ARETURN)
                        continue;

                    InsnList filter = new InsnList();
                    filter.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    filter.add(new MethodInsnNode(Opcodes.INVOKESTATIC, PATCHES, "filterObjectsToAdopt", //$NON-NLS-1$
                        "(Ljava/util/Set;Ljava/lang/Object;)Ljava/util/Set;", false)); //$NON-NLS-1$
                    method.instructions.insertBefore(instruction, filter);
                    patched = true;
                }
                method.maxStack = Math.max(method.maxStack, 2);
            }

            if (patched)
            {
                ContextLinks.logDebug("EDT Extension Tweaks patched Query Wizard adoption filter"); //$NON-NLS-1$
                return write(classNode);
            }
            return null;
        }

        private ClassNode read(byte[] bytes)
        {
            ClassReader reader = new ClassReader(bytes);
            ClassNode classNode = new ClassNode();
            reader.accept(classNode, 0);
            return classNode;
        }

        private byte[] write(ClassNode classNode)
        {
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            classNode.accept(writer);
            return writer.toByteArray();
        }
    }
}
