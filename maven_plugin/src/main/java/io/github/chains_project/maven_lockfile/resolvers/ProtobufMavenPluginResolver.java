package io.github.chains_project.maven_lockfile.resolvers;

import io.github.chains_project.maven_lockfile.reporting.PluginLogManager;
import java.util.ArrayList;
import java.util.List;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Plugin;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;

/**
 * Discovers platform-specific binary artifacts required by
 * {@code io.github.ascopes:protobuf-maven-plugin} by reading its plugin configuration.
 *
 * <p>The plugin configuration schema (v5.x) is:
 * <pre>{@code
 * <configuration>
 *   <protoc>3.25.5</protoc>               <!-- protoc version -->
 *   <plugins>
 *     <plugin kind="binary-maven">        <!-- additional native protoc plugins -->
 *       <groupId>io.grpc</groupId>
 *       <artifactId>protoc-gen-grpc-java</artifactId>
 *       <version>1.68.0</version>
 *     </plugin>
 *   </plugins>
 * </configuration>
 * }</pre>
 *
 * <p>From this, two categories of artifacts are discovered:
 * <ol>
 *   <li>{@code com.google.protobuf:protoc:exe:${classifier}:VERSION} — from {@code <protoc>}</li>
 *   <li>{@code groupId:artifactId:exe:${classifier}:version} — from each {@code binary-maven}
 *       plugin entry</li>
 * </ol>
 *
 * <p>The returned specs are in {@code groupId:artifactId:type:version} format (without classifier),
 * ready for {@link PlatformArtifactResolver#resolve}.
 */
public class ProtobufMavenPluginResolver extends SpecialPluginResolver {

    private static final String PLUGIN_GROUP_ID = "io.github.ascopes";
    private static final String PLUGIN_ARTIFACT_ID = "protobuf-maven-plugin";
    private static final String PROTOC_GROUP_ID = "com.google.protobuf";
    private static final String PROTOC_ARTIFACT_ID = "protoc";
    private static final String BINARY_MAVEN_KIND = "binary-maven";

    public ProtobufMavenPluginResolver() {}

    @Override
    public boolean isApplicable(MavenProject project) {
        return project.getBuildPlugins().stream()
                .anyMatch(p -> PLUGIN_GROUP_ID.equals(p.getGroupId())
                        && PLUGIN_ARTIFACT_ID.equals(p.getArtifactId()));
    }

    @Override
    public String getDisplayName() {
        return "protobuf-maven-plugin";
    }

    @Override
    public DiscoveryResult discover(MavenProject project, MavenSession session) {
        List<String> specs = discoverPlatformArtifactSpecs(project);
        return specs.isEmpty() ? DiscoveryResult.empty() : DiscoveryResult.ofPlatformArtifacts(specs);
    }

    private static List<String> discoverPlatformArtifactSpecs(MavenProject project) {
        List<String> specs = new ArrayList<>();

        Plugin plugin = project.getBuildPlugins().stream()
                .filter(p -> PLUGIN_GROUP_ID.equals(p.getGroupId())
                        && PLUGIN_ARTIFACT_ID.equals(p.getArtifactId()))
                .findFirst()
                .orElse(null);

        if (plugin == null || !(plugin.getConfiguration() instanceof Xpp3Dom)) {
            return specs;
        }

        Xpp3Dom config = (Xpp3Dom) plugin.getConfiguration();

        // <protoc>VERSION</protoc> → com.google.protobuf:protoc:exe:VERSION
        Xpp3Dom protocElem = config.getChild("protoc");
        if (protocElem != null) {
            String version = resolveProperty(protocElem.getValue(), project);
            if (version != null && !version.isBlank()) {
                String spec = PROTOC_GROUP_ID + ":" + PROTOC_ARTIFACT_ID + ":exe:" + version;
                specs.add(spec);
                PluginLogManager.getLog().info(
                        "ProtobufMavenPlugin: discovered protoc spec: " + spec);
            }
        }

        // <plugins><plugin kind="binary-maven"><groupId>...</groupId>...
        Xpp3Dom pluginsElem = config.getChild("plugins");
        if (pluginsElem != null) {
            for (Xpp3Dom pluginElem : pluginsElem.getChildren("plugin")) {
                String kind = pluginElem.getAttribute("kind");
                if (!BINARY_MAVEN_KIND.equals(kind)) continue;

                String groupId = childValue(pluginElem, "groupId", project);
                String artifactId = childValue(pluginElem, "artifactId", project);
                String version = childValue(pluginElem, "version", project);

                if (groupId == null || artifactId == null || version == null) {
                    PluginLogManager.getLog().warn(
                            "ProtobufMavenPlugin: skipping binary-maven plugin entry"
                                    + " with missing groupId/artifactId/version");
                    continue;
                }

                String spec = groupId + ":" + artifactId + ":exe:" + version;
                specs.add(spec);
                PluginLogManager.getLog().info(
                        "ProtobufMavenPlugin: discovered binary-maven plugin spec: " + spec);
            }
        }

        return specs;
    }

    private static String childValue(Xpp3Dom parent, String childName, MavenProject project) {
        Xpp3Dom child = parent.getChild(childName);
        return child != null ? resolveProperty(child.getValue(), project) : null;
    }

    /**
     * Resolves a single {@code ${property.name}} placeholder using the project's effective
     * properties. Returns the value as-is if it is not a placeholder or cannot be resolved.
     */
    private static String resolveProperty(String value, MavenProject project) {
        if (value == null) return null;
        value = value.trim();
        if (value.startsWith("${") && value.endsWith("}")) {
            String key = value.substring(2, value.length() - 1);
            String resolved = project.getProperties().getProperty(key);
            if (resolved != null) return resolved.trim();
            PluginLogManager.getLog().warn(
                    "ProtobufMavenPlugin: could not resolve property: " + value);
        }
        return value;
    }
}
