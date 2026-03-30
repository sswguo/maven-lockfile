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
import io.github.chains_project.maven_lockfile.resolvers.PlatformArtifactResolver;
import io.github.chains_project.maven_lockfile.resolvers.ProjectBuilder;
import io.github.chains_project.maven_lockfile.resolvers.ProtobufMavenPluginResolver;
import io.github.chains_project.maven_lockfile.resolvers.QuarkusDeploymentResolver;
import io.github.chains_project.maven_lockfile.resolvers.ExtraArtifactResolver;
import io.github.chains_project.maven_lockfile.resolvers.MavenCompilerPluginResolver;
import io.github.chains_project.maven_lockfile.resolvers.SpecialPluginResolver;
import io.github.chains_project.maven_lockfile.resolvers.SurefirePluginResolver;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.List;
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
     * Registry of all special plugin resolvers. Add new implementations here to extend
     * lockfile generation to new build plugins without touching any other code.
     */
    private static final List<SpecialPluginResolver> PLUGIN_RESOLVERS = List.of(
            new QuarkusDeploymentResolver(),
            new ProtobufMavenPluginResolver(),
            new SurefirePluginResolver(),
            new MavenCompilerPluginResolver());

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
        return generateLockFileFromProject(
                session, project, dependencyCollectorBuilder, checksumCalculator, metadata,
                Collections.emptyList(), null);
    }

    public static LockFile generateLockFileFromProject(
            MavenSession session,
            MavenProject project,
            DependencyCollectorBuilder dependencyCollectorBuilder,
            AbstractChecksumCalculator checksumCalculator,
            MetaData metadata,
            List<String> platformArtifactSpecs) {
        return generateLockFileFromProject(
                session, project, dependencyCollectorBuilder, checksumCalculator, metadata,
                platformArtifactSpecs, null);
    }

    public static LockFile generateLockFileFromProject(
            MavenSession session,
            MavenProject project,
            DependencyCollectorBuilder dependencyCollectorBuilder,
            AbstractChecksumCalculator checksumCalculator,
            MetaData metadata,
            List<String> platformArtifactSpecs,
            RepositorySystem repositorySystem) {
        PluginLogManager.getLog().info(String.format("Generating lock file for project %s", project.getArtifactId()));

        // Phase 1: create a mutable session copy with a recording RepositoryListener attached.
        // The mutable session is injected into every ProjectBuildingRequest so that
        // artifactResolved fires for each POM/artifact resolved — warm cache or cold cache.
        ExtraArtifactResolver.Tracker extraTracker = ExtraArtifactResolver.createTracker(session, project);
        DefaultRepositorySystemSession mutableSession = extraTracker.getMutableSession();

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
            plugins = getAllPlugins(project, session, dependencyCollectorBuilder, checksumCalculator, pluginResolver, mutableSession, repositorySystem);
        }

        var graph = buildDependencyGraph(session, project, dependencyCollectorBuilder, checksumCalculator,
                metadata.getConfig().isReduced(), mutableSession, repositorySystem, artifactResolver);

        // Pre-warm: parallel buildFromGav for all dep nodes, then parallel HTTP for all parent POMs.
        // The sequential resolve() pass then hits only in-memory caches.
        artifactResolver.prewarm(graph.getGraph());
        graph.getGraph().forEach(artifactResolver::resolve);

        // Resolve platform-specific binary artifacts (e.g. protoc) for the current platform.
        Set<io.github.chains_project.maven_lockfile.graph.DependencyNode> allRoots =
                new TreeSet<>(Comparator.comparing(
                        io.github.chains_project.maven_lockfile.graph.DependencyNode::getComparatorString));
        allRoots.addAll(graph.getRoots());

        // Collect platform artifact specs from all resolvers + manually declared platformArtifacts.
        List<String> allPlatformSpecs = new ArrayList<>(platformArtifactSpecs);
        for (SpecialPluginResolver resolver : PLUGIN_RESOLVERS) {
            if (!resolver.isApplicable(project)) continue;
            SpecialPluginResolver.DiscoveryResult result = resolver.discover(project, session);
            allPlatformSpecs.addAll(result.getPlatformArtifactSpecs());
        }

        if (!allPlatformSpecs.isEmpty()) {
            String osClassifier = PlatformArtifactResolver.detectOsClassifier(project);
            if (osClassifier == null) {
                PluginLogManager.getLog().warn(
                        "PlatformArtifacts: platform artifacts found but os-maven-plugin not present"
                                + " or os.detected.classifier not set — skipping platform artifact resolution");
            } else {
                List<io.github.chains_project.maven_lockfile.graph.DependencyNode> platformNodes =
                        PlatformArtifactResolver.resolve(allPlatformSpecs, osClassifier, checksumCalculator);
                allRoots.addAll(platformNodes);
                PluginLogManager.getLog().info(String.format(
                        "PlatformArtifacts: added %d platform binary node(s) for %s",
                        platformNodes.size(), osClassifier));
            }
        }

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

        Set<Pom> boms = resolveBoms(session, project, checksumCalculator);
        Set<Extension> extensions = resolveExtensions(
                session, project, dependencyCollectorBuilder, checksumCalculator, pluginResolver, mutableSession, repositorySystem);

        // Resolve annotation processors (and any other forceDependencyPopulation resolvers)
        // as standalone roots so their full unmediated transitive closure is captured.
        resolveSpecialPluginDependencies(project, session, dependencyCollectorBuilder,
                checksumCalculator, pluginResolver, mutableSession, repositorySystem, allRoots);

        // Phase 2: extract extras — apply GAV-level dedup and build DependencyNode entries.
        Set<String> alreadyRecordedGavs = buildRecordedGavs(allRoots, plugins, boms, extensions);
        List<io.github.chains_project.maven_lockfile.graph.DependencyNode> extraDependencies =
                ExtraArtifactResolver.extractExtras(extraTracker, alreadyRecordedGavs, checksumCalculator);

        return new LockFile(
                GroupId.of(project.getGroupId()),
                ArtifactId.of(project.getArtifactId()),
                VersionNumber.of(project.getVersion()),
                constructProjectPomChain(project, checksumCalculator),
                allRoots,
                plugins,
                boms,
                extensions,
                p2Dependencies,
                p2Repositories,
                extraDependencies,
                metadata);
    }

    private static Set<Extension> resolveExtensions(
            MavenSession session,
            MavenProject project,
            DependencyCollectorBuilder dependencyCollectorBuilder,
            AbstractChecksumCalculator checksumCalculator,
            NodeParentResolver pluginResolver,
            DefaultRepositorySystemSession aetherSession,
            RepositorySystem repositorySystem) {
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
                            Collections.emptyList(), pluginResolver, aetherSession, repositorySystem);

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

    /**
     * For every {@link SpecialPluginResolver} that returns {@code true} from
     * {@link SpecialPluginResolver#forceDependencyPopulation()}, resolves each discovered
     * dependency as a <em>standalone root</em> — not as a dep of its plugin.
     *
     * <p>This bypasses Maven's plugin-context conflict mediation so the full, unmediated
     * transitive closure of each artifact is captured. Needed for annotation processors
     * declared in {@code <annotationProcessorPaths>} whose classloader is independent of
     * the project's dependency graph — every artifact must be present regardless of the
     * project-level conflict winner.
     *
     * <p>Results are added directly to {@code allRoots} so hermeto pre-fetches them.
     */
    private static void resolveSpecialPluginDependencies(
            MavenProject project,
            MavenSession session,
            DependencyCollectorBuilder dependencyCollectorBuilder,
            AbstractChecksumCalculator checksumCalculator,
            NodeParentResolver pluginResolver,
            DefaultRepositorySystemSession aetherSession,
            RepositorySystem repositorySystem,
            Set<io.github.chains_project.maven_lockfile.graph.DependencyNode> allRoots) {

        for (SpecialPluginResolver resolver : PLUGIN_RESOLVERS) {
            if (!resolver.isApplicable(project)) continue;
            if (!resolver.forceDependencyPopulation()) continue;

            SpecialPluginResolver.DiscoveryResult result = resolver.discover(project, session);
            if (result.isEmpty()) continue;

            PluginLogManager.getLog().info(String.format(
                    "%s: force-resolving discovered artifacts as standalone roots",
                    resolver.getDisplayName()));

            for (List<Dependency> deps : result.getPluginDependencies().values()) {
                for (Dependency dep : deps) {
                    Artifact artifact = new DefaultArtifact(
                            dep.getGroupId(), dep.getArtifactId(), dep.getVersion(),
                            "compile", "jar", null, new DefaultArtifactHandler("jar"));
                    Set<io.github.chains_project.maven_lockfile.graph.DependencyNode> nodes =
                            resolvePluginDependencies(
                                    artifact, session, project, dependencyCollectorBuilder,
                                    checksumCalculator, Collections.emptyList(),
                                    pluginResolver, aetherSession, repositorySystem);
                    allRoots.addAll(nodes);
                    PluginLogManager.getLog().debug(String.format(
                            "%s: added %d node(s) for %s:%s:%s",
                            resolver.getDisplayName(), nodes.size(),
                            dep.getGroupId(), dep.getArtifactId(), dep.getVersion()));
                }
            }
        }
    }

    /**
     * Converts a list of Maven {@link org.apache.maven.artifact.repository.ArtifactRepository}
     * entries into Aether {@link org.eclipse.aether.repository.RemoteRepository} objects.
     */
    @SuppressWarnings("deprecation")
    private static List<org.eclipse.aether.repository.RemoteRepository> toAetherRemoteRepos(
            List<org.apache.maven.artifact.repository.ArtifactRepository> repos) {
        List<org.eclipse.aether.repository.RemoteRepository> result = new ArrayList<>();
        for (org.apache.maven.artifact.repository.ArtifactRepository repo : repos) {
            result.add(new org.eclipse.aether.repository.RemoteRepository.Builder(
                    repo.getId(), "default", repo.getUrl()).build());
        }
        return result;
    }

    /**
     * Calls Aether {@link RepositorySystem#resolveArtifacts} for every node in {@code nodes}
     * through the mutable session so that our {@link org.eclipse.aether.RepositoryListener}
     * fires for every JAR artifact during the main resolution pass — not only for the POMs
     * resolved by {@code collectDependencyGraph}.
     *
     * <p>All artifacts are already in the local cache at this point, so this is a warm-cache
     * pass with no network calls. Partial failures (e.g. reactor artifacts with no remote URL)
     * are logged at DEBUG level and ignored.
     */
    private static void resolveArtifactsThroughMutableSession(
            RepositorySystem repositorySystem,
            DefaultRepositorySystemSession aetherSession,
            Collection<DependencyNode> nodes,
            List<org.eclipse.aether.repository.RemoteRepository> remoteRepos) {

        List<org.eclipse.aether.resolution.ArtifactRequest> requests = new ArrayList<>();
        for (DependencyNode node : nodes) {
            Artifact a = node.getArtifact();
            if (a == null || a.getGroupId() == null || a.getVersion() == null) continue;
            requests.add(new org.eclipse.aether.resolution.ArtifactRequest(
                    new org.eclipse.aether.artifact.DefaultArtifact(
                            a.getGroupId(), a.getArtifactId(),
                            a.getClassifier(), a.getArtifactHandler().getExtension(),
                            a.getVersion()),
                    remoteRepos, null));
        }
        if (requests.isEmpty()) return;

        try {
            repositorySystem.resolveArtifacts(aetherSession, requests);
            PluginLogManager.getLog().debug(String.format(
                    "ExtraArtifacts: resolved %d artifact(s) through mutable session (listener pass)",
                    requests.size()));
        } catch (org.eclipse.aether.resolution.ArtifactResolutionException e) {
            // Partial failures are expected for reactor-local or optional artifacts.
            // artifactResolved fires per-artifact regardless of overall exception.
            PluginLogManager.getLog().debug(
                    "ExtraArtifacts: resolveArtifacts partial failures (expected for local/optional): "
                            + e.getMessage());
        }
    }

    /**
     * Builds a set of {@code groupId:artifactId:version} GAV triples for all artifacts already
     * recorded in the lockfile (dependencies + plugin artifacts + plugin deps). Used by
     * {@link ExtraArtifactResolver#extractExtras} to skip artifacts that are already captured.
     *
     * <p>GAV-level (no type/classifier) so that POM transfers for JARs already in the lockfile
     * are also filtered — e.g. {@code g:a:1.0:pom:} is skipped when {@code g:a:1.0:jar:} is
     * already recorded.
     */
    private static Set<String> buildRecordedGavs(
            Set<io.github.chains_project.maven_lockfile.graph.DependencyNode> roots,
            Set<MavenPlugin> plugins,
            Set<Pom> boms,
            Set<Extension> extensions) {
        Set<String> recorded = new HashSet<>();

        // Dependency graph — all direct + transitive deps (recursive tree walk).
        for (io.github.chains_project.maven_lockfile.graph.DependencyNode node : roots) {
            collectNodeGavs(node, recorded);
        }

        // Plugins: the plugin artifact itself, its declared dependencies, its POM chain,
        // and its BOM imports (all of which Maven reads and whose artifactResolved fires).
        for (MavenPlugin plugin : plugins) {
            String pg = plugin.getGroupId().getValue();
            String pa = plugin.getArtifactId().getValue();
            String pv = plugin.getVersion().getValue();
            recorded.add(pg + ":" + pa + ":" + pv + ":jar");
            recorded.add(pg + ":" + pa + ":" + pv + ":pom");
            for (io.github.chains_project.maven_lockfile.graph.DependencyNode dep :
                    plugin.getDependencies()) {
                collectNodeGavs(dep, recorded);
            }
            collectPomChainGavs(plugin.getPom(), recorded);
            if (plugin.getBoms() != null) {
                for (Pom pluginBom : plugin.getBoms()) {
                    collectPomChainGavs(pluginBom, recorded);
                }
            }
        }

        // BOMs listed in dependencyManagement — their full parent chains are also resolved.
        for (Pom bom : boms) {
            collectPomChainGavs(bom, recorded);
        }

        // Build extensions + their transitive dependency nodes.
        for (Extension ext : extensions) {
            String eg = ext.getGroupId().getValue();
            String ea = ext.getArtifactId().getValue();
            String ev = ext.getVersion().getValue();
            recorded.add(eg + ":" + ea + ":" + ev + ":jar");
            recorded.add(eg + ":" + ea + ":" + ev + ":pom");
            for (io.github.chains_project.maven_lockfile.graph.DependencyNode dep :
                    ext.getDependencies()) {
                collectNodeGavs(dep, recorded);
            }
        }

        return recorded;
    }

    private static void collectNodeGavs(
            io.github.chains_project.maven_lockfile.graph.DependencyNode node,
            Set<String> gavs) {
        String g = node.getGroupId().getValue();
        String a = node.getArtifactId().getValue();
        String v = node.getVersion().getValue();
        // ArtifactType.of("jar") returns null — null means jar.
        String type = node.getType() != null ? node.getType().getValue() : "jar";
        gavs.add(g + ":" + a + ":" + v + ":" + type);
        // Also register the POM type: hermeto auto-downloads the POM for every JAR artifact,
        // so we pre-filter it here to avoid a redundant extraDependencies entry.
        gavs.add(g + ":" + a + ":" + v + ":pom");
        for (io.github.chains_project.maven_lockfile.graph.DependencyNode child :
                node.getChildren()) {
            collectNodeGavs(child, gavs);
        }
    }

    /** Walks the {@link Pom} parent chain and adds each entry's GAVT ({@code :pom}) to the given set. */
    private static void collectPomChainGavs(Pom pom, Set<String> gavs) {
        for (Pom p = pom; p != null; p = p.getParent()) {
            gavs.add(p.getGroupId().getValue() + ":" + p.getArtifactId().getValue()
                    + ":" + p.getVersion().getValue() + ":pom");
        }
    }

    private static Set<MavenPlugin> getAllPlugins(
            MavenProject project,
            MavenSession session,
            DependencyCollectorBuilder dependencyCollectorBuilder,
            AbstractChecksumCalculator checksumCalculator,
            NodeParentResolver pluginResolver,
            DefaultRepositorySystemSession aetherSession,
            RepositorySystem repositorySystem) {
        Set<MavenPlugin> plugins = new TreeSet<>();

        // originalUserPluginDeps: only what the project's pom.xml declares for each plugin.
        // allUserPluginDeps: pom.xml deps + QuarkusDeploymentResolver-injected deployment deps.
        // Keeping them separate lets us do a two-phase resolution for quarkus-maven-plugin:
        //   Phase 1 (original only) → captures plugin's native dep versions (e.g. 3.27.0)
        //   Phase 2 (all)           → captures deployment artifact chain (e.g. 3.27.2)
        // The union of both phases ensures hermeto downloads all required versions.
        Map<String, List<Dependency>> originalUserPluginDeps = new HashMap<>();
        Map<String, List<Dependency>> allUserPluginDeps = new HashMap<>();
        if (project.getBuild() != null && project.getBuild().getPlugins() != null) {
            for (Plugin plugin : project.getBuild().getPlugins()) {
                String key = plugin.getGroupId() + ":" + plugin.getArtifactId();
                if (plugin.getDependencies() != null && !plugin.getDependencies().isEmpty()) {
                    originalUserPluginDeps.put(key, new ArrayList<>(plugin.getDependencies()));
                    allUserPluginDeps.put(key, new ArrayList<>(plugin.getDependencies()));
                }
            }
        }

        // Run all registered special plugin resolvers. Resolvers that produce plugin dependencies
        // inject them into allUserPluginDeps for the target plugin. Track which plugin keys had
        // deps injected so we can apply two-phase resolution for those plugins later.
        Set<String> twoPhasePluginKeys = new HashSet<>();
        for (SpecialPluginResolver resolver : PLUGIN_RESOLVERS) {
            if (!resolver.isApplicable(project)) continue;
            PluginLogManager.getLog().info(
                    resolver.getDisplayName() + " detected — running special plugin resolver");
            SpecialPluginResolver.DiscoveryResult result = resolver.discover(project, session);
            if (result.isEmpty()) continue;

            for (Map.Entry<String, List<Dependency>> entry :
                    result.getPluginDependencies().entrySet()) {
                String pluginKey = entry.getKey();
                List<Dependency> discovered = entry.getValue();
                List<Dependency> allDeps =
                        allUserPluginDeps.computeIfAbsent(pluginKey, k -> new ArrayList<>());
                Set<String> existingGas = new HashSet<>();
                for (Dependency d : allDeps) existingGas.add(d.getGroupId() + ":" + d.getArtifactId());
                for (Dependency dep : discovered) {
                    String ga = dep.getGroupId() + ":" + dep.getArtifactId();
                    if (existingGas.add(ga)) {
                        allDeps.add(dep);
                        PluginLogManager.getLog().debug(
                                resolver.getDisplayName() + ": injecting dep " + ga
                                        + " into " + pluginKey);
                    }
                }
                twoPhasePluginKeys.add(pluginKey);
            }
        }
        ProjectBuilder pluginProjectBuilder = new ProjectBuilder(session, project.getPluginArtifactRepositories());
        BomResolver pluginBomResolver = new BomResolver(session, project.getPluginArtifactRepositories(), checksumCalculator);
        for (Artifact pluginArtifact : project.getPluginArtifacts()) {
            RepositoryInformation repositoryInformation = checksumCalculator.getPluginResolvedField(pluginArtifact);
            String pluginKey = pluginArtifact.getGroupId() + ":" + pluginArtifact.getArtifactId();
            List<Dependency> originalDeps = originalUserPluginDeps.getOrDefault(pluginKey, Collections.emptyList());
            List<Dependency> allDeps = allUserPluginDeps.getOrDefault(pluginKey, Collections.emptyList());

            Set<io.github.chains_project.maven_lockfile.graph.DependencyNode> pluginDependencies;
            if (twoPhasePluginKeys.contains(pluginKey) && !originalDeps.equals(allDeps)) {
                // Two-phase resolution: union plugin's native deps (original versions) with the
                // extended discovered deps. This prevents Maven's version conflict resolution from
                // dropping the plugin's own pinned versions when discovered artifacts bring in
                // newer versions of the same artifact.
                Set<io.github.chains_project.maven_lockfile.graph.DependencyNode> nativeDeps =
                        resolvePluginDependencies(pluginArtifact, session, project,
                                dependencyCollectorBuilder, checksumCalculator, originalDeps, pluginResolver, aetherSession, repositorySystem);
                Set<io.github.chains_project.maven_lockfile.graph.DependencyNode> extendedDeps =
                        resolvePluginDependencies(pluginArtifact, session, project,
                                dependencyCollectorBuilder, checksumCalculator, allDeps, pluginResolver, aetherSession, repositorySystem);
                pluginDependencies = new HashSet<>(nativeDeps);
                pluginDependencies.addAll(extendedDeps);
            } else {
                pluginDependencies = resolvePluginDependencies(
                        pluginArtifact, session, project, dependencyCollectorBuilder,
                        checksumCalculator, allDeps, pluginResolver, aetherSession, repositorySystem);
            }
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
            NodeParentResolver pluginResolver,
            DefaultRepositorySystemSession aetherSession,
            RepositorySystem repositorySystem) {
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
            if (aetherSession != null) {
                dependencyBuildingRequest.setRepositorySession(aetherSession);
            }

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

            // Resolve plugin JAR artifacts through the mutable Aether session so our
            // RepositoryListener fires for every plugin dependency JAR.
            if (repositorySystem != null && aetherSession != null) {
                resolveArtifactsThroughMutableSession(
                        repositorySystem, aetherSession, graph.nodes(),
                        toAetherRemoteRepos(project.getPluginArtifactRepositories()));
            }

            // Conflict-loser nodes have their subtrees pruned by Maven's resolver, but their
            // transitive JARs and POMs still need to be in the local repo for offline builds.
            // Resolve each loser's subtree independently so hermeto can pre-fetch them.
            final Set<String> resolvingLosers = new HashSet<>();
            /*dependencyGraph.populateChildrenForConflictLosers(loserNode -> {
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
                        loserArtifact, session, project, dependencyCollectorBuilder, checksumCalculator,
                        Collections.emptyList(), pluginResolver, aetherSession, repositorySystem);
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
            boolean reduced,
            DefaultRepositorySystemSession aetherSession,
            RepositorySystem repositorySystem,
            NodeParentResolver artifactResolver) {
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
            if (aetherSession != null) {
                buildingRequest.setRepositorySession(aetherSession);
            }

            var rootNode = dependencyCollectorBuilder.collectDependencyGraph(buildingRequest, null);

            MutableGraph<DependencyNode> graph = GraphBuilder.directed().build();
            rootNode.accept(new GraphBuildingNodeVisitor(graph));
            PluginLogManager.getLog()
                    .info(String.format(
                            "Resolved %4d dependencies for project %s",
                            graph.nodes().size(), project));

            // Resolve all JAR artifacts through the mutable Aether session so our
            // RepositoryListener fires for every dependency JAR — not only for the POMs
            // resolved during collectDependencyGraph above.
            if (repositorySystem != null && aetherSession != null) {
                resolveArtifactsThroughMutableSession(
                        repositorySystem, aetherSession, graph.nodes(),
                        toAetherRemoteRepos(project.getRemoteArtifactRepositories()));
            }

            // Reactor nodes are now in the graph but have no remote URL — exclude them from
            // the lockfile output. Their transitive deps (all external) remain included.
            DependencyGraph dependencyGraph = DependencyGraph.of(graph, checksumCalculator, reduced, reactorGavs);

            // Conflict-loser nodes have their subtrees pruned by Maven's resolver, but their
            // transitive JARs and POMs still need to be in the local repo for offline builds.
            // Resolve each loser's subtree independently so hermeto can pre-fetch them.
            if (artifactResolver != null) {
                final Set<String> resolvingLosers = new HashSet<>();
                dependencyGraph.populateChildrenForConflictLosers(loserNode -> {
                    String gav = loserNode.getGroupId().getValue() + ":"
                            + loserNode.getArtifactId().getValue() + ":"
                            + loserNode.getVersion().getValue();
                    if (!resolvingLosers.add(gav)) {
                        return Collections.emptySet();
                    }
                    Artifact loserArtifact = new DefaultArtifact(
                            loserNode.getGroupId().getValue(),
                            loserNode.getArtifactId().getValue(),
                            loserNode.getVersion().getValue(),
                            "compile", "jar", null, new DefaultArtifactHandler("jar"));
                    return resolvePluginDependencies(
                            loserArtifact, session, project, dependencyCollectorBuilder, checksumCalculator,
                            Collections.emptyList(), artifactResolver, aetherSession, repositorySystem);
                });
            }

            return dependencyGraph;
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
