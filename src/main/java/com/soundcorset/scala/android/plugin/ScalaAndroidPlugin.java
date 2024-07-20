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
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.ConfigurableFileTree;
import org.gradle.api.file.SourceDirectorySet;
import org.gradle.api.internal.tasks.DefaultScalaSourceSet;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.plugins.ExtensionAware;
import org.gradle.api.plugins.PluginContainer;
import org.gradle.api.plugins.jvm.internal.JvmPluginServices;
import org.gradle.api.plugins.scala.ScalaBasePlugin;
import org.gradle.api.services.BuildServiceRegistry;
import org.gradle.api.tasks.ScalaRuntime;
import org.gradle.api.tasks.ScalaSourceDirectorySet;
import org.gradle.api.tasks.TaskContainer;
import org.gradle.api.tasks.scala.ScalaCompile;
import org.gradle.language.scala.tasks.KeepAliveMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.io.File;
import java.util.*;
import java.util.stream.Collectors;


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
        androidExt.getSourceSets().all(sourceSet -> {
            if (sourceSet instanceof ExtensionAware) {
                var ext = ((ExtensionAware) sourceSet).getExtensions();
                String sourceSetName = sourceSet.getName();
                File sourceSetPath = project.file("src/" + sourceSetName + "/scala");

                if (!sourceSetPath.exists()) {
                    LOGGER.debug("SourceSet path does not exists for {} {}", sourceSet.getName(), sourceSetPath);
                    return;
                }

                sourceSet.getJava().srcDir(sourceSetPath);
                var scalaSourceSet = new DefaultScalaSourceSet(sourceSetName, project.getObjects()) {};
                var scalaDirectorySet = scalaSourceSet.getScala();
                scalaDirectorySet.srcDir(sourceSetPath);
                ext.add(ScalaSourceDirectorySet.class, "ScalaSourceDirectorySet", scalaDirectorySet);

                LOGGER.debug("Created scala sourceDirectorySet at {}", scalaDirectorySet.getSrcDirs());
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

        project.afterEvaluate(p -> {
            listVariants(androidExt).forEach(variant -> processVariant(variant, project, scalaRuntime, androidExt));
            TaskContainer tasks = project.getTasks();
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
        var javaClasspath = javaTask.getClasspath();
        var taskName = javaTask.getName().replace("Java", "Scala");
        var scalaTask = tasks.create(taskName, ScalaCompile.class);
        var scalaOutDir = project.getLayout().getBuildDirectory().dir("tmp/scala/" + scalaTask.getName()+"/classes");
        scalaTask.getDestinationDirectory().set(scalaOutDir);
        scalaTask.setClasspath(javaClasspath);
        scalaTask.setScalaClasspath(scalaRuntime.inferScalaClasspath(javaClasspath));
        variant.registerPreJavacGeneratedBytecode(project.files(scalaOutDir));
        for(Object t: javaTask.getDependsOn()) {
            scalaTask.dependsOn(t);
        }
        scalaTask.getScalaCompileOptions().getKeepAliveMode().set(KeepAliveMode.SESSION);

        var zinc = project.getConfigurations().getByName("zinc");
        var plugins = project.getConfigurations().getByName(ScalaBasePlugin.SCALA_COMPILER_PLUGINS_CONFIGURATION_NAME);

        scalaTask.setScalaCompilerPlugins(plugins.getAsFileTree());
        scalaTask.setZincClasspath(zinc.getAsFileTree());

        LOGGER.debug("scala sources for {}: {}", variantName, scalaTask.getSource().getFiles());

        Object[] additionalSourceFiles = variant.getSourceFolders(SourceKind.JAVA)
                .stream()
                .map(ConfigurableFileTree::getDir)
                .toArray();
        ConfigurableFileCollection additionalSrc = project.files(additionalSourceFiles);

        LOGGER.debug("additional source files found at {}", additionalSourceFiles);

        variant.getSourceSets().forEach(provider -> {
            if(provider instanceof ExtensionAware) {
                var ext = ((ExtensionAware) provider).getExtensions();
                try {
                    SourceDirectorySet srcDirSet = ext.getByType(ScalaSourceDirectorySet.class);
                    scalaTask.setSource(srcDirSet.plus(additionalSrc));
                } catch(UnknownDomainObjectException u) {
                    // pass through
                }
            }
        });
        javaTask.setSource(project.getObjects().fileCollection()); // set empty source

        if (scalaTask.getSource().isEmpty()) {
            LOGGER.warn("no scala sources found for {} removing scala task", variantName);
            scalaTask.setEnabled(false);
            return;
        }

        scalaTask.doFirst(task -> {
            var runtimeJars =
                project.getLayout().files(androidExtension.getBootClasspath().toArray())
                    .plus(javaTask.getClasspath());

            scalaTask.setClasspath(runtimeJars);
            scalaTask.getOptions().setAnnotationProcessorPath(javaTask.getOptions().getAnnotationProcessorPath());

            var incrementalOptions = scalaTask.getScalaCompileOptions().getIncrementalOptions();
            incrementalOptions.getAnalysisFile().set(
                project.getLayout().getBuildDirectory().file("tmp/scala/compilerAnalysis/" + scalaTask.getName() + ".analysis")
            );

            incrementalOptions.getClassfileBackupDir().set(
                project.getLayout().getBuildDirectory().file("tmp/scala/classfileBackup/" + scalaTask.getName() + ".bak")
            );

            LOGGER.debug("Java annotationProcessorPath {}", javaTask.getOptions().getAnnotationProcessorPath());
            LOGGER.debug("Scala compiler args {}", scalaTask.getOptions().getCompilerArgs());
        });
        LOGGER.debug("Scala classpath: {}", scalaTask.getClasspath());

        javaTask.dependsOn(scalaTask);

        // Prevent error from implicit dependency (AGP 8.0 or above)
        // https://docs.gradle.org/8.1.1/userguide/validation_problems.html#implicit_dependency
        String capitalizedName = variantName.substring(0,1).toUpperCase() + variantName.substring(1);
        dependsOnIfPresent(tasks, "process" + capitalizedName + "JavaRes", scalaTask);
        dependsOnIfPresent(tasks, "dexBuilder" + capitalizedName, scalaTask);
        dependsOnIfPresent(tasks, "transform" + capitalizedName + "ClassesWithAsm", scalaTask);
        dependsOnIfPresent(tasks, "lintVitalAnalyze" + capitalizedName, scalaTask);
        dependsOnIfPresent(tasks, "bundle" + capitalizedName + "ClassesToCompileJar", scalaTask);
        dependsOnIfPresent(tasks, "generate" + capitalizedName + "LintVitalReportModel", scalaTask);
        dependsOnIfPresent(tasks, "expand" + capitalizedName + "ArtProfileWildcards", scalaTask);
    }

}
