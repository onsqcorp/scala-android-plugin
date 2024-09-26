package com.soundcorset.scala.android.plugin;

import com.android.build.gradle.*;
import com.android.build.gradle.api.BaseVariant;
import com.android.build.gradle.api.SourceKind;
import com.android.build.gradle.internal.plugins.BasePlugin;
import com.android.build.gradle.internal.services.BuildServicesKt;
import com.android.build.gradle.options.BooleanOption;
import com.android.build.gradle.options.ProjectOptionService;
import com.android.build.gradle.options.ProjectOptions;
import com.android.build.gradle.options.StringOption;
import org.gradle.api.*;
import org.gradle.api.artifacts.ConfigurationContainer;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.ProjectLayout;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.plugins.PluginContainer;
import org.gradle.api.plugins.jvm.internal.JvmPluginServices;
import org.gradle.api.plugins.scala.ScalaBasePlugin;
import org.gradle.api.services.BuildServiceRegistry;
import org.gradle.api.tasks.ScalaRuntime;
import org.gradle.api.tasks.TaskContainer;
import org.gradle.api.tasks.scala.ScalaCompile;
import org.gradle.language.scala.tasks.KeepAliveMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.io.File;
import java.util.*;


public class ScalaAndroidPlugin extends ScalaBasePlugin {
    private static final Logger LOGGER = LoggerFactory.getLogger(ScalaAndroidPlugin.class);

    @Inject
    public ScalaAndroidPlugin(ObjectFactory objectFactory, JvmPluginServices jvmPluginServices) {
        super(objectFactory, jvmPluginServices);
    }

    public void apply(Project project) {
        super.apply(project);
        ScalaRuntime scalaRuntime = project.getExtensions().getByType(ScalaRuntime.class);
        ensureAndroidPlugin(project.getPlugins());
        var androidExt = (BaseExtension) project.getExtensions().getByName("android");
        // The function `all()` take account all the future additions for the source sets
        androidExt.getSourceSets().all(sourceSet -> {
            String sourceSetName = sourceSet.getName();
            File sourceSetPath = project.file("src/" + sourceSetName + "/scala");
            if (sourceSetPath.exists()) {
                sourceSet.getJava().srcDir(sourceSetPath);
            } else {
                LOGGER.debug("SourceSet path does not exists for {} {}", sourceSet.getName(), sourceSetPath);
            }
        });

        BuildServiceRegistry sharedServices = project.getGradle().getSharedServices();
        ProjectOptionService optionService = BuildServicesKt.getBuildService(sharedServices, ProjectOptionService.class).get();
        ProjectOptions options = optionService.getProjectOptions();
        String jetifierIgnoreList = options.get(StringOption.JETIFIER_IGNORE_LIST); // Alternatively, project.property("android.jetifier.ignorelist")
        boolean enableJetifier = options.get(BooleanOption.ENABLE_JETIFIER); // Alternatively, project.property("android.enableJetifier")
        if(enableJetifier && (jetifierIgnoreList == null || !jetifierIgnoreList.contains("scala"))) {
            throw new GradleException("If jetifier is enabled, \"android.jetifier.ignorelist=scala\" should be defined in gradle.properties.");
        }

        project.afterEvaluate(proj -> {
            listVariants(androidExt).forEach(variant -> processVariant(variant, proj, scalaRuntime, androidExt));
            TaskContainer tasks = proj.getTasks();
            dependsOnIfPresent(tasks, "compileDebugUnitTestScalaWithScalac", "compileDebugScalaWithScalac");
            dependsOnIfPresent(tasks, "compileReleaseUnitTestScalaWithScalac", "compileReleaseScalaWithScalac");
        });
    }

    private static void dependsOnIfPresent(TaskContainer tasks, String taskName1, String taskName2) {
        dependsOnIfPresent(tasks, taskName1, tasks.findByPath(taskName2));
    }
    private static void dependsOnIfPresent(TaskContainer tasks, String taskName, Task scalaTask) {
        Optional.ofNullable(tasks.findByName(taskName))
                .map(t -> t.dependsOn(scalaTask));
    }

    private static final List<String> ANDROID_PLUGIN_NAMES = Arrays.asList(
        "com.android.internal.application", "com.android.internal.library", "com.android.internal.test"
    );

    private static void ensureAndroidPlugin(PluginContainer plugins) {
        var plugin = ANDROID_PLUGIN_NAMES.stream()
            .map(plugins::findPlugin)
            .findFirst().orElse(null);

        if (!(plugin instanceof BasePlugin)) {
            throw new GradleException("You must apply the Android plugin or the Android library plugin before using the scala-android plugin");
        }
    }

    @SuppressWarnings("deprecation")
    private static Collection<? extends BaseVariant> listVariants(BaseExtension androidExtension) {
        if (androidExtension instanceof AppExtension) {
            return ((AppExtension) androidExtension).getApplicationVariants();
        }
        if (androidExtension instanceof LibraryExtension) {
            return ((LibraryExtension) androidExtension).getLibraryVariants();
        }
        if (androidExtension instanceof TestExtension) {
            return ((TestExtension) androidExtension).getApplicationVariants();
        }
        if (androidExtension instanceof TestedExtension) {
            List<BaseVariant> variants = new ArrayList<>(((TestedExtension) androidExtension).getTestVariants());
            variants.addAll(((TestedExtension) androidExtension).getUnitTestVariants());
            return variants;
        }
        throw new RuntimeException("There is no android extension");
    }

    @SuppressWarnings("deprecation")
    private static void processVariant(
            BaseVariant variant,
            Project project,
            ScalaRuntime scalaRuntime,
            BaseExtension androidExtension
    ) {
        var variantName = variant.getName();
        var javaTask = variant.getJavaCompileProvider().getOrNull();
        if (javaTask == null) {
            LOGGER.warn("No java compile provider for {}", variantName);
            return;
        }
        TaskContainer tasks = project.getTasks();
        ProjectLayout layout = project.getLayout();
        ConfigurationContainer conf = project.getConfigurations();
        var javaClasspath = javaTask.getClasspath();
        var taskName = javaTask.getName().replace("Java", "Scala");
        var scalaTask = tasks.create(taskName, ScalaCompile.class);
        var scalaOutDir = layout.getBuildDirectory().dir("tmp/scala-classes/" + variantName);
        scalaTask.getDestinationDirectory().set(scalaOutDir);
        scalaTask.setScalaClasspath(scalaRuntime.inferScalaClasspath(javaClasspath));
        var preJavaClasspathKey = variant.registerPreJavacGeneratedBytecode(project.files(scalaOutDir));
        var scalaClasspath = project.getObjects().fileCollection().from(javaClasspath).from(variant.getCompileClasspath(preJavaClasspathKey))
                .from(androidExtension.getBootClasspath().toArray());
        scalaTask.setClasspath(scalaClasspath);
        for(Object t: javaTask.getDependsOn()) {
            scalaTask.dependsOn(t);
        }
        scalaTask.getScalaCompileOptions().getKeepAliveMode().set(KeepAliveMode.SESSION);
        var zinc = conf.getByName("zinc");
        var plugins = conf.getByName(ScalaBasePlugin.SCALA_COMPILER_PLUGINS_CONFIGURATION_NAME);
        scalaTask.setScalaCompilerPlugins(plugins.getAsFileTree());
        scalaTask.setZincClasspath(zinc.getAsFileTree());

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

        var buildDir = layout.getBuildDirectory();
        var annotationProcessorPath = javaTask.getOptions().getAnnotationProcessorPath();
        scalaTask.doFirst(task -> {
            // Consciously reference objects from outside this block. Referencing certain objects,
            // such as project or javaTask, can disable the configuration cache, slowing down build times.
            // https://docs.gradle.org/current/userguide/configuration_cache.html#config_cache:requirements
            scalaTask.getOptions().setAnnotationProcessorPath(annotationProcessorPath);
            var incrementalOptions = scalaTask.getScalaCompileOptions().getIncrementalOptions();
            incrementalOptions.getAnalysisFile().set(
                buildDir.file("tmp/scala/compilerAnalysis/" + scalaTask.getName() + ".analysis")
            );
            incrementalOptions.getClassfileBackupDir().set(
                buildDir.file("tmp/scala/classfileBackup/" + scalaTask.getName() + ".bak")
            );
        });

        javaTask.dependsOn(scalaTask);

        // Prevent error from implicit dependency (AGP 8.0 or above)
        // https://docs.gradle.org/8.1.1/userguide/validation_problems.html#implicit_dependency
        String capitalizedName = variantName.substring(0,1).toUpperCase() + variantName.substring(1);
        dependsOnIfPresent(tasks, "process" + capitalizedName + "JavaRes", scalaTask);
    }

}
