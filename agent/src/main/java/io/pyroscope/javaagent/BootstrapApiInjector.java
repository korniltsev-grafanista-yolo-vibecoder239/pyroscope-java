package io.pyroscope.javaagent;

import io.pyroscope.javaagent.api.Logger;
import io.pyroscope.javaagent.impl.DefaultLogger;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.jar.JarFile;

/**
 * Injects the shared bootstrap-api classes (ProfilerApi, ProfilerApiHolder, ProfilerScopedContext)
 * into the bootstrap classloader at agent startup.
 *
 * <p>This ensures that both the agent classloader and any application classloader resolve the same
 * ProfilerApiHolder class with the same static fields, enabling cross-classloader communication
 * (e.g., with the OTel extension).
 *
 * <p>Primary approach: resolve the agent JAR on disk via {@code getCodeSource().getLocation()}
 * and pass it directly to {@code appendToBootstrapClassLoaderSearch}. This avoids creating
 * temporary files. The JVM only reads the file path from the JarFile object, so the entire
 * agent JAR is added to the bootstrap search path. This is safe because all classes in the
 * shadow JAR are under {@code io.pyroscope.*} (third-party code is relocated to
 * {@code io.pyroscope.vendor.*}), so there is no risk of shadowing JDK or application classes.
 *
 * <p>Fallback: if the agent JAR path cannot be resolved (e.g., custom classloader, security
 * manager), the embedded {@code pyroscope-bootstrap.jar.bin} resource is extracted to a temp
 * file and used instead.
 *
 * <p>There are two places that perform this injection:
 * <ul>
 *   <li>Here, in the agent premain ({@code PyroscopeAgent.premain})</li>
 *   <li>In the otel-profiling-java extension ({@code io.otel.pyroscope.BootstrapApiInjector})</li>
 * </ul>
 * Whichever runs first injects the classes; the second call is a no-op since the classes
 * are already on the bootstrap classloader.
 */
class BootstrapApiInjector {

    private static final String RESOURCE_NAME = "/pyroscope-bootstrap.jar.bin";

    private static final String[] BOOTSTRAP_CLASSES = {
        "io/pyroscope/javaagent/api/ProfilerApi.class",
        "io/pyroscope/javaagent/api/ProfilerApiHolder.class",
        "io/pyroscope/javaagent/api/ProfilerScopedContext.class"
    };

    static void inject(Instrumentation instrumentation) {
        try {
            File agentJar = getAgentJarFile();
            if (agentJar != null) {
                instrumentation.appendToBootstrapClassLoaderSearch(new JarFile(agentJar));
                DefaultLogger.PRECONFIG_LOGGER.log(Logger.Level.DEBUG,
                    "BootstrapApiInjector: Injected from agent JAR: %s", agentJar);
                return;
            }
            injectFromResource(instrumentation);
        } catch (IOException e) {
            DefaultLogger.PRECONFIG_LOGGER.log(Logger.Level.ERROR,
                "BootstrapApiInjector: Failed to inject bootstrap API: %s", e);
        }
    }

    private static File getAgentJarFile() {
        try {
            URL location = BootstrapApiInjector.class
                .getProtectionDomain().getCodeSource().getLocation();
            if (location == null || !"file".equals(location.getProtocol())) {
                return null;
            }
            File file = new File(location.toURI());
            if (!file.isFile()) {
                return null;
            }
            try (JarFile jar = new JarFile(file)) {
                for (String cls : BOOTSTRAP_CLASSES) {
                    if (jar.getJarEntry(cls) == null) {
                        DefaultLogger.PRECONFIG_LOGGER.log(Logger.Level.DEBUG,
                            "BootstrapApiInjector: Agent JAR missing bootstrap class: %s", cls);
                        return null;
                    }
                }
            }
            return file;
        } catch (SecurityException | URISyntaxException | IOException e) {
            DefaultLogger.PRECONFIG_LOGGER.log(Logger.Level.DEBUG,
                "BootstrapApiInjector: Cannot resolve agent JAR path: %s", e);
            return null;
        }
    }

    private static void injectFromResource(Instrumentation instrumentation) throws IOException {
        try (InputStream is = BootstrapApiInjector.class.getResourceAsStream(RESOURCE_NAME)) {
            if (is == null) {
                DefaultLogger.PRECONFIG_LOGGER.log(Logger.Level.WARN,
                    "BootstrapApiInjector: %s not found in resources, skipping bootstrap injection",
                    RESOURCE_NAME);
                return;
            }
            Path tempJar = Files.createTempFile("pyroscope-bootstrap-", ".jar");
            tempJar.toFile().deleteOnExit();
            Files.copy(is, tempJar, StandardCopyOption.REPLACE_EXISTING);

            instrumentation.appendToBootstrapClassLoaderSearch(new JarFile(tempJar.toFile()));
            DefaultLogger.PRECONFIG_LOGGER.log(Logger.Level.DEBUG,
                "BootstrapApiInjector: Injected API classes from temp file (fallback)");
        }
    }
}
