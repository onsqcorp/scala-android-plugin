package com.soundcorset.scala.android.plugin;

import com.android.build.api.artifact.ScopedArtifact;
import com.android.build.api.dsl.CommonExtension;
import com.android.build.api.variant.*;
import com.android.build.gradle.*;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.dsl.DependencyFactory;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.plugins.PluginContainer;
import org.gradle.api.plugins.jvm.internal.JvmPluginServices;
import org.gradle.api.plugins.scala.ScalaBasePlugin;
import org.gradle.api.plugins.scala.ScalaPluginExtension;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.api.tasks.scala.ScalaCompile;
import org.gradle.api.tasks.scala.internal.ScalaRuntimeHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.util.*;


public class ScalaAndroidPlugin extends ScalaBasePlugin {
    private static final Logger LOGGER = LoggerFactory.getLogger(ScalaAndroidPlugin.class);
    private final DependencyFactory dependencyFactory;

    @Inject
    public ScalaAndroidPlugin(ObjectFactory objectFactory, JvmPluginServices jvmPluginServices, DependencyFactory dependencyFactory) {
        super(objectFactory, jvmPluginServices, dependencyFactory);
        this.dependencyFactory = dependencyFactory;
    }

    public void apply(Project project) {
        super.apply(project);
        ensureAndroidPlugin(project.getPlugins());
        var extensions = project.getExtensions();
        CommonExtension androidExt = extensions.getByType(CommonExtension.class);
        var scalaPluginExt = extensions.getByType(ScalaPluginExtension.class);
        configureScalaSourceSet(dependencyFactory, project, androidExt, scalaPluginExt);
        var androidComponents = (AndroidComponentsExtension<?, ?, Variant>)extensions.getByType(AndroidComponentsExtension.class);
        androidComponents.onVariants(androidComponents.selector().all(), variant -> {
            ensureScalaVersionSpecified(scalaPluginExt);
            processVariant(project, variant);
        });
    }

    public static void configureScalaSourceSet(DependencyFactory dependencyFactory, Project project, CommonExtension androidExt, ScalaPluginExtension scalaPluginExt) {
        // The function `all()` take account all the future additions for the source sets
        androidExt.getSourceSets().all(sourceSet -> {
            sourceSet.getJava().getDirectories().add("src/" + sourceSet.getName() + "/scala");
            project.getConfigurations().getByName(sourceSet.getImplementationConfigurationName()).getDependencies()
                    .addLater(createScalaDependency(dependencyFactory, scalaPluginExt));
        });
    }

    public static void ensureScalaVersionSpecified(ScalaPluginExtension scalaPluginExt) {
        if (!scalaPluginExt.getScalaVersion().isPresent()) {
            throw new GradleException("scala.scalaVersion property needs to be specified. See https://docs.gradle.org/8.13/userguide/scala_plugin.html#sec:scala_version");
        }
    }

    // Also available in org.jetbrains.kotlin.gradle.utils.androidPluginIds
    public static final List<String> ANDROID_PLUGIN_NAMES = Arrays.asList(
            "com.android.application", "com.android.library", "com.android.dynamic-feature", "com.android.test"
    );

    public static void ensureAndroidPlugin(PluginContainer plugins) {
        if (ANDROID_PLUGIN_NAMES.stream().noneMatch(plugins::hasPlugin)) {
            throw new GradleException("You must apply the Android plugin or the Android library plugin before using the scala-android plugin");
        }
    }

    public static void processVariant(Project project, Variant variant) {
        String variantName = variant.getName();
        String VName = capitalize(variantName);
        String intermediatePath = "intermediates/scala/" + variantName;
        var buildDir = project.getLayout().getBuildDirectory();
        var configurations = project.getConfigurations();
        var extensions = project.getExtensions();
        var androidComponents = extensions.getByType(AndroidComponentsExtension.class);
        TaskProvider<ScalaCompile> scalaTaskProvider = project.getTasks().register("compile" + VName + "Scala", ScalaCompile.class);
        scalaTaskProvider.configure(scalaTask -> {
            scalaTask.getDestinationDirectory().set(buildDir.dir(intermediatePath + "/classes"));
            // See https://docs.gradle.org/9.0.0/userguide/scala_plugin.html#sec:scala_version and ScalaBasePlugin.java for the details about "scalaToolchainRuntimeClasspath"
            scalaTask.setScalaClasspath(configurations.getByName("scalaToolchainRuntimeClasspath"));
            var incrementalOptions = scalaTask.getScalaCompileOptions().getIncrementalOptions();
            incrementalOptions.getAnalysisFile().set(buildDir.file(intermediatePath + "/incremental.analysis"));
            incrementalOptions.getClassfileBackupDir().set(buildDir.file(intermediatePath + "/classfile.bak"));
        });
        variant.getArtifacts().forScope(ScopedArtifacts.Scope.PROJECT).use(scalaTaskProvider)
                .toAppend(ScopedArtifact.CLASSES.INSTANCE, ScalaCompile::getDestinationDirectory);
        // Related issue: https://issuetracker.google.com/issues/479577764
        var stripTaskProvider = project.getTasks().register("strip" + VName + "RJar", StripFinalModifierTask.class, task -> {
            var processResProvider = project.getTasks().named("generate" + VName + "RFile");
            task.getRJarCollection().from(
                processResProvider.map(t -> t.getOutputs().getFiles().filter(f -> f.getName().equals("R.jar")))
            );
            task.getOutputJar().set(buildDir.file("intermediates/scala_r/" + variantName + "/safe_r.jar"));
        });

        project.afterEvaluate(p -> {
            var javaTask = (JavaCompile) project.getTasks().findByName("compile" + VName + "JavaWithJavac");
            var scalaPluginExt = extensions.getByType(ScalaPluginExtension.class);
            scalaTaskProvider.configure(scalaTask -> {
                var rClasspath = scalaPluginExt.getScalaVersion().get().startsWith("2") ? project.files(stripTaskProvider) :
                        project.getTasks().findByName("generate" + VName + "RFile").getOutputs().getFiles().filter(f -> f.getName().equals("R.jar"));
                scalaTask.setClasspath(rClasspath
                        .plus(variant.getCompileClasspath().filter(f -> !f.getName().equals("R.jar")))
                        .plus(project.files(androidComponents.getSdkComponents().getBootClasspath())));
                javaTask.getDependsOn().forEach(scalaTask::dependsOn);
                scalaTask.setSource(project.files(variant.getSources().getJava().getAll()));
                javaTask.setSource(project.getObjects().fileCollection()); // set empty source
                var processResProvider = project.getTasks().named("process" + VName + "Resources");
                // As the javaTask.source is empty, it looses dependency to process...Resources, so we compensate it. There is no performance impact because the scalaTask is the bottleneck.
                javaTask.mustRunAfter(processResProvider);
                var annotationProcessorPath = javaTask.getOptions().getAnnotationProcessorPath();
                scalaTask.getOptions().setAnnotationProcessorPath(annotationProcessorPath);
                javaTask.dependsOn(scalaTask);
            });
            // Workaround to resolve the IntelliJ Scala IDE plugin not recognizing R.jar
            // https://github.com/onsqcorp/scala-android-plugin/issues/2#issuecomment-2394861477
            String compileOnlyConfigName = variantName + "CompileOnly";
            Optional.ofNullable(configurations.findByName(compileOnlyConfigName)).map(c ->
                    project.getDependencies().add(compileOnlyConfigName, project.fileTree(buildDir).include("**/" + variantName + "/**/R.jar"))
            );
        });
    }

    // Would be better if ScalaBasePlugin.createScalaDependency() is public
    public static Provider<Dependency> createScalaDependency(DependencyFactory dependencyFactory, ScalaPluginExtension scalaPluginExtension) {
        return scalaPluginExtension.getScalaVersion().map(scalaVersion -> {
            if (ScalaRuntimeHelper.isScala3(scalaVersion)) {
                return dependencyFactory.create("org.scala-lang", "scala3-library_3", scalaVersion);
            } else {
                return dependencyFactory.create("org.scala-lang", "scala-library", scalaVersion);
            }
        });
    }

    private static String capitalize(String s) {
        return s.substring(0, 1).toUpperCase() + s.substring(1);
    }
}
