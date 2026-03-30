package io.github.chains_project.maven_lockfile.resolvers;

import io.github.chains_project.maven_lockfile.reporting.PluginLogManager;
import java.util.ArrayList;
import java.util.List;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Plugin;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;

/**
 * Discovers annotation processor artifacts declared under
 * {@code maven-compiler-plugin}'s {@code <annotationProcessorPaths>} configuration.
 *
 * <p>Maven resolves annotation processor paths separately from the project's regular
 * dependency tree — they do not appear in {@code project.getArtifacts()} and would
 * be missing from the lockfile without this resolver.
 *
 * <p>Example configuration captured by this resolver:
 * <pre>{@code
 * <plugin>
 *   <artifactId>maven-compiler-plugin</artifactId>
 *   <configuration>
 *     <annotationProcessorPaths>
 *       <path>
 *         <groupId>com.google.errorprone</groupId>
 *         <artifactId>error_prone_core</artifactId>
 *         <version>2.36.0</version>
 *       </path>
 *       <path>
 *         <groupId>com.uber.nullaway</groupId>
 *         <artifactId>nullaway</artifactId>
 *         <version>0.11.3</version>
 *       </path>
 *     </annotationProcessorPaths>
 *   </configuration>
 * </plugin>
 * }</pre>
 *
 * <p>Each {@code <path>} is injected as a dependency of {@code maven-compiler-plugin},
 * causing the existing plugin dependency resolution to capture its full transitive closure.
 */
public class MavenCompilerPluginResolver extends SpecialPluginResolver {

    private static final String PLUGIN_GROUP_ID = "org.apache.maven.plugins";
    private static final String PLUGIN_ARTIFACT_ID = "maven-compiler-plugin";
    private static final String PLUGIN_KEY = PLUGIN_GROUP_ID + ":" + PLUGIN_ARTIFACT_ID;

    public MavenCompilerPluginResolver() {}

    @Override
    public boolean isApplicable(MavenProject project) {
        return project.getBuildPlugins().stream()
                .anyMatch(p -> PLUGIN_ARTIFACT_ID.equals(p.getArtifactId())
                        && hasAnnotationProcessorPaths(p));
    }

    @Override
    public String getDisplayName() {
        return "maven-compiler-plugin (annotationProcessorPaths)";
    }

    @Override
    public boolean forceDependencyPopulation() {
        // Annotation processors run in a separate classloader — Maven's main conflict
        // resolution does not apply. Every artifact (including "duplicates") must be
        // present in the local repo for a hermetic offline build.
        return true;
    }

    @Override
    public DiscoveryResult discover(MavenProject project, MavenSession session) {
        List<Dependency> deps = discoverAnnotationProcessors(project);
        if (deps.isEmpty()) {
            return DiscoveryResult.empty();
        }
        return DiscoveryResult.ofPluginDependencies(PLUGIN_KEY, deps);
    }

    private static boolean hasAnnotationProcessorPaths(Plugin plugin) {
        if (!(plugin.getConfiguration() instanceof Xpp3Dom)) return false;
        Xpp3Dom config = (Xpp3Dom) plugin.getConfiguration();
        return config.getChild("annotationProcessorPaths") != null;
    }

    private static List<Dependency> discoverAnnotationProcessors(MavenProject project) {
        List<Dependency> deps = new ArrayList<>();

        Plugin plugin = project.getBuildPlugins().stream()
                .filter(p -> PLUGIN_ARTIFACT_ID.equals(p.getArtifactId()))
                .findFirst()
                .orElse(null);

        if (plugin == null || !(plugin.getConfiguration() instanceof Xpp3Dom)) {
            return deps;
        }

        Xpp3Dom config = (Xpp3Dom) plugin.getConfiguration();
        Xpp3Dom processorPaths = config.getChild("annotationProcessorPaths");
        if (processorPaths == null) {
            return deps;
        }

        for (Xpp3Dom path : processorPaths.getChildren("path")) {
            String groupId = childValue(path, "groupId", project);
            String artifactId = childValue(path, "artifactId", project);
            String version = childValue(path, "version", project);

            if (groupId == null || artifactId == null || version == null) {
                PluginLogManager.getLog().warn(
                        "MavenCompilerPlugin: skipping annotationProcessorPath entry"
                                + " with missing groupId/artifactId/version");
                continue;
            }

            if (version.startsWith("${")) {
                PluginLogManager.getLog().warn(String.format(
                        "MavenCompilerPlugin: could not resolve version for %s:%s (%s) — skipping",
                        groupId, artifactId, version));
                continue;
            }

            Dependency dep = new Dependency();
            dep.setGroupId(groupId);
            dep.setArtifactId(artifactId);
            dep.setVersion(version);
            deps.add(dep);

            PluginLogManager.getLog().info(String.format(
                    "MavenCompilerPlugin: discovered annotationProcessorPath %s:%s:%s",
                    groupId, artifactId, version));
        }

        return deps;
    }

    private static String childValue(Xpp3Dom parent, String childName, MavenProject project) {
        Xpp3Dom child = parent.getChild(childName);
        if (child == null || child.getValue() == null) return null;
        String value = child.getValue().trim();
        if (value.startsWith("${") && value.endsWith("}")) {
            String key = value.substring(2, value.length() - 1);
            String resolved = project.getProperties().getProperty(key);
            if (resolved != null) return resolved.trim();
            return value; // return unresolved — caller will warn
        }
        return value;
    }
}
