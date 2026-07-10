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
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.osgi.framework.Bundle;
import org.osgi.framework.wiring.BundleRevision;

/**
 * Narrow weaving service for EDT BSL UI internals.
 */
final class ContextLinksBslUiWeavingServiceFactory
    implements IWeavingServiceFactory
{
    private static final String BSL_UI_BUNDLE = "com._1c.g5.v8.dt.bsl.ui"; //$NON-NLS-1$
    private static final String PARAMETERS_HOVER_INFO_CONTROL =
        "com/_1c/g5/v8/dt/bsl/ui/contentassist/ParametersHoverInfoControl"; //$NON-NLS-1$
    private static final String BSL_COMMENT_UI_UTILS =
        "com/_1c/g5/v8/dt/bsl/ui/contentassist/BslCommentUiUtils"; //$NON-NLS-1$
    private static final String BSL_CONTENT_ASSIST_BROWSER_INPUT =
        "com/_1c/g5/v8/dt/bsl/ui/contentassist/BslContentAssistBrowserInput"; //$NON-NLS-1$
    private static final String INVOCATION_PARAMETERS_HOVER_HANDLER =
        "com/_1c/g5/v8/dt/bsl/ui/contentassist/InvocationParametersHoverHandler"; //$NON-NLS-1$
    private static final String INVOCATION_PARAMETERS_HOVER_UNIT =
        "com/_1c/g5/v8/dt/bsl/ui/contentassist/InvocationParametersHoverHandler$1"; //$NON-NLS-1$
    private static final String INVOCATION_PARAMETER_INFO =
        "com/_1c/g5/v8/dt/bsl/ui/contentassist/InvocationParametersHoverHandler$ParameterInfo"; //$NON-NLS-1$
    private static final String PREFERENCES =
        "ru/xelgo/edt/contextlinks/core/ContextLinksPreferences"; //$NON-NLS-1$
    private static final String DOCUMENTATION_PATCHES =
        "ru/xelgo/edt/contextlinks/core/ContextLinksBslDocumentationPatches"; //$NON-NLS-1$

    @Override
    public IWeavingService createWeavingService(ClassLoader classLoader, Bundle bundle, BundleRevision bundleRevision,
        ISupplementerRegistry supplementerRegistry)
    {
        if (bundle == null)
            return null;

        String symbolicName = bundle.getSymbolicName();
        if (!BSL_UI_BUNDLE.equals(symbolicName))
            return null;

        return new BslUiWeavingService();
    }

    private static final class BslUiWeavingService
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
            return "ru.xelgo.edt.contextlinks.bsl.ui"; //$NON-NLS-1$
        }

        @Override
        public byte[] preProcess(String className, byte[] bytes, ClassLoader classLoader)
            throws IOException
        {
            String internalClassName = className != null ? className.replace('.', '/') : null;
            if (PARAMETERS_HOVER_INFO_CONTROL.equals(internalClassName))
                return patchParametersHoverInfoControl(bytes);
            if (INVOCATION_PARAMETERS_HOVER_HANDLER.equals(internalClassName))
                return patchInvocationParametersHoverHandler(bytes);
            if (INVOCATION_PARAMETERS_HOVER_UNIT.equals(internalClassName))
                return patchInvocationParametersHoverUnit(bytes);
            if (BSL_COMMENT_UI_UTILS.equals(internalClassName))
                return patchBslCommentUiUtils(bytes);
            return null;
        }

        private byte[] patchParametersHoverInfoControl(byte[] bytes)
        {
            ClassNode classNode = read(bytes);
            boolean patched = false;

            for (MethodNode method : classNode.methods)
            {
                if (!"showPage".equals(method.name) || !"(Ljava/util/List;II)V".equals(method.desc)) //$NON-NLS-1$ //$NON-NLS-2$
                    continue;

                for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null;
                    instruction = instruction.getNext())
                {
                    if (!(instruction instanceof MethodInsnNode methodInsn)
                        || instruction.getOpcode() != Opcodes.INVOKESPECIAL
                        || !BSL_CONTENT_ASSIST_BROWSER_INPUT.equals(methodInsn.owner)
                        || !"<init>".equals(methodInsn.name) //$NON-NLS-1$
                        || !("(L" + BSL_CONTENT_ASSIST_BROWSER_INPUT + ";IIZLjava/util/List;)V") //$NON-NLS-1$ //$NON-NLS-2$
                            .equals(methodInsn.desc))
                    {
                        continue;
                    }

                    AbstractInsnNode listLoad = previousInstruction(instruction);
                    AbstractInsnNode showDescription = previousInstruction(listLoad);
                    if (showDescription == null || showDescription.getOpcode() != Opcodes.ICONST_0)
                        continue;

                    method.instructions.set(showDescription, new MethodInsnNode(Opcodes.INVOKESTATIC, PREFERENCES,
                        "isMethodParameterInfoDescriptionVisible", "()Z", false)); //$NON-NLS-1$ //$NON-NLS-2$
                    patched = true;
                    break;
                }
                method.maxStack = Math.max(method.maxStack, 5);
            }

            if (patched)
            {
                return write(classNode);
            }
            return null;
        }

        private byte[] patchInvocationParametersHoverHandler(byte[] bytes)
        {
            ClassNode classNode = read(bytes);
            boolean patched = false;

            for (MethodNode method : classNode.methods)
            {
                if (!"execute".equals(method.name) //$NON-NLS-1$
                    || !"(Lorg/eclipse/core/commands/ExecutionEvent;)Ljava/lang/Object;".equals(method.desc)) //$NON-NLS-1$
                {
                    continue;
                }

                for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null;
                    instruction = instruction.getNext())
                {
                    if (!(instruction instanceof TypeInsnNode typeInsn)
                        || instruction.getOpcode() != Opcodes.CHECKCAST
                        || !INVOCATION_PARAMETER_INFO.equals(typeInsn.desc))
                    {
                        continue;
                    }

                    AbstractInsnNode storeInstruction = nextInstruction(instruction);
                    if (!(storeInstruction instanceof VarInsnNode varInsn)
                        || storeInstruction.getOpcode() != Opcodes.ASTORE)
                    {
                        continue;
                    }

                    InsnList recordParameterInfo = new InsnList();
                    recordParameterInfo.add(new VarInsnNode(Opcodes.ALOAD, varInsn.var));
                    recordParameterInfo.add(new MethodInsnNode(Opcodes.INVOKESTATIC, DOCUMENTATION_PATCHES,
                        "recordInvocationParameterInfo", "(Ljava/lang/Object;)V", false)); //$NON-NLS-1$ //$NON-NLS-2$
                    method.instructions.insert(storeInstruction, recordParameterInfo);
                    patched = true;
                    break;
                }
                method.maxStack = Math.max(method.maxStack, 2);
            }

            if (patched)
            {
                return write(classNode);
            }
            return null;
        }

        private byte[] patchInvocationParametersHoverUnit(byte[] bytes)
        {
            ClassNode classNode = read(bytes);
            boolean patched = false;

            for (MethodNode method : classNode.methods)
            {
                if (!"exec".equals(method.name) //$NON-NLS-1$
                    || !("(Lorg/eclipse/xtext/resource/XtextResource;)L" + INVOCATION_PARAMETER_INFO + ";") //$NON-NLS-1$ //$NON-NLS-2$
                        .equals(method.desc))
                {
                    continue;
                }

                for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null;
                    instruction = instruction.getNext())
                {
                    if (!(instruction instanceof MethodInsnNode methodInsn)
                        || instruction.getOpcode() != Opcodes.INVOKEVIRTUAL
                        || !"org/eclipse/xtext/resource/EObjectAtOffsetHelper".equals(methodInsn.owner) //$NON-NLS-1$
                        || !"resolveContainedElementAt".equals(methodInsn.name) //$NON-NLS-1$
                        || !("(Lorg/eclipse/xtext/resource/XtextResource;I)Lorg/eclipse/emf/ecore/EObject;") //$NON-NLS-1$
                            .equals(methodInsn.desc))
                    {
                        continue;
                    }

                    method.instructions.insertBefore(instruction, new MethodInsnNode(Opcodes.INVOKESTATIC,
                        DOCUMENTATION_PATCHES, "adjustInvocationParameterLookupOffset", "(I)I", false)); //$NON-NLS-1$ //$NON-NLS-2$
                    patched = true;
                    break;
                }
                method.maxStack = Math.max(method.maxStack, 3);
            }

            if (patched)
            {
                return write(classNode);
            }
            return null;
        }

        private byte[] patchBslCommentUiUtils(byte[] bytes)
        {
            ClassNode classNode = read(bytes);
            boolean patched = false;

            for (MethodNode method : classNode.methods)
            {
                if (!"parseTemplateComment".equals(method.name) //$NON-NLS-1$
                    || !method.desc.startsWith("(Ljava/util/List;Lcom/_1c/g5/v8/dt/bsl/model/Method;") //$NON-NLS-1$
                    || !method.desc.endsWith(
                        ")Lcom/_1c/g5/v8/dt/internal/bsl/ui/documentation/page/MethodLikeBslDocumentationPage;")) //$NON-NLS-1$
                {
                    continue;
                }

                for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null;
                    instruction = instruction.getNext())
                {
                    if (instruction.getOpcode() != Opcodes.ARETURN)
                        continue;

                    InsnList enrich = new InsnList();
                    enrich.add(new InsnNode(Opcodes.DUP));
                    enrich.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    enrich.add(new MethodInsnNode(Opcodes.INVOKESTATIC, DOCUMENTATION_PATCHES,
                        "enrichMethodParameterDescriptions", "(Ljava/lang/Object;Ljava/util/List;)V", false)); //$NON-NLS-1$ //$NON-NLS-2$
                    method.instructions.insertBefore(instruction, enrich);
                    patched = true;
                }
                method.maxStack = Math.max(method.maxStack, 3);
            }

            if (patched)
            {
                return write(classNode);
            }
            return null;
        }

        private AbstractInsnNode previousInstruction(AbstractInsnNode instruction)
        {
            AbstractInsnNode previous = instruction != null ? instruction.getPrevious() : null;
            while (previous != null && previous.getOpcode() < 0)
                previous = previous.getPrevious();
            return previous;
        }

        private AbstractInsnNode nextInstruction(AbstractInsnNode instruction)
        {
            AbstractInsnNode next = instruction != null ? instruction.getNext() : null;
            while (next != null && next.getOpcode() < 0)
                next = next.getNext();
            return next;
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
