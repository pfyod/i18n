package net.jhorstmann.i18n.tools;

import antlr.RecognitionException;
import antlr.TokenStreamException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.ResourceBundle;
import net.jhorstmann.i18n.tools.expr.Expression;
import org.fedorahosted.tennera.jgettext.Message;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import static org.objectweb.asm.Opcodes.*;
import org.objectweb.asm.commons.GeneratorAdapter;
import org.objectweb.asm.util.CheckClassAdapter;

public class ResourceBundleCompiler {

    private static final String CONTEXT_GLUE = "\u0004";
    private static final String DEFAULT_PARENT_CLASS = ResourceBundle.class.getName();

    static class MyClassLoader extends ClassLoader {

        MyClassLoader(ClassLoader parent) {
            super(parent);
        }

        final Class<?> defineClass(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }

    public static byte[] compile(MessageBundle bundle, String className) {
        return compile(bundle, DEFAULT_PARENT_CLASS, className);
    }

    public static void compileFile(MessageBundle bundle, String className, File dir) throws IOException {
        compileFile(bundle, DEFAULT_PARENT_CLASS, className, dir);
    }

    public static void compileFile(MessageBundle bundle, String parentClassName, String className, File dir) throws IOException {
        File file = new File(dir, className.replace('.', '/') + ".class");
        File parent = file.getParentFile();
        if (!parent.exists() && !parent.mkdirs()) {
            throw new IOException("Could not create directory " + parent);
        }
        byte[] bytes = compile(bundle, parentClassName, className);
        FileOutputStream fos = new FileOutputStream(file);
        try {
            fos.write(bytes);
        } finally {
            fos.close();
        }
    }

    static Class<ResourceBundle> compileAndLoad(MessageBundle bundle, String className) throws InstantiationException, IllegalAccessException {
        return compileAndLoad(bundle, DEFAULT_PARENT_CLASS, className, null);
    }

    static Class<ResourceBundle> compileAndLoad(MessageBundle bundle, String parentClassName, String className, ClassLoader parent) throws InstantiationException, IllegalAccessException {
        byte[] bytes = compile(bundle, parentClassName, className);
        return (Class<ResourceBundle>) new MyClassLoader(parent).defineClass(className, bytes);
    }

    public static byte[] compile(MessageBundle bundle, String parentClassName, String className) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        CheckClassAdapter ca = new CheckClassAdapter(cw, true);
        compile(ca, bundle, parentClassName, className);
        return cw.toByteArray();
    }

    static void compile(ClassVisitor cv, MessageBundle bundle, String parentClassName, String className) {
        String parent = parentClassName.replace('.', '/');
        String owner = className.replace('.', '/');
        cv.visit(V1_2, ACC_PUBLIC, owner, null, parent, null);
        cv.visitField(ACC_PRIVATE | ACC_STATIC, "messages", "Ljava/util/HashMap;", null, null);

        compileStaticInit(cv, owner, bundle);
        compileConstructor(cv, parent);
        compileGetParent(cv, parent);
        compileLookup(cv, owner);
        compileGetKeys(cv, owner);
        compileHandleGetObject(cv, owner);

        String pluralForms = bundle.getPluralForms();
        if (pluralForms != null) {
            compilePluralEval(cv, pluralForms);
            compilePluralIndex(cv, owner);
        } else {
            compilePluralIndexDummy(cv, owner);
        }

        cv.visitEnd();
    }

    private static void compileHandleGetObject(ClassVisitor cv, String owner) {
        MethodVisitor mv = cv.visitMethod(ACC_PUBLIC, "handleGetObject", "(Ljava/lang/String;)Ljava/lang/Object;", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEVIRTUAL, owner, "lookup", "(Ljava/lang/String;)Ljava/lang/Object;");

        mv.visitInsn(ARETURN);

        mv.visitMaxs(2, 2);

        /*
        Label isString = new Label();
        mv.visitVarInsn(ASTORE, 2);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitTypeInsn(INSTANCEOF, "[Ljava/lang/String;");
        mv.visitJumpInsn(IFEQ, isString);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitTypeInsn(CHECKCAST, "[Ljava/lang/String;");
        mv.visitInsn(ICONST_0);
        mv.visitInsn(AALOAD);
        mv.visitInsn(ARETURN);

        mv.visitLabel(isString);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(2, 3);
        */
        mv.visitEnd();
    }

    private static void compileGetKeys(ClassVisitor cv, String owner) {
        MethodVisitor mv = cv.visitMethod(ACC_PUBLIC, "getKeys", "()Ljava/util/Enumeration;", null, null);
        mv.visitCode();
        mv.visitFieldInsn(GETSTATIC, owner, "messages", "Ljava/util/HashMap;");
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/Map", "keySet", "()Ljava/util/Set;");
        mv.visitMethodInsn(INVOKESTATIC, "java/util/Collections", "enumeration", "(Ljava/util/Collection;)Ljava/util/Enumeration;");
        mv.visitInsn(ARETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
    }

    private static void compileLookup(ClassVisitor cv, String owner) {
        MethodVisitor mv = cv.visitMethod(ACC_PUBLIC, "lookup", "(Ljava/lang/String;)Ljava/lang/Object;", null, null);
        mv.visitCode();
        mv.visitFieldInsn(GETSTATIC, owner, "messages", "Ljava/util/HashMap;");
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/Map", "get", "(Ljava/lang/Object;)Ljava/lang/Object;");
        mv.visitInsn(ARETURN);
        mv.visitMaxs(2, 2);
        mv.visitEnd();
    }

    private static void compileGetParent(ClassVisitor cv, String parent) {
        MethodVisitor mv = cv.visitMethod(ACC_PUBLIC, "getParent", "()Ljava/util/ResourceBundle;", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, parent, "parent", "Ljava/util/ResourceBundle;");
        mv.visitInsn(ARETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
    }

    private static void compileConstructor(ClassVisitor cv, String parent) {
        MethodVisitor mv = cv.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESPECIAL, parent, "<init>", "()V");
        mv.visitInsn(RETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
    }

    private static void compileStaticInit(ClassVisitor cv, String owner, MessageBundle bundle) {
        Label localMapBegin = new Label();
        Label localMapEnd = new Label();
        Label localArrayBegin = new Label();
        Label localArrayEnd = new Label();

        MethodVisitor mv = cv.visitMethod(ACC_PUBLIC | ACC_STATIC, "<clinit>", "()V", null, null);

        mv.visitCode();

        mv.visitLabel(localMapBegin);
        mv.visitTypeInsn(NEW, "java/util/HashMap");
        mv.visitInsn(DUP);
        mv.visitMethodInsn(INVOKESPECIAL, "java/util/HashMap", "<init>", "()V");
        mv.visitVarInsn(ASTORE, 0);

        mv.visitLabel(localArrayBegin);
        // TODO: Depending on the amount of messages this might get too big for one method and should be split up
        for (Message message : bundle) {
            String msgctx = message.getMsgctxt();
            String msgid = (msgctx != null ? msgctx + CONTEXT_GLUE : "") + message.getMsgid();
            mv.visitVarInsn(ALOAD, 0);
            if (message.isPlural()) {
                List<String> plurals = bundle.isTemplate() ? Arrays.asList(msgid, message.getMsgidPlural()) : message.getMsgstrPlural();
                if (plurals.isEmpty()) {
                    mv.visitInsn(ACONST_NULL);
                    mv.visitLdcInsn(msgid);
                } else {
                    mv.visitLdcInsn(Integer.valueOf(plurals.size()));
                    mv.visitTypeInsn(ANEWARRAY, "java/lang/String");
                    mv.visitVarInsn(ASTORE, 1);
                    int i = 0;
                    for (String plural : plurals) {
                        mv.visitVarInsn(ALOAD, 1);
                        mv.visitLdcInsn(Integer.valueOf(i));
                        mv.visitLdcInsn(plural);
                        mv.visitInsn(AASTORE);
                        i++;
                    }
                    mv.visitLdcInsn(msgid);
                    mv.visitVarInsn(ALOAD, 1);
                }
            } else {
                String msgstr = bundle.isTemplate() ? message.getMsgid() : message.getMsgstr();
                if (msgstr == null || msgstr.length() == 0) {
                    mv.visitLdcInsn(msgid);
                    mv.visitInsn(ACONST_NULL);
                } else {
                    mv.visitLdcInsn(msgid);
                    mv.visitLdcInsn(msgstr);
                }
            }
            mv.visitMethodInsn(INVOKEINTERFACE, "java/util/Map", "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
            mv.visitInsn(POP);
        }
        mv.visitLabel(localArrayEnd);


        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(PUTSTATIC, owner, "messages", "Ljava/util/HashMap;");
        mv.visitLabel(localMapEnd);
        mv.visitInsn(RETURN);

        mv.visitLocalVariable("array", "[Ljava/lang/Object;", null, localArrayBegin, localArrayEnd, 1);
        mv.visitLocalVariable("map", "Ljava/util/Map;", null, localMapBegin, localMapEnd, 0);

        mv.visitMaxs(4, 2);
        mv.visitEnd();
    }

    private static void compilePluralEval(ClassVisitor cv, String pluralForms) {
        try {
            PluralForms pf = PluralsParser.parsePluralForms(pluralForms);
            Expression expr = pf.getExpression();
            MethodVisitor mv = cv.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "pluralEval", "(J)J", null, null);
            //mv.visitCode();
            //GeneratorAdapter ga = new GeneratorAdapter(ACC_PUBLIC | ACC_STATIC, Method.getMethod("long pluralEval(long)"), null, null, cv);
            GeneratorAdapter ga = new GeneratorAdapter(mv, Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "pluralEval", "(J)J");
            ga.visitCode();
            expr.compile(ga, 0);
            ga.returnValue();
            int stack = expr.computeStackSize();
            ga.visitMaxs(stack, 2);
            ga.visitEnd();
        } catch (RecognitionException ex) {
            throw new IllegalStateException(ex);
        } catch (TokenStreamException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static void compilePluralIndex(ClassVisitor cv, String owner) {
        MethodVisitor mv = cv.visitMethod(ACC_PUBLIC, "pluralIndex", "(J)I", null, null);
        mv.visitCode();
        mv.visitVarInsn(LLOAD, 1);
        mv.visitMethodInsn(INVOKESTATIC, owner, "pluralEval", "(J)J");
        mv.visitInsn(L2I);
        mv.visitInsn(IRETURN);
        mv.visitMaxs(3, 3);
        mv.visitEnd();
    }

    private static void compilePluralIndexDummy(ClassVisitor cv, String owner) {
        MethodVisitor mv = cv.visitMethod(ACC_PUBLIC, "pluralIndex", "(J)I", null, null);
        mv.visitCode();
        mv.visitInsn(ICONST_0);
        mv.visitInsn(IRETURN);
        mv.visitMaxs(1, 3);
        mv.visitEnd();
    }

}
