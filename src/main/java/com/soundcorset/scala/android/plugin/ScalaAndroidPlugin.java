package com.soundcorset.scala.android.plugin;

import com.android.build.api.dsl.CommonExtension;
import com.android.build.api.variant.AndroidComponentsExtension;
import com.android.build.gradle.*;
import com.android.build.gradle.api.BaseVariant;
import com.android.build.gradle.api.SourceKind;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.dsl.DependencyFactory;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.plugins.PluginContainer;
import org.gradle.api.plugins.jvm.internal.JvmPluginServices;
import org.gradle.api.plugins.scala.ScalaBasePlugin;
import org.gradle.api.plugins.scala.ScalaPluginExtension;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.TaskContainer;
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
        var androidExt = (CommonExtension) extensions.getByName("android");
        var scalaPluginExt = extensions.getByType(ScalaPluginExtension.class);
        configureScalaSourceSet(dependencyFactory, project, androidExt, scalaPluginExt);
        project.afterEvaluate(proj -> {
            ensureScalaVersionSpecified(scalaPluginExt);
            listVariants(androidExt).forEach(variant -> processVariant(variant, proj));
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

    @SuppressWarnings("deprecation")
    public static Collection<BaseVariant> listVariants(CommonExtension androidExtension) {
        Collection<BaseVariant> variants = new ArrayList<>();
        if (androidExtension instanceof AppExtension) {
            variants.addAll(((AppExtension) androidExtension).getApplicationVariants());
        }
        if (androidExtension instanceof LibraryExtension) {
            variants.addAll(((LibraryExtension) androidExtension).getLibraryVariants());
        }
        if (androidExtension instanceof TestExtension) {
            variants.addAll(((TestExtension) androidExtension).getApplicationVariants());
        }
        if (androidExtension instanceof TestedExtension) {
            variants.addAll(((TestedExtension) androidExtension).getTestVariants());
            variants.addAll(((TestedExtension) androidExtension).getUnitTestVariants());
        }
        return variants;
    }

    @SuppressWarnings("deprecation")
    public static void processVariant(BaseVariant variant, Project project) {
        String variantName = variant.getName();
        String intermediatePath = "intermediates/scala/" + variantName;
        JavaCompile javaTask = variant.getJavaCompileProvider().get();
        TaskContainer tasks = project.getTasks();
        var javaClasspath = javaTask.getClasspath();
        String scalaTaskName = javaTask.getName().replace("Java", "Scala");
        ScalaCompile scalaTask = tasks.create(scalaTaskName, ScalaCompile.class);
        var buildDir = project.getLayout().getBuildDirectory();
        var scalaOutDir = buildDir.dir(intermediatePath + "/classes");
        var configurations = project.getConfigurations();
        scalaTask.getDestinationDirectory().set(scalaOutDir);
        // See https://docs.gradle.org/9.0.0/userguide/scala_plugin.html#sec:scala_version and ScalaBasePlugin.java for the details about "scalaToolchainRuntimeClasspath"
        scalaTask.setScalaClasspath(configurations.getByName("scalaToolchainRuntimeClasspath"));
        var preJavaClasspathKey = variant.registerPreJavacGeneratedBytecode(project.files(scalaOutDir));
        var androidComponents = project.getExtensions().getByType(AndroidComponentsExtension.class);
        ConfigurableFileCollection scalaClasspath = project.getObjects().fileCollection()
                .from(javaClasspath)
                .from(variant.getCompileClasspath(preJavaClasspathKey))
                .from(androidComponents.getSdkComponents().getBootClasspath());
        scalaTask.setClasspath(scalaClasspath);
        javaTask.getDependsOn().forEach(scalaTask::dependsOn);

        ConfigurableFileCollection scalaSrc = project.files(variant.getSourceFolders(SourceKind.JAVA));
        variant.getSourceSets().forEach(provider ->
            provider.getJavaDirectories().forEach(scalaSrc::from)
        );
        scalaTask.setSource(scalaSrc);
        javaTask.setSource(project.getObjects().fileCollection()); // set empty source

        var annotationProcessorPath = javaTask.getOptions().getAnnotationProcessorPath();
        scalaTask.getOptions().setAnnotationProcessorPath(annotationProcessorPath);
        var incrementalOptions = scalaTask.getScalaCompileOptions().getIncrementalOptions();
        incrementalOptions.getAnalysisFile().set(
                buildDir.file(intermediatePath + "/incremental.analysis"));
        incrementalOptions.getClassfileBackupDir().set(
                buildDir.file(intermediatePath + "/classfile.bak"));

        javaTask.dependsOn(scalaTask);

        // Workaround to resolve the IntelliJ Scala IDE plugin not recognizing R.jar
        // https://github.com/onsqcorp/scala-android-plugin/issues/2#issuecomment-2394861477
        String compileOnlyConfigName = variantName + "CompileOnly";
        Optional.ofNullable(configurations.findByName(compileOnlyConfigName)).map( c ->
            project.getDependencies().add(compileOnlyConfigName,
                    project.fileTree(buildDir).include("**/" + variantName + "/**/R.jar"))
        );
        // Prevent error from implicit dependency (AGP 8.0 or later)
        // https://docs.gradle.org/8.1.1/userguide/validation_problems.html#implicit_dependency
        variant.getProcessJavaResourcesProvider().get().mustRunAfter(scalaTask);
        // Strangely enough, 1) the error does not always happen without the code above.
        // 2) scalaTask.mustRunAfter(variant.getProcessJavaResourcesProvider()) also mutes the error. Why?
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
}
