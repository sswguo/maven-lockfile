package io.github.chains_project.maven_lockfile.resolvers;

import io.github.chains_project.maven_lockfile.checksum.AbstractChecksumCalculator;
import io.github.chains_project.maven_lockfile.data.ArtifactId;
import io.github.chains_project.maven_lockfile.data.GroupId;
import io.github.chains_project.maven_lockfile.data.Pom;
import io.github.chains_project.maven_lockfile.data.VersionNumber;
import io.github.chains_project.maven_lockfile.reporting.PluginLogManager;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.project.MavenProject;

public class BomResolver {
    private final MavenSession session;

    @SuppressWarnings("deprecation")
    private final List<ArtifactRepository> repositories;

    private final AbstractChecksumCalculator checksumCalculator;

    // Shared cache across all resolveForProject calls — keyed by groupId:artifactId:version.
    // Prevents re-fetching the same BOM POM when multiple dependencies share the same parent chain.
    private final Map<String, Optional<MavenProject>> projectCache = new HashMap<>();

    // Tracks which BOM GAVs have already been fully resolved (including their nested BOM imports),
    // preventing infinite recursion when BOMs import each other.
    private final Set<String> resolvedBoms = new HashSet<>();

    private final ProjectBuilder projectBuilder;

    @SuppressWarnings("deprecation")
    public BomResolver(
            MavenSession session,
            List<ArtifactRepository> repositories,
            AbstractChecksumCalculator checksumCalculator) {
        this.session = session;
        this.repositories = repositories;
        this.checksumCalculator = checksumCalculator;
        this.projectBuilder = new ProjectBuilder(session, repositories);
    }

    public Set<Pom> resolveForProject(MavenProject project) {
        var boms = new TreeSet<Pom>();

        // Walk the full parent chain so we capture BOM imports declared in parent POMs.
        // For example, protobuf-parent imports protobuf-bom in its own <dependencyManagement>;
        // any child (e.g. protobuf-java) needs that BOM pre-fetched for a hermetic build.
        MavenProject current = project;
        while (current != null) {
            var model = current.getOriginalModel();
            var dependencyManagement = model.getDependencyManagement();

            if (dependencyManagement != null && !dependencyManagement.getDependencies().isEmpty()) {
                for (Dependency dependency : dependencyManagement.getDependencies()) {
                    if ("pom".equals(dependency.getType()) && "import".equals(dependency.getScope())) {
                        var resolvedGroupId = resolvePropertyPlaceholder(dependency.getGroupId(), current);
                        var resolvedArtifactId = resolvePropertyPlaceholder(dependency.getArtifactId(), current);
                        var resolvedVersion = resolvePropertyPlaceholder(dependency.getVersion(), current);
                        String cacheKey = resolvedGroupId + ":" + resolvedArtifactId + ":" + resolvedVersion;
                        var bomProjectOptional = projectCache.computeIfAbsent(cacheKey, k -> {
                            PluginLogManager.getLog().info(String.format("Resolving BOM for %s:%s:%s (from parent chain of %s)", resolvedGroupId, resolvedArtifactId, resolvedVersion, project.getArtifactId()));
                            return projectBuilder.buildFromGav(
                                    resolvedGroupId, resolvedArtifactId, resolvedVersion);
                        });

                        if (bomProjectOptional.isEmpty()) {
                            PluginLogManager.getLog().warn(String.format("Could not resolve BOM for %s", dependency));
                            continue;
                        }

                        var bomTree = resolveBomParents(bomProjectOptional.get());
                        boms.add(bomTree);

                        // Recursively resolve BOMs imported by this BOM (nested BOM imports).
                        // resolvedBoms.add returns false if already processed — prevents cycles.
                        if (resolvedBoms.add(cacheKey)) {
                            var nestedBoms = resolveForProject(bomProjectOptional.get());
                            boms.addAll(nestedBoms);
                        }
                    }
                }
            }

            current = current.hasParent() ? current.getParent() : null;
        }

        return boms;
    }

    private String resolvePropertyPlaceholder(String value, MavenProject project) {
        if (value != null && value.startsWith("${") && value.endsWith("}")) {
            String propertyName = value.substring(2, value.length() - 1);

            // Handle Maven built-in project expressions first
            switch (propertyName) {
                case "project.version":
                case "pom.version":
                    return project.getVersion();
                case "project.groupId":
                case "pom.groupId":
                    return project.getGroupId();
                case "project.artifactId":
                case "pom.artifactId":
                    return project.getArtifactId();
                case "project.parent.version":
                    return project.hasParent() ? project.getParent().getVersion() : value;
                default:
                    break;
            }

            // Walk the project and its parent chain to find the property
            for (MavenProject p = project; p != null; p = p.hasParent() ? p.getParent() : null) {
                String resolved = p.getModel().getProperties().getProperty(propertyName);
                if (resolved != null) {
                    return resolved;
                }
            }
        }

        return value;
    }

    /**
     * Build a linked {@link Pom} chain from the BOM's own parent hierarchy.
     * The chain is built root-first so each entry references its ancestor as {@code parent}.
     */
    private Pom resolveBomParents(MavenProject start) {
        // Collect chain from BOM project up to root ancestor, then reverse to build root-first
        List<MavenProject> chain = new ArrayList<>();
        for (MavenProject p = start; p != null; p = p.hasParent() ? p.getParent() : null) {
            chain.add(p);
        }
        Collections.reverse(chain);

        Pom pom = null;
        for (MavenProject project : chain) {
            pom = mavenProjectToBom(project, checksumCalculator, pom);
        }
        return pom;
    }

    private static Pom mavenProjectToBom(
            MavenProject project, AbstractChecksumCalculator checksumCalculator, Pom parent) {
        var dependency = project.getModel();

        // BOMs are POM-type artifacts — always resolve as .pom, never as .jar
        Artifact pomArtifact = new DefaultArtifact(
                dependency.getGroupId(),
                dependency.getArtifactId(),
                dependency.getVersion(),
                "compile",
                "pom",
                null,
                new DefaultArtifactHandler("pom"));
        var repoInfo = checksumCalculator.getArtifactResolvedField(pomArtifact);
        var checksum = checksumCalculator.calculateArtifactChecksum(pomArtifact);
        var checksumAlgorithm = checksumCalculator.getChecksumAlgorithm();

        return new Pom(
                GroupId.of(dependency.getGroupId()),
                ArtifactId.of(dependency.getArtifactId()),
                VersionNumber.of(dependency.getVersion()),
                null,
                repoInfo.getResolvedUrl(),
                repoInfo.getRepositoryId(),
                checksumAlgorithm,
                checksum,
                parent);
    }
}
