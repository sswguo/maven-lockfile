package io.github.chains_project.maven_lockfile;

import com.google.common.graph.GraphBuilder;
import com.google.common.graph.MutableGraph;
import io.github.chains_project.maven_lockfile.checksum.AbstractChecksumCalculator;
import io.github.chains_project.maven_lockfile.checksum.RepositoryInformation;
import io.github.chains_project.maven_lockfile.data.ArtifactId;
import io.github.chains_project.maven_lockfile.data.GroupId;
import io.github.chains_project.maven_lockfile.data.LockFile;
import io.github.chains_project.maven_lockfile.data.MavenPlugin;
import io.github.chains_project.maven_lockfile.data.MetaData;
import io.github.chains_project.maven_lockfile.data.Pom;
import io.github.chains_project.maven_lockfile.data.Extension;
import io.github.chains_project.maven_lockfile.data.RepositoryId;
import io.github.chains_project.maven_lockfile.data.ResolvedUrl;
import io.github.chains_project.maven_lockfile.data.VersionNumber;
import io.github.chains_project.maven_lockfile.graph.DependencyGraph;
import io.github.chains_project.maven_lockfile.reporting.PluginLogManager;
import io.github.chains_project.maven_lockfile.data.P2DependencyNode;
import io.github.chains_project.maven_lockfile.data.P2Repository;
import io.github.chains_project.maven_lockfile.resolvers.BomResolver;
import io.github.chains_project.maven_lockfile.resolvers.P2Resolver;
import io.github.chains_project.maven_lockfile.resolvers.ProjectBuilder;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;
import org.apache.maven.artifact.resolver.filter.ArtifactFilter;
import org.apache.maven.artifact.resolver.filter.ScopeArtifactFilter;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Plugin;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.shared.dependency.graph.DependencyCollectorBuilder;
import org.apache.maven.shared.dependency.graph.DependencyCollectorBuilderException;
import org.apache.maven.shared.dependency.graph.DependencyNode;
import org.apache.maven.shared.dependency.graph.traversal.DependencyNodeVisitor;

/**
 * Entry point for the lock file generation. This class is responsible for generating the lock file for a project.
 *
 */
public class LockFileFacade {

    /**
     * This visitor is used to traverse the dependency graph and add the edges to the graph.
     */
    private static final class GraphBuildingNodeVisitor implements DependencyNodeVisitor {
        private final MutableGraph<DependencyNode> graph;
        /**
         * Create a new instance of the visitor.
         * @param graph  The graph to add the edges to.
         */
        private GraphBuildingNodeVisitor(MutableGraph<DependencyNode> graph) {
            this.graph = graph;
        }

        @Override
        public boolean visit(DependencyNode node) {
            node.getChildren().forEach(v -> graph.putEdge(node, v));
            return true;
        }

        @Override
        public boolean endVisit(DependencyNode node) {
            return true;
        }
    }

    /**
     * Generate a lock file for a project.
     * @param project The project to generate a lock file for.
     * @return A lock file for the project.
     */
    public static Path getLockFilePath(MavenProject project, String lockfileName) {
        return Path.of(project.getBasedir().getAbsolutePath(), lockfileName);
    }

    private LockFileFacade() {
        // Prevent instantiation
    }

    /**
     * Generate a lock file for a project. This method is responsible for generating the lock file for a project. It uses the dependency collector to generate the dependency graph and then resolves the dependencies.
     * @param session  The maven session.
     * @param project  The project to generate a lock file for.
     * @param dependencyCollectorBuilder  The dependency collector builder to use for generating the dependency graph.
     * @param checksumCalculator  The checksum calculator to use for calculating the checksums of the artifacts.
     * @param metadata The metadata to include in the lock file.
     * @return  A lock file for the project.
     */
    public static LockFile generateLockFileFromProject(
            MavenSession session,
            MavenProject project,
            DependencyCollectorBuilder dependencyCollectorBuilder,
            AbstractChecksumCalculator checksumCalculator,
            MetaData metadata) {
        PluginLogManager.getLog().info(String.format("Generating lock file for project %s", project.getArtifactId()));

        // Create resolvers once so their projectCache/pomChainCache/bomsCache are shared
        // across all resolveNodeParents() calls — avoids redundant buildFromGav() invocations
        // for artifacts that appear in multiple contexts (e.g. guava in both dep graph and plugins).
        var artifactResolver = new NodeParentResolver(session, project.getRemoteArtifactRepositories(), checksumCalculator);
        var pluginResolver = new NodeParentResolver(session, project.getPluginArtifactRepositories(), checksumCalculator);

        // Pre-warm plugin artifact checksums/URLs in parallel before the sequential plugin loop.
        // getPluginResolvedField + calculatePluginChecksum are otherwise called one-by-one per plugin.
        checksumCalculator.prewarmPluginCache(project.getPluginArtifacts());

        Set<MavenPlugin> plugins = new TreeSet<>();
        if (metadata.getConfig().isIncludeMavenPlugins()) {
            plugins = getAllPlugins(project, session, dependencyCollectorBuilder, checksumCalculator, pluginResolver);
        }

        var graph = buildDependencyGraph(session, project, dependencyCollectorBuilder, checksumCalculator,
                metadata.getConfig().isReduced());

        // Pre-warm: parallel buildFromGav for all dep nodes, then parallel HTTP for all parent POMs.
        // The sequential resolve() pass then hits only in-memory caches.
        artifactResolver.prewarm(graph.getGraph());
        graph.getGraph().forEach(artifactResolver::resolve);

        // Resolve P2/OSGi dependencies for Tycho projects
        List<P2DependencyNode> p2Dependencies = Collections.emptyList();
        List<P2Repository> p2Repositories = Collections.emptyList();
        if (P2Resolver.isTychoProject(project)) {
            PluginLogManager.getLog().info("Tycho project detected — resolving P2 dependencies from .target files");
            P2Resolver.P2ResolverResult p2Result = P2Resolver.resolve(project);
            p2Dependencies = p2Result.getArtifacts();
            p2Repositories = p2Result.getRepositories();
            PluginLogManager.getLog().info(String.format(
                    "Resolved %d P2 artifact(s) from %d repository(ies)",
                    p2Dependencies.size(), p2Repositories.size()));
        }

        return new LockFile(
                GroupId.of(project.getGroupId()),
                ArtifactId.of(project.getArtifactId()),
                VersionNumber.of(project.getVersion()),
                constructProjectPomChain(project, checksumCalculator),
                graph.getRoots(),
                plugins,
                resolveBoms(session, project, checksumCalculator),
                resolveExtensions(session, project, dependencyCollectorBuilder, checksumCalculator, pluginResolver),
                p2Dependencies,
                p2Repositories,
                metadata);
    }

    private static Set<Extension> resolveExtensions(
            MavenSession session,
            MavenProject project,
            DependencyCollectorBuilder dependencyCollectorBuilder,
            AbstractChecksumCalculator checksumCalculator,
            NodeParentResolver pluginResolver) {
        var buildExtensions = project.getBuild().getExtensions();
        PluginLogManager.getLog().info(String.format(
                "Resolving %d build extension(s) for project %s", buildExtensions.size(), project.getArtifactId()));

        ProjectBuilder projectBuilder = new ProjectBuilder(session, project.getPluginArtifactRepositories());
        BomResolver bomResolver = new BomResolver(session, project.getPluginArtifactRepositories(), checksumCalculator);
        Set<Extension> extensions = new TreeSet<>();

        for (org.apache.maven.model.Extension ext : buildExtensions) {
            PluginLogManager.getLog().info(String.format(
                    "Resolving extension %s:%s:%s", ext.getGroupId(), ext.getArtifactId(), ext.getVersion()));

            Artifact artifact = new DefaultArtifact(
                    ext.getGroupId(), ext.getArtifactId(), ext.getVersion(),
                    "compile", "jar", null, new DefaultArtifactHandler("jar"));
            RepositoryInformation repoInfo = checksumCalculator.getPluginResolvedField(artifact);
            String checksum = checksumCalculator.calculatePluginChecksum(artifact);
            Set<io.github.chains_project.maven_lockfile.graph.DependencyNode> deps =
                    resolvePluginDependencies(artifact, session, project, dependencyCollectorBuilder, checksumCalculator,
                            Collections.emptyList(), pluginResolver);

            Optional<MavenProject> extProjectOpt = projectBuilder.buildFromGav(
                    ext.getGroupId(), ext.getArtifactId(), ext.getVersion());
            Pom pom = extProjectOpt.map(p -> resolveParentChain(p, checksumCalculator)).orElse(null);
            Set<Pom> extBoms = extProjectOpt.map(bomResolver::resolveForProject).orElse(Collections.emptySet());

            PluginLogManager.getLog().info(String.format(
                    "Resolved extension %s:%s:%s -> url=%s, repo=%s, checksum=%s, dependencies=%d, pom=%s",
                    ext.getGroupId(), ext.getArtifactId(), ext.getVersion(),
                    repoInfo.getResolvedUrl(), repoInfo.getRepositoryId(), checksum, deps.size(),
                    pom != null ? pom.getChecksum() : "unresolved"));

            extensions.add(new Extension(
                    GroupId.of(ext.getGroupId()),
                    ArtifactId.of(ext.getArtifactId()),
                    VersionNumber.of(ext.getVersion()),
                    null,
                    repoInfo.getResolvedUrl(),
                    repoInfo.getRepositoryId(),
                    checksumCalculator.getChecksumAlgorithm(),
                    checksum,
                    deps,
                    pom,
                    extBoms));
        }
        return extensions;
    }

    /**
     * Resolve the parent POM chain of a dependency (or plugin/extension) project as a linked {@link Pom} chain.
     * Always resolves as POM-type artifacts regardless of the project's packaging
     * (e.g. Guava has type=bundle, but the file hermeto needs is the .pom).
     */
    private static Pom resolveParentChain(MavenProject start, AbstractChecksumCalculator checksumCalculator) {
        List<MavenProject> chain = new ArrayList<>();
        for (MavenProject p = start; p != null; p = p.hasParent() ? p.getParent() : null) {
            chain.add(p);
        }
        Collections.reverse(chain); // root-first so each entry becomes the parent of the next

        Pom pom = null;
        for (MavenProject p : chain) {
            // Always resolve as a POM artifact regardless of the project's packaging type
            // (e.g. guava has type=bundle, but the file we want to checksum is the .pom)
            Artifact pomArtifact = new DefaultArtifact(
                    p.getGroupId(), p.getArtifactId(), p.getVersion(),
                    "compile", "pom", null, new DefaultArtifactHandler("pom"));
            RepositoryInformation repoInfo = checksumCalculator.getArtifactResolvedField(pomArtifact);
            String checksum = checksumCalculator.calculateArtifactChecksum(pomArtifact);
            PluginLogManager.getLog().debug(String.format("Resolved parent %s:%s:%s -> checksum=%s",
                    p.getGroupId(), p.getArtifactId(), p.getVersion(), checksum));
            pom = new Pom(GroupId.of(p.getGroupId()), ArtifactId.of(p.getArtifactId()),
                    VersionNumber.of(p.getVersion()), null,
                    repoInfo.getResolvedUrl(), repoInfo.getRepositoryId(),
                    checksumCalculator.getChecksumAlgorithm(), checksum, pom);
        }
        return pom;
    }

    /**
     * Attaches a POM parent chain and BOM imports to every node in the dependency graph.
     * Results are cached by GAV inside {@code resolver} so each unique artifact is resolved at most once.
     */
    private static void resolveNodeParents(DependencyGraph graph, NodeParentResolver resolver) {
        graph.getGraph().forEach(resolver::resolve);
    }

    /**
     * Stateful helper that resolves POM parent chains and BOM imports for dependency nodes.
     * Caches {@link MavenProject} lookups, POM chains, and BOM sets by GAV to avoid redundant
     * remote calls when multiple nodes share the same parent or BOM.
     */
    private static final class NodeParentResolver {
        private final ProjectBuilder projectBuilder;
        private final BomResolver bomResolver;
        private final AbstractChecksumCalculator checksumCalculator;
        // ConcurrentHashMap so the parallel prewarm() phase and sequential resolve() phase
        // can both access it safely.
        private final java.util.concurrent.ConcurrentHashMap<String, Optional<MavenProject>> projectCache =
                new java.util.concurrent.ConcurrentHashMap<>();
        private final Map<String, Pom> pomChainCache = new HashMap<>();
        private final Map<String, Set<Pom>> bomsCache = new HashMap<>();

        NodeParentResolver(MavenSession session,
                @SuppressWarnings("deprecation") List<org.apache.maven.artifact.repository.ArtifactRepository> repositories,
                AbstractChecksumCalculator checksumCalculator) {
            this.projectBuilder = new ProjectBuilder(session, repositories);
            this.bomResolver = new BomResolver(session, repositories, checksumCalculator);
            this.checksumCalculator = checksumCalculator;
        }

        /**
         * Two-phase parallel pre-warm before the sequential resolve() traversal:
         * <ol>
         *   <li>Call {@code buildFromGav()} for every dep node in parallel → populates
         *       {@code projectCache} so the sequential pass never blocks on file I/O.</li>
         *   <li>Collect every parent POM artifact from all resolved projects, then pass
         *       them to {@code prewarmArtifactCache()} so parent-POM HEAD and checksum
         *       HTTP calls are issued in parallel before the sequential traversal.</li>
         * </ol>
         */
        void prewarm(Collection<io.github.chains_project.maven_lockfile.graph.DependencyNode> nodes) {
            if (nodes.isEmpty()) {
                return;
            }
            int poolSize = Math.min(16, Math.max(4, Runtime.getRuntime().availableProcessors() * 2));
            java.util.concurrent.ExecutorService executor =
                    java.util.concurrent.Executors.newFixedThreadPool(poolSize);
            try {
                // Phase 1: parallel buildFromGav for all dep nodes
                List<java.util.concurrent.Future<MavenProject>> futures = new ArrayList<>();
                for (var node : nodes) {
                    String gav = node.getGroupId().getValue() + ":" + node.getArtifactId().getValue()
                            + ":" + node.getVersion().getValue();
                    futures.add(executor.submit(() -> projectCache.computeIfAbsent(gav, k ->
                            projectBuilder.buildFromGav(
                                    node.getGroupId().getValue(),
                                    node.getArtifactId().getValue(),
                                    node.getVersion().getValue()))
                            .orElse(null)));
                }

                // Phase 2: collect all parent POM artifacts from resolved projects
                List<Artifact> parentPomArtifacts = new ArrayList<>();
                for (var future : futures) {
                    try {
                        MavenProject project = future.get();
                        if (project != null) {
                            for (MavenProject p = project; p != null;
                                    p = p.hasParent() ? p.getParent() : null) {
                                parentPomArtifacts.add(new DefaultArtifact(
                                        p.getGroupId(), p.getArtifactId(), p.getVersion(),
                                        "compile", "pom", null, new DefaultArtifactHandler("pom")));
                            }
                        }
                    } catch (Exception e) {
                        PluginLogManager.getLog().debug("Prewarm buildFromGav task failed: " + e.getMessage());
                    }
                }

                // Phase 3: parallel HTTP pre-warm for all parent POM checksums/URLs
                checksumCalculator.prewarmArtifactCache(parentPomArtifacts);
            } finally {
                executor.shutdown();
            }
        }

        void resolve(io.github.chains_project.maven_lockfile.graph.DependencyNode node) {
            String gav = node.getGroupId().getValue() + ":" + node.getArtifactId().getValue()
                    + ":" + node.getVersion().getValue();

            Optional<MavenProject> project = projectCache.computeIfAbsent(gav, k -> {
                PluginLogManager.getLog().debug(String.format("Resolving parent chain for dependency %s", gav));
                return projectBuilder.buildFromGav(
                        node.getGroupId().getValue(), node.getArtifactId().getValue(), node.getVersion().getValue());
            });

            project.ifPresent(p -> {
                node.setPom(pomChainCache.computeIfAbsent(gav, k -> resolveParentChain(p, checksumCalculator)));
                Set<Pom> boms = bomsCache.computeIfAbsent(gav, k -> bomResolver.resolveForProject(p));
                if (!boms.isEmpty()) {
                    node.setBoms(boms);
                }
            });

            node.getChildren().forEach(this::resolve);
        }
    }

    private static Set<MavenPlugin> getAllPlugins(
            MavenProject project,
            MavenSession session,
            DependencyCollectorBuilder dependencyCollectorBuilder,
            AbstractChecksumCalculator checksumCalculator,
            NodeParentResolver pluginResolver) {
        Set<MavenPlugin> plugins = new TreeSet<>();

        Map<String, List<Dependency>> userPluginDependencies = new HashMap<>();
        if (project.getBuild() != null && project.getBuild().getPlugins() != null) {
            for (Plugin plugin : project.getBuild().getPlugins()) {
                String key = plugin.getGroupId() + ":" + plugin.getArtifactId();
                if (plugin.getDependencies() != null && !plugin.getDependencies().isEmpty()) {
                    userPluginDependencies.put(key, plugin.getDependencies());
                }
            }
        }

        ProjectBuilder pluginProjectBuilder = new ProjectBuilder(session, project.getPluginArtifactRepositories());
        BomResolver pluginBomResolver = new BomResolver(session, project.getPluginArtifactRepositories(), checksumCalculator);
        for (Artifact pluginArtifact : project.getPluginArtifacts()) {
            RepositoryInformation repositoryInformation = checksumCalculator.getPluginResolvedField(pluginArtifact);
            String pluginKey = pluginArtifact.getGroupId() + ":" + pluginArtifact.getArtifactId();
            List<Dependency> userDeclaredDeps = userPluginDependencies.getOrDefault(pluginKey, Collections.emptyList());

            Set<io.github.chains_project.maven_lockfile.graph.DependencyNode> pluginDependencies =
                    resolvePluginDependencies(
                            pluginArtifact, session, project, dependencyCollectorBuilder, checksumCalculator, userDeclaredDeps, pluginResolver);
            Optional<MavenProject> pluginProjectOpt = pluginProjectBuilder.buildFromGav(
                    pluginArtifact.getGroupId(), pluginArtifact.getArtifactId(), pluginArtifact.getBaseVersion());
            Pom pluginPom = pluginProjectOpt.map(p -> resolveParentChain(p, checksumCalculator)).orElse(null);
            Set<Pom> pluginBoms = pluginProjectOpt.map(pluginBomResolver::resolveForProject).orElse(Collections.emptySet());
            plugins.add(new MavenPlugin(
                    GroupId.of(pluginArtifact.getGroupId()),
                    ArtifactId.of(pluginArtifact.getArtifactId()),
                    VersionNumber.of(pluginArtifact.getVersion()),
                    repositoryInformation.getResolvedUrl(),
                    repositoryInformation.getRepositoryId(),
                    checksumCalculator.getChecksumAlgorithm(),
                    checksumCalculator.calculatePluginChecksum(pluginArtifact),
                    pluginDependencies,
                    pluginPom,
                    pluginBoms));
        }
        return plugins;
    }

    /**
     * Resolve the dependencies of a Maven plugin.
     *
     * @param pluginArtifact The plugin artifact to resolve dependencies for
     * @param session The Maven session
     * @param project The current Maven project (for repository configuration)
     * @param dependencyCollectorBuilder The dependency collector builder
     * @param checksumCalculator The checksum calculator
     * @param userDeclaredDeps User-declared dependencies for this plugin (from the project's pom.xml)
     * @return A set of dependency nodes representing the plugin's dependencies
     */
    private static Set<io.github.chains_project.maven_lockfile.graph.DependencyNode> resolvePluginDependencies(
            Artifact pluginArtifact,
            MavenSession session,
            MavenProject project,
            DependencyCollectorBuilder dependencyCollectorBuilder,
            AbstractChecksumCalculator checksumCalculator,
            List<Dependency> userDeclaredDeps,
            NodeParentResolver pluginResolver) {
        PluginLogManager.getLog()
                .debug(String.format("Attempting to resolve dependencies for plugin %s", pluginArtifact));
        try {
            ProjectBuilder projectBuilder = new ProjectBuilder(session, project.getPluginArtifactRepositories());
            Optional<MavenProject> pluginProjectOptional = projectBuilder.buildFromGav(
                    pluginArtifact.getGroupId(), pluginArtifact.getArtifactId(), pluginArtifact.getBaseVersion());

            if (pluginProjectOptional.isEmpty()) {
                PluginLogManager.getLog().warn(String.format("Could not build project for plugin %s", pluginArtifact));
                return Collections.emptySet();
            }

            var pluginProject = pluginProjectOptional.get();

            int declaredDeps = pluginProject.getDependencies() != null
                    ? pluginProject.getDependencies().size()
                    : 0;
            PluginLogManager.getLog()
                    .debug(String.format(
                            "Built plugin project %s with %d declared dependencies", pluginArtifact, declaredDeps));

            // User-declared plugin dependencies (from <plugin><dependencies> in pom.xml) override
            // the plugin's built-in defaults — e.g. to change scope or pin a version.
            if (!userDeclaredDeps.isEmpty()) {
                mergeUserDeclaredDeps(pluginProject, userDeclaredDeps, pluginArtifact);
            }

            ProjectBuildingRequest dependencyBuildingRequest =
                    new DefaultProjectBuildingRequest(session.getProjectBuildingRequest());
            dependencyBuildingRequest.setProject(pluginProject);
            dependencyBuildingRequest.setRemoteRepositories(project.getPluginArtifactRepositories());

            // Filter artifacts to "compile+runtime" scopes. Maven plugins require their runtime
            // scope dependencies to be present alongside any compile-time dependencies.
            // Test scope dependencies of plugins should be excluded.
            ArtifactFilter filter = new ScopeArtifactFilter("compile+runtime");
            var rootNode = dependencyCollectorBuilder.collectDependencyGraph(dependencyBuildingRequest, filter);

            int rootChildren =
                    rootNode.getChildren() != null ? rootNode.getChildren().size() : 0;
            PluginLogManager.getLog()
                    .debug(String.format(
                            "Collected dependency graph for plugin %s, root node has %d children",
                            pluginArtifact, rootChildren));

            MutableGraph<DependencyNode> graph = GraphBuilder.directed().build();
            rootNode.accept(new GraphBuildingNodeVisitor(graph));

            PluginLogManager.getLog()
                    .debug(String.format(
                            "Built graph with %d nodes for plugin %s",
                            graph.nodes().size(), pluginArtifact));

            DependencyGraph dependencyGraph = DependencyGraph.of(graph, checksumCalculator, false);
            resolveNodeParents(dependencyGraph, pluginResolver);

            // Conflict-loser nodes have their subtrees pruned by Maven's resolver, but their
            // transitive POMs still need to be in the local repo for offline resolution. Resolve
            // each loser's subtree independently so hermeto can pre-fetch them.
            final Set<String> resolvingLosers = new HashSet<>();
           /* dependencyGraph.populateChildrenForConflictLosers(loserNode -> {
                String gav = loserNode.getGroupId().getValue() + ":"
                        + loserNode.getArtifactId().getValue() + ":"
                        + loserNode.getVersion().getValue();
                if (!resolvingLosers.add(gav)) {
                    // already resolving this artifact (cycle guard)
                    return Collections.emptySet();
                }
                Artifact loserArtifact = new DefaultArtifact(
                        loserNode.getGroupId().getValue(),
                        loserNode.getArtifactId().getValue(),
                        loserNode.getVersion().getValue(),
                        "compile",
                        "jar",
                        null,
                        new DefaultArtifactHandler("jar"));
                return resolvePluginDependencies(
                        loserArtifact, session, project, dependencyCollectorBuilder, checksumCalculator, Collections.emptyList(), pluginResolver);
            });*/

            Set<io.github.chains_project.maven_lockfile.graph.DependencyNode> roots = dependencyGraph.getRoots();
            PluginLogManager.getLog()
                    .info(String.format("Resolved %4d dependencies for plugin %s", roots.size(), pluginArtifact));
            return roots;

        } catch (Exception e) {
            PluginLogManager.getLog()
                    .warn(String.format("Could not resolve dependencies for plugin %s", pluginArtifact), e);
            return Collections.emptySet();
        }
    }

    /**
     * Merges user-declared plugin dependencies (from the project's {@code <plugin><dependencies>})
     * into the plugin project's dependency list. User entries override built-in defaults by GA key;
     * new entries are appended.
     */
    private static void mergeUserDeclaredDeps(
            MavenProject pluginProject, List<Dependency> userDeclaredDeps, Artifact pluginArtifact) {
        List<Dependency> pluginDeps = new ArrayList<>(pluginProject.getDependencies());
        Map<String, Dependency> existingByGa = new HashMap<>();
        for (Dependency dep : pluginDeps) {
            existingByGa.put(dep.getGroupId() + ":" + dep.getArtifactId(), dep);
        }

        for (Dependency userDep : userDeclaredDeps) {
            String ga = userDep.getGroupId() + ":" + userDep.getArtifactId();
            Dependency existing = existingByGa.get(ga);
            if (existing != null) {
                pluginDeps.remove(existing);
                PluginLogManager.getLog().debug(String.format(
                        "Overriding plugin dependency %s (scope: %s -> %s)", ga, existing.getScope(), userDep.getScope()));
            } else {
                PluginLogManager.getLog().debug(String.format(
                        "Adding user-declared dependency %s to plugin %s", ga, pluginArtifact));
            }
            pluginDeps.add(userDep);
        }

        pluginProject.setDependencies(pluginDeps);
        PluginLogManager.getLog().debug(String.format(
                "Plugin %s now has %d dependencies after merging user-declared dependencies",
                pluginArtifact, pluginDeps.size()));
    }

    private static DependencyGraph buildDependencyGraph(
            MavenSession session,
            MavenProject project,
            DependencyCollectorBuilder dependencyCollectorBuilder,
            AbstractChecksumCalculator checksumCalculator,
            boolean reduced) {
        try {
            // Build the reactor sibling GAV set before collection.
            Set<String> reactorGavs = session.getProjects().stream()
                    .map(p -> p.getGroupId() + ":" + p.getArtifactId() + ":" + p.getVersion())
                    .collect(Collectors.toSet());
            // Remove the current module itself — it is the graph root, not a sibling to skip.
            reactorGavs.remove(project.getGroupId() + ":" + project.getArtifactId() + ":" + project.getVersion());

            ProjectBuildingRequest buildingRequest =
                    new DefaultProjectBuildingRequest(session.getProjectBuildingRequest());
            buildingRequest.setProject(project);

            var rootNode = dependencyCollectorBuilder.collectDependencyGraph(buildingRequest, null);

            MutableGraph<DependencyNode> graph = GraphBuilder.directed().build();
            rootNode.accept(new GraphBuildingNodeVisitor(graph));
            PluginLogManager.getLog()
                    .info(String.format(
                            "Resolved %4d dependencies for project %s",
                            graph.nodes().size(), project));

            // Reactor nodes are now in the graph but have no remote URL — exclude them from
            // the lockfile output. Their transitive deps (all external) remain included.
            return DependencyGraph.of(graph, checksumCalculator, reduced, reactorGavs);
        } catch (Exception e) {
            PluginLogManager.getLog().warn("Could not generate graph", e);
            return DependencyGraph.of(GraphBuilder.directed().build(), checksumCalculator, reduced);
        }
    }

    /**
     * Build a linked {@link Pom} chain for the root project's own parent hierarchy.
     * Each entry is either:
     * <ul>
     *   <li>A <b>local POM</b> (file exists on disk) — checksummed from the file, stored with a
     *       relative path, no resolved URL.</li>
     *   <li>An <b>external POM</b> (no local file) — resolved from a remote repository, stored
     *       with a resolved URL and repository ID.</li>
     * </ul>
     */
    private static Pom constructProjectPomChain(
            MavenProject initialProject, AbstractChecksumCalculator checksumCalculator) {
        // Collect chain from project up to root ancestor, then reverse to build root-first
        List<MavenProject> chain = new ArrayList<>();
        for (MavenProject p = initialProject; p != null; p = p.hasParent() ? p.getParent() : null) {
            chain.add(p);
        }
        Collections.reverse(chain);

        Pom pom = null;
        for (MavenProject project : chain) {
            if (project.getFile() != null) {
                // Local POM — checksum from file on disk, relativePath for identification
                String relativePath = initialProject.getBasedir().toPath()
                        .relativize(project.getFile().toPath()).toString();
                String checksum = checksumCalculator.calculatePomChecksum(project.getFile().toPath());
                pom = new Pom(GroupId.of(project.getGroupId()), ArtifactId.of(project.getArtifactId()),
                        VersionNumber.of(project.getVersion()), relativePath,
                        null, null, checksumCalculator.getChecksumAlgorithm(), checksum, pom);
            } else {
                // External POM — resolve URL and checksum from remote repository
                Artifact artifact = project.getArtifact();
                Artifact pomArtifact = new DefaultArtifact(
                        artifact.getGroupId(), artifact.getArtifactId(), artifact.getVersion(),
                        artifact.getScope(), "pom", artifact.getClassifier(), artifact.getArtifactHandler());
                String checksum = checksumCalculator.calculateArtifactChecksum(pomArtifact);
                RepositoryInformation repoInfo = checksumCalculator.getArtifactResolvedField(pomArtifact);
                pom = new Pom(GroupId.of(project.getGroupId()), ArtifactId.of(project.getArtifactId()),
                        VersionNumber.of(project.getVersion()), null,
                        repoInfo.getResolvedUrl(), repoInfo.getRepositoryId(),
                        checksumCalculator.getChecksumAlgorithm(), checksum, pom);
            }
        }
        return pom;
    }

    private static Set<Pom> resolveBoms(
            MavenSession session, MavenProject rootProject, AbstractChecksumCalculator checksumCalculator) {
        BomResolver bomResolver =
                new BomResolver(session, rootProject.getRemoteArtifactRepositories(), checksumCalculator);
        return bomResolver.resolveForProject(rootProject);
    }
}
