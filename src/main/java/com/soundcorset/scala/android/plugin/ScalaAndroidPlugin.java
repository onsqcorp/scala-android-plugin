package com.soundcorset.scala.android.plugin;

import com.android.build.gradle.*;
import com.android.build.gradle.api.BaseVariant;
import com.android.build.gradle.api.SourceKind;
import com.android.build.gradle.internal.services.BuildServicesKt;
import com.android.build.gradle.options.BooleanOption;
import com.android.build.gradle.options.ProjectOptionService;
import com.android.build.gradle.options.ProjectOptions;
import com.android.build.gradle.options.StringOption;
import org.gradle.api.*;
import org.gradle.api.artifacts.UnknownConfigurationException;
import org.gradle.api.artifacts.dsl.DependencyFactory;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.plugins.PluginContainer;
import org.gradle.api.plugins.jvm.internal.JvmPluginServices;
import org.gradle.api.plugins.scala.ScalaBasePlugin;
import org.gradle.api.services.BuildServiceRegistry;
import org.gradle.api.tasks.ScalaRuntime;
import org.gradle.api.tasks.TaskContainer;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.api.tasks.scala.ScalaCompile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.io.File;
import java.util.*;


public class ScalaAndroidPlugin extends ScalaBasePlugin {
    private static final Logger LOGGER = LoggerFactory.getLogger(ScalaAndroidPlugin.class);

    @Inject
    public ScalaAndroidPlugin(ObjectFactory objectFactory, JvmPluginServices jvmPluginServices, DependencyFactory dependencyFactory) {
        super(objectFactory, jvmPluginServices, dependencyFactory);
    }

    public void apply(Project project) {
        super.apply(project);
        ensureAndroidPlugin(project.getPlugins());
        checkJetifier(project);
        var androidExt = (BaseExtension) project.getExtensions().getByName("android");
        addScalaSourceSet(project, androidExt);
        project.afterEvaluate(proj ->
            listVariants(androidExt).forEach(variant -> processVariant(variant, proj, androidExt))
        );
    }

    private static void addScalaSourceSet(Project project, BaseExtension androidExt) {
        // The function `all()` take account all the future additions for the source sets
        androidExt.getSourceSets().all(sourceSet -> {
            String sourceSetName = sourceSet.getName();
            File sourceSetPath = project.file("src/" + sourceSetName + "/scala");
            if (sourceSetPath.exists()) {
                sourceSet.getJava().srcDir(sourceSetPath);
            }
        });
    }

    private static void checkJetifier(Project project) {
        BuildServiceRegistry sharedServices = project.getGradle().getSharedServices();
        ProjectOptionService optionService = BuildServicesKt.getBuildService(sharedServices, ProjectOptionService.class).get();
        ProjectOptions options = optionService.getProjectOptions();
        String jetifierIgnoreList = options.get(StringOption.JETIFIER_IGNORE_LIST); // Alternatively, project.property("android.jetifier.ignorelist")
        boolean enableJetifier = options.get(BooleanOption.ENABLE_JETIFIER); // Alternatively, project.property("android.enableJetifier")
        if(enableJetifier && (jetifierIgnoreList == null || !jetifierIgnoreList.contains("scala"))) {
            throw new GradleException("If jetifier is enabled, \"android.jetifier.ignorelist=scala\" should be defined in gradle.properties.");
        }
    }

    private static void dependsOnIfPresent(TaskContainer tasks, String taskName, Task scalaTask) {
        Optional.ofNullable(tasks.findByName(taskName))
                .map(t -> t.dependsOn(scalaTask));
    }

    // Also available in org.jetbrains.kotlin.gradle.utils.androidPluginIds
    private static final List<String> ANDROID_PLUGIN_NAMES = Arrays.asList(
            "com.android.application", "com.android.library", "com.android.dynamic-feature", "com.android.test"
    );

    private static void ensureAndroidPlugin(PluginContainer plugins) {
        var plugin = ANDROID_PLUGIN_NAMES.stream()
            .map(plugins::findPlugin)
            .findFirst().orElse(null);
        if (plugin == null) {
            throw new GradleException("You must apply the Android plugin or the Android library plugin before using the scala-android plugin");
        }
    }

    @SuppressWarnings("deprecation")
    private static Collection<? extends BaseVariant> listVariants(BaseExtension androidExtension) {
        List<BaseVariant> variants = new ArrayList<>();
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
    private static void processVariant(
            BaseVariant variant,
            Project project,
            BaseExtension androidExtension
    ) {
        String variantName = variant.getName();
        String intermediatePath = "intermediates/scala/" + variantName;
        JavaCompile javaTask = variant.getJavaCompileProvider().getOrNull();
        if (javaTask == null) {
            LOGGER.warn("No java compile provider for {}", variantName);
            return;
        }
        TaskContainer tasks = project.getTasks();
        var javaClasspath = javaTask.getClasspath();
        String taskName = javaTask.getName().replace("Java", "Scala");
        ScalaCompile scalaTask = tasks.create(taskName, ScalaCompile.class);
        var buildDir = project.getLayout().getBuildDirectory();
        var scalaOutDir = buildDir.dir(intermediatePath + "/classes");
        scalaTask.getDestinationDirectory().set(scalaOutDir);
        ScalaRuntime scalaRuntime = project.getExtensions().getByType(ScalaRuntime.class);
        scalaTask.setScalaClasspath(scalaRuntime.inferScalaClasspath(javaClasspath));
        var preJavaClasspathKey = variant.registerPreJavacGeneratedBytecode(project.files(scalaOutDir));
        ConfigurableFileCollection scalaClasspath = project.getObjects().fileCollection()
                .from(javaClasspath)
                .from(variant.getCompileClasspath(preJavaClasspathKey))
                .from(androidExtension.getBootClasspath().toArray());
        scalaTask.setClasspath(scalaClasspath);
        for(Object t: javaTask.getDependsOn()) {
            scalaTask.dependsOn(t);
        }

        ConfigurableFileCollection additionalSrc = project.files(variant.getSourceFolders(SourceKind.JAVA));
        variant.getSourceSets().forEach(provider ->
            provider.getJavaDirectories().forEach(dir -> {
                if(dir.exists()) {
                    additionalSrc.from(dir);
                }
            })
        );
        scalaTask.setSource(additionalSrc);
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
        if(project.getConfigurations().getNames().contains(compileOnlyConfigName)) {
            project.getDependencies().add(compileOnlyConfigName,
                    project.fileTree(buildDir).include("**/" + variantName + "/**/R.jar"));
        }
        // Prevent error from implicit dependency (AGP 8.0 or above)
        // https://docs.gradle.org/8.1.1/userguide/validation_problems.html#implicit_dependency
        String capitalizedName = variantName.substring(0,1).toUpperCase() + variantName.substring(1);
        dependsOnIfPresent(tasks, "process" + capitalizedName + "JavaRes", scalaTask);
    }

}
