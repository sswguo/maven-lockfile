package io.github.chains_project.maven_lockfile.resolvers;

import io.github.chains_project.maven_lockfile.reporting.PluginLogManager;
import java.util.ArrayList;
import java.util.List;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Plugin;
import org.apache.maven.project.MavenProject;

/**
 * Discovers the JUnit Platform provider ({@code surefire-junit-platform}) that
 * {@code maven-surefire-plugin} loads dynamically at runtime when JUnit 5 is detected on the
 * test classpath.
 *
 * <p>When surefire finds {@code junit-jupiter} (or any {@code junit-platform-*} artifact) among
 * the test dependencies, it resolves {@code org.apache.maven.surefire:surefire-junit-platform}
 * at the same version as the plugin itself — along with its transitive dependencies such as
 * {@code junit-platform-launcher} and {@code junit-platform-engine}. None of these appear in
 * the project's declared dependencies, so they would be missing from the lockfile without this
 * resolver.
 *
 * <p>The resolver injects {@code surefire-junit-platform} as a dependency of
 * {@code maven-surefire-plugin}, causing the existing plugin dependency resolution to capture
 * its full transitive closure.
 */
public class SurefirePluginResolver extends SpecialPluginResolver {

    private static final String SUREFIRE_GROUP_ID = "org.apache.maven.surefire";
    private static final String SUREFIRE_ARTIFACT_ID = "maven-surefire-plugin";
    private static final String SUREFIRE_PLATFORM_PROVIDER = "surefire-junit-platform";
    private static final String SUREFIRE_PLUGIN_KEY =
            "org.apache.maven.plugins:" + SUREFIRE_ARTIFACT_ID;

    public SurefirePluginResolver() {}

    @Override
    public boolean isApplicable(MavenProject project) {
        return hasSurefirePlugin(project) && hasJUnit5OnTestClasspath(project);
    }

    @Override
    public String getDisplayName() {
        return "maven-surefire-plugin (JUnit Platform)";
    }

    @Override
    public DiscoveryResult discover(MavenProject project, MavenSession session) {
        String surefireVersion = getSurefireVersion(project);
        if (surefireVersion == null) {
            PluginLogManager.getLog().warn(
                    "SurefirePluginResolver: could not determine surefire version — skipping");
            return DiscoveryResult.empty();
        }

        // Resolve any property placeholder (e.g. ${maven-surefire-plugin.version})
        surefireVersion = resolveProperty(surefireVersion, project);
        if (surefireVersion == null || surefireVersion.startsWith("${")) {
            PluginLogManager.getLog().warn(
                    "SurefirePluginResolver: could not resolve surefire version — skipping");
            return DiscoveryResult.empty();
        }

        Dependency platformProvider = new Dependency();
        platformProvider.setGroupId(SUREFIRE_GROUP_ID);
        platformProvider.setArtifactId(SUREFIRE_PLATFORM_PROVIDER);
        platformProvider.setVersion(surefireVersion);

        List<Dependency> deps = new ArrayList<>();
        deps.add(platformProvider);

        PluginLogManager.getLog().info(String.format(
                "SurefirePluginResolver: injecting %s:%s:%s into %s",
                SUREFIRE_GROUP_ID, SUREFIRE_PLATFORM_PROVIDER, surefireVersion,
                SUREFIRE_PLUGIN_KEY));

        return DiscoveryResult.ofPluginDependencies(SUREFIRE_PLUGIN_KEY, deps);
    }

    private static boolean hasSurefirePlugin(MavenProject project) {
        return project.getBuildPlugins().stream()
                .anyMatch(p -> SUREFIRE_ARTIFACT_ID.equals(p.getArtifactId()));
    }

    /**
     * Returns {@code true} if any JUnit 5 artifact is present on the test classpath.
     * Surefire uses this same detection to decide whether to load the JUnit Platform provider.
     */
    private static boolean hasJUnit5OnTestClasspath(MavenProject project) {
        return project.getArtifacts().stream().anyMatch(a ->
                (a.getGroupId().equals("org.junit.jupiter")
                        || a.getGroupId().equals("org.junit.platform"))
                        && "test".equals(a.getScope()));
    }

    private static String getSurefireVersion(MavenProject project) {
        return project.getBuildPlugins().stream()
                .filter(p -> SUREFIRE_ARTIFACT_ID.equals(p.getArtifactId()))
                .map(Plugin::getVersion)
                .findFirst()
                .orElse(null);
    }

    private static String resolveProperty(String value, MavenProject project) {
        if (value != null && value.startsWith("${") && value.endsWith("}")) {
            String key = value.substring(2, value.length() - 1);
            String resolved = project.getProperties().getProperty(key);
            return resolved != null ? resolved.trim() : value;
        }
        return value;
    }
}
