package io.github.chains_project.maven_lockfile.resolvers;

import io.github.chains_project.maven_lockfile.checksum.AbstractChecksumCalculator;
import io.github.chains_project.maven_lockfile.checksum.RepositoryInformation;
import io.github.chains_project.maven_lockfile.data.ArtifactId;
import io.github.chains_project.maven_lockfile.data.ArtifactType;
import io.github.chains_project.maven_lockfile.data.Classifier;
import io.github.chains_project.maven_lockfile.data.GroupId;
import io.github.chains_project.maven_lockfile.data.MavenScope;
import io.github.chains_project.maven_lockfile.data.VersionNumber;
import io.github.chains_project.maven_lockfile.graph.DependencyNode;
import io.github.chains_project.maven_lockfile.reporting.PluginLogManager;
import java.util.ArrayList;
import java.util.List;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;
import org.apache.maven.project.MavenProject;

/**
 * Resolves platform-specific binary artifacts (e.g. {@code protoc}, {@code protoc-gen-grpc-java})
 * for the current build platform, as detected by {@code os-maven-plugin}.
 *
 * <p>Some Maven build plugins download native executables at build time using OS-detected
 * classifiers such as {@code linux-x86_64} or {@code osx-aarch_64}. When {@code os-maven-plugin}
 * is present, its {@code os.detected.classifier} property is used to resolve only the classifier
 * matching the current build platform.
 *
 * <p>Users declare the base artifact coordinates (without classifier) in the
 * {@code maven-lockfile} plugin configuration:
 *
 * <pre>{@code
 * <configuration>
 *   <platformArtifacts>
 *     <platformArtifact>com.google.protobuf:protoc:exe:3.25.5</platformArtifact>
 *     <platformArtifact>io.grpc:protoc-gen-grpc-java:exe:1.68.0</platformArtifact>
 *   </platformArtifacts>
 * </configuration>
 * }</pre>
 *
 * <p>Format: {@code groupId:artifactId:type:version}.
 */
public class PlatformArtifactResolver {

    private static final String OS_DETECTED_CLASSIFIER = "os.detected.classifier";

    private PlatformArtifactResolver() {}

    /**
     * Determines the OS/arch classifier for the current build platform.
     *
     * <p>Resolution order:
     * <ol>
     *   <li>The {@code os.detected.classifier} project property — set by {@code os-maven-plugin}
     *       or by plugins like {@code protobuf-maven-plugin} that perform their own OS detection.</li>
     *   <li>Computed from Java system properties {@code os.name} and {@code os.arch} — works
     *       without any explicit OS detection plugin.</li>
     * </ol>
     *
     * @return classifier string (e.g. {@code linux-x86_64}, {@code osx-aarch_64}),
     *         or {@code null} if the current platform is not recognized
     */
    public static String detectOsClassifier(MavenProject project) {
        // 1. Try project property (set by os-maven-plugin or by ascopes protobuf-maven-plugin)
        String classifier = project.getProperties().getProperty(OS_DETECTED_CLASSIFIER);
        if (classifier != null && !classifier.isBlank()) {
            PluginLogManager.getLog().info(
                    "PlatformArtifacts: using os.detected.classifier from project properties: "
                            + classifier);
            return classifier.trim();
        }

        // 2. Compute from Java system properties
        classifier = computeClassifierFromSystemProperties();
        if (classifier != null) {
            PluginLogManager.getLog().info(
                    "PlatformArtifacts: computed platform classifier from system properties: "
                            + classifier);
        } else {
            PluginLogManager.getLog().warn(
                    "PlatformArtifacts: could not determine platform classifier"
                            + " (os.name=" + System.getProperty("os.name")
                            + ", os.arch=" + System.getProperty("os.arch") + ")");
        }
        return classifier;
    }

    private static String computeClassifierFromSystemProperties() {
        String osName = System.getProperty("os.name", "").toLowerCase();
        String osArch = System.getProperty("os.arch", "").toLowerCase();

        String name;
        if (osName.contains("linux")) {
            name = "linux";
        } else if (osName.contains("mac") || osName.contains("darwin")) {
            name = "osx";
        } else if (osName.contains("windows")) {
            name = "windows";
        } else {
            return null;
        }

        String arch;
        if (osArch.equals("x86_64") || osArch.equals("amd64")) {
            arch = "x86_64";
        } else if (osArch.equals("aarch64")) {
            arch = "aarch_64";
        } else if (osArch.equals("ppc64le")) {
            arch = "ppc64le";
        } else if (osArch.equals("s390x")) {
            arch = "s390x";
        } else {
            return null;
        }

        return name + "-" + arch;
    }

    /**
     * Resolves the given artifact specs for the specified platform classifier and returns them as
     * {@link DependencyNode} instances ready to be added to the lockfile.
     *
     * @param platformArtifactSpecs list of {@code groupId:artifactId:type:version} strings
     * @param osClassifier          the OS/arch classifier (e.g. {@code linux-x86_64})
     * @param checksumCalculator    used to resolve remote URLs and compute checksums
     * @return flat list of resolved nodes, one per successfully resolved spec
     */
    public static List<DependencyNode> resolve(
            List<String> platformArtifactSpecs,
            String osClassifier,
            AbstractChecksumCalculator checksumCalculator) {
        List<DependencyNode> result = new ArrayList<>();

        for (String spec : platformArtifactSpecs) {
            String[] parts = spec.split(":");
            if (parts.length != 4) {
                PluginLogManager.getLog().warn(
                        "PlatformArtifacts: ignoring malformed spec (expected groupId:artifactId:type:version): "
                                + spec);
                continue;
            }
            String groupId = parts[0];
            String artifactId = parts[1];
            String type = parts[2];
            String version = parts[3];

            PluginLogManager.getLog().info(String.format(
                    "PlatformArtifacts: resolving %s:%s:%s:%s:%s",
                    groupId, artifactId, type, osClassifier, version));

            Artifact artifact = new DefaultArtifact(
                    groupId, artifactId, version,
                    "compile", type, osClassifier,
                    new DefaultArtifactHandler(type));

            RepositoryInformation repoInfo = checksumCalculator.getPluginResolvedField(artifact);
            if (repoInfo == null || repoInfo.equals(RepositoryInformation.Unresolved())
                    || repoInfo.getResolvedUrl() == null
                    || repoInfo.getResolvedUrl().getValue() == null) {
                PluginLogManager.getLog().warn(String.format(
                        "PlatformArtifacts: %s:%s:%s:%s:%s — not found in any repository",
                        groupId, artifactId, type, osClassifier, version));
                continue;
            }

            String checksum = checksumCalculator.calculatePluginChecksum(artifact);
            result.add(DependencyNode.ofPlatformArtifact(
                    ArtifactId.of(artifactId),
                    GroupId.of(groupId),
                    VersionNumber.of(version),
                    Classifier.of(osClassifier),
                    ArtifactType.of(type),
                    repoInfo.getResolvedUrl(),
                    repoInfo.getRepositoryId(),
                    checksumCalculator.getChecksumAlgorithm(),
                    checksum));

            PluginLogManager.getLog().info(String.format(
                    "PlatformArtifacts: resolved %s:%s:%s:%s:%s -> %s",
                    groupId, artifactId, type, osClassifier, version,
                    repoInfo.getResolvedUrl().getValue()));
        }

        return result;
    }
}
