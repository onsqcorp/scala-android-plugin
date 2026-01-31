package com.soundcorset.scala.android.plugin;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.*;
import org.objectweb.asm.*;
import java.io.*;
import java.util.jar.*;
import java.util.Set;
import javax.inject.Inject;

@CacheableTask
public abstract class StripFinalModifierTask extends DefaultTask {
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getRJarCollection();

    @OutputFile
    public abstract RegularFileProperty getOutputJar();

    @Inject
    public StripFinalModifierTask() { }

    @TaskAction
    public void execute() throws IOException {
        Set<File> files = getRJarCollection().getFiles();
        File rJarFile = null;
        if (!files.isEmpty()) {
            rJarFile = files.iterator().next();
        }

        File outputFile = getOutputJar().get().getAsFile();
        if (rJarFile == null || !rJarFile.exists()) {
            try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(outputFile))) {
            }
            return;
        }
        try (JarInputStream jarIn = new JarInputStream(new FileInputStream(rJarFile));
             JarOutputStream jarOut = new JarOutputStream(new FileOutputStream(outputFile))) {

            JarEntry entry;
            while ((entry = jarIn.getNextJarEntry()) != null) {
                jarOut.putNextEntry(new JarEntry(entry.getName()));

                if (entry.getName().endsWith(".class") && (entry.getName().endsWith("/R.class") || entry.getName().contains("/R$"))) {
                    ClassReader cr = new ClassReader(jarIn);
                    ClassWriter cw = new ClassWriter(0);

                    cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
                        @Override
                        public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
                            super.visit(version, access & ~Opcodes.ACC_FINAL, name, signature, superName, interfaces);
                        }
                        @Override
                        public void visitInnerClass(String name, String outerName, String innerName, int access) {
                            super.visitInnerClass(name, outerName, innerName, access & ~Opcodes.ACC_FINAL);
                        }
                        @Override
                        public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                            return super.visitField(access & ~Opcodes.ACC_FINAL, name, descriptor, signature, null);
                        }
                    }, 0);
                    jarOut.write(cw.toByteArray());
                } else {
                    jarIn.transferTo(jarOut);
                }
                jarOut.closeEntry();
            }
        }
    }
}