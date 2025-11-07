/*
 * Copyright (c) 2015 - Present. The STARTS Team. All Rights Reserved.
 */

package edu.illinois.starts.helpers;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

import edu.illinois.starts.constants.StartsConstants;
import edu.illinois.starts.util.ChecksumUtil;
import edu.illinois.starts.util.Logger;
import edu.illinois.yasgl.DirectedGraph;
import edu.illinois.yasgl.DirectedGraphBuilder;
import org.apache.maven.surefire.booter.Classpath;
import org.ekstazi.util.Types;

/**
 * Utility methods for loading several things from disk.
 */
public class Loadables implements StartsConstants {
    private static final Logger LOGGER = Logger.getGlobal();

    Map<String, Set<String>> deps;
    List<String> extraEdges;
    private List<String> classesToAnalyze;
    private File cache;
    private String sfPathString;
    private DirectedGraph<String> graph;
    private Map<String, Set<String>> transitiveClosure;
    private Set<String> unreached;
    private boolean filterLib;
    private boolean useThirdParty;
    private Classpath surefireClasspath;
    private String artifactsDir;

    public Loadables(List<String> classesToAnalyze, String artifactsDir, String sfPathString,
                     boolean useThirdParty, boolean filterLib, File cache) {
        this.classesToAnalyze = classesToAnalyze;
        this.artifactsDir = artifactsDir;
        this.sfPathString = sfPathString;
        this.filterLib = filterLib;
        this.cache = cache;
        this.useThirdParty = useThirdParty;
    }

    public DirectedGraph<String> getGraph() {
        return graph;
    }

    public Map<String, Set<String>> getTransitiveClosure() {
        return transitiveClosure;
    }

    public Set<String> getUnreached() {
        return unreached;
    }

    public List<String> getClasspathWithNoJars() {
        // There is a cache of all third party libraries, remove third-party jars from jdeps classpath
        // ASSUMPTION: local dependencies (modules in the same mvn project) are directories, not jars
        List<String> localPaths = new ArrayList<>();
        if (surefireClasspath != null) {
            for (String path : surefireClasspath.getClassPath()) {
                if (!path.endsWith(JAR_EXTENSION) && new File(path).exists()) {
                    localPaths.add(path);
                }
            }
        }
        return localPaths;
    }

    public Loadables create(List<String> moreEdges, Classpath sfClassPath,
                            boolean computeUnreached) {
        setSurefireClasspath(sfClassPath);
        LOGGER.log(Level.FINEST, "More: " + moreEdges.size());
        extraEdges = moreEdges;
        long startTime = System.currentTimeMillis();
        deps = getDepMap(sfPathString, classesToAnalyze);
        long jdepsTime = System.currentTimeMillis();
        graph = makeGraph(deps, extraEdges);
        long graphBuildingTime = System.currentTimeMillis();
        transitiveClosure = getTransitiveClosurePerClass(graph, classesToAnalyze);
        long transitiveClosureTime = System.currentTimeMillis();
        if (computeUnreached) {
            unreached = findUnreached(deps, transitiveClosure);
            LOGGER.log(Level.INFO, "Classes inaccessible (count): " + unreached.size());
        }
        long findUnreachedTime = System.currentTimeMillis();
        long endTime = System.currentTimeMillis();
        LOGGER.log(Level.INFO, "[PROFILE] createLoadable(runJDeps): " + Writer.millsToLog(jdepsTime - startTime));
        LOGGER.log(Level.INFO, "[PROFILE] createLoadable(buildGraph): "
                + Writer.millsToLog(graphBuildingTime - jdepsTime));
        LOGGER.log(Level.INFO, "[PROFILE] createLoadable(transitiveClosure): "
                + Writer.millsToLog(transitiveClosureTime - graphBuildingTime));
        LOGGER.log(Level.INFO, "[PROFILE] createLoadable(findUnreached): "
                + Writer.millsToLog(endTime - findUnreachedTime));
        LOGGER.log(Level.INFO, "[PROFILE] createLoadable(TOTAL): " + Writer.millsToLog(endTime - startTime));
        LOGGER.log(Level.INFO, "STARTS:Nodes: " + graph.getVertices().size());
        LOGGER.log(Level.INFO, "STARTS:Edges: " + graph.getEdges().size());
        return this;
    }

    /**
     * This method takes (i) the dependencies that jdeps found and (i) the map from tests to reachable
     * types in the graph, and uses these to find types jdeps found but which are not reachable by any test.
     * @param deps      The dependencies that jdeps found.
     * @param testDeps  The map from test to types that can be reached in the graph.
     * @return          The set of types that are not reachable by any test in the graph.
     */
    private Set<String> findUnreached(Map<String, Set<String>> deps,
                                      Map<String, Set<String>> testDeps) {
        Set<String> allClasses = new HashSet<>();
        for (String loc : deps.keySet()) {
            // 1. jdeps finds no dependencies for a class if the class' dependencies were not analyzed (e.g., no -R)
            // 2. every class in the CUT has non-empty jdeps dependency; they , at least, depend on java.lang.Object
            // 3. isWellKnownUrl will ignore classes from junit, hamcrest, maven, etc; we don't want to track those
            // 4. isIgnorableInternalName will ignore classes from standard library, mockito, jacoco
            String className = ChecksumUtil.toClassName(loc);
            if (!deps.get(loc).isEmpty()
                    || !ChecksumUtil.isWellKnownUrl(className)
                    || !Types.isIgnorableInternalName(className)) {
                // this means that this a class we want to track, either because it is in the CUT
                // or in some jar that we are tracking
                allClasses.add(loc);
            }
        }
        LOGGER.log(Level.INFO, "ALL classes(count): " + allClasses.size());
        Set<String> reached = new HashSet<>(testDeps.keySet());
        for (String test : testDeps.keySet()) {
            reached.addAll(testDeps.get(test));
        }
        // remove the reached classes from allClasses to get the unreached classes.
        allClasses.removeAll(reached);
        return allClasses;
    }

    private DirectedGraph<String> makeGraph(Map<String, Set<String>> deps,
                                            List<String> moreEdges) {
        DirectedGraphBuilder<String> builder = getBuilderFromDeps(deps, true, true);
        addEdgesToGraphBuilder(builder, moreEdges);
        return builder.build();
    }


    public DirectedGraphBuilder<String> getBuilderFromDeps(
            Map<String, Set<String>> deps,
            boolean excludeNoise,
            boolean bypassTypeNodes
    ) {
        // Copie défensive
        Map<String, Set<String>> graph = new HashMap<>(deps.size());
        for (Map.Entry<String, Set<String>> e : deps.entrySet()) {
            graph.put(e.getKey(), e.getValue() == null ? new HashSet<>() : new HashSet<>(e.getValue()));
        }

        // 1) Filtrage du bruit (libs de tests/logging + nls/utils)
        if (excludeNoise) {
            graph.entrySet().removeIf(entry -> isNoiseNode(entry.getKey()));
            for (Map.Entry<String, Set<String>> entry : graph.entrySet()) {
                entry.getValue().removeIf(this::isNoiseNode);
            }
        }

        // 2) Bypass des nœuds *.type.* (supprime l’effet “colle” en reconnectant prédécesseurs → successeurs)
        if (bypassTypeNodes) {
            graph = bypassTypeNodes(graph);
        }

        // 3) Construction du graphe
        DirectedGraphBuilder<String> builder = new DirectedGraphBuilder<>();
        for (Map.Entry<String, Set<String>> e : graph.entrySet()) {
            String source = e.getKey();
            Set<String> targets = e.getValue();

            if (targets == null || targets.isEmpty()) {
                // important: conserver les sommets isolés
                builder.addVertex(source);
                continue;
            }
            for (String target : targets) {
                builder.addEdge(source, target);
            }
        }

        return builder;
    }

    private boolean isNoiseNode(String fqcn) {
        String s = fqcn.toLowerCase(Locale.ROOT);
        return s.startsWith("java.")
                || s.startsWith("javax.")
                || s.startsWith("jakarta.")
                || s.startsWith("org.slf4j.")
                || s.startsWith("ch.qos.logback.")
                || s.startsWith("org.apache.logging.")
                || s.startsWith("org.junit.")
                || s.startsWith("org.mockito.")
                || s.startsWith("org.assertj.")
                || s.startsWith("com.fasterxml.")
                || s.contains(".nls.")
                || s.contains(".utils.");
        // on NE met pas ".type." ici : on le traite via le bypass pour garder la connectivité métier utile
    }

    private Map<String, Set<String>> bypassTypeNodes(Map<String, Set<String>> graph) {
        // Construire les prédécesseurs
        Map<String, Set<String>> predecessors = new HashMap<>();
        for (Map.Entry<String, Set<String>> e : graph.entrySet()) {
            String source = e.getKey();
            for (String target : e.getValue()) {
                predecessors.computeIfAbsent(target, k -> new HashSet<>()).add(source);
            }
            predecessors.computeIfAbsent(source, k -> new HashSet<>());
        }

        // Lister les nœuds .type.
        Set<String> typeNodes = new HashSet<>();
        for (String node : graph.keySet()) {
            if (node.toLowerCase(Locale.ROOT).contains(".type.")) {
                typeNodes.add(node);
            }
        }
        if (typeNodes.isEmpty()) {
            return graph;
        }

        // Bypass P->T->S => P->S (sans garder T)
        // garde-fou en cas de nœud .type. hyper-connecté
        final long bypassEdgeLimit = 200_000L;

        for (String typeNode : typeNodes) {
            Set<String> preds = predecessors.getOrDefault( typeNode, Collections.emptySet());
            Set<String> succs = graph.getOrDefault(typeNode, Collections.emptySet());

            long potential = (long) preds.size() * (long) succs.size();
            boolean tooBig = potential > bypassEdgeLimit;

            if (!preds.isEmpty() && !succs.isEmpty() && !tooBig) {
                for (String pred : preds) {
                    Set<String> out = graph.computeIfAbsent(pred, k -> new HashSet<>());
                    for (String succ : succs) {
                        if (!pred.equals(succ) && !succ.toLowerCase(Locale.ROOT).contains(".type.")) {
                            out.add(succ);
                        }
                    }
                }
            }
            // si "tooBig", on ne reconnecte pas (on coupe ce hub .type. spécifique)
        }

        // Supprimer T et toutes les références vers T
        for (String t : typeNodes) {
            graph.remove(t);
        }
        for (Map.Entry<String, Set<String>> e : graph.entrySet()) {
            e.getValue().removeIf(typeNodes::contains);
        }

        return graph;
    }


    public Map<String, Set<String>> getDepMap(String pathToUse, List<String> classes)
            throws IllegalArgumentException {
        if (classes.isEmpty()) {
            //There are no test classes, no need to waste time with jdeps
            return null;
        }
        List<String> args = new ArrayList<>(Arrays.asList("-v"));
        if (filterLib) {
            // TODO: We need a cleaner/generic way to add filters
            args.addAll(Arrays.asList("-filter", "java.*|sun.*"));
        }
        List<String> localPaths = getClasspathWithNoJars();
        if (localPaths.isEmpty()) {
            throw new IllegalArgumentException("JDEPS cannot run with an empty classpath.");
        }
        String jdepsClassPath;
        if ((!cache.exists() || (cache.isDirectory() && cache.list().length == 0)) && useThirdParty) {
            //There is no cache of jdeps graphs, so we want to run jdeps recursively with the entire surefire classpath
            LOGGER.log(Level.WARNING, "Should jdeps cache really be empty? Running in recursive mode.");
            args.add("-R");
            jdepsClassPath = pathToUse;
        } else {
            jdepsClassPath = Writer.pathToString(localPaths);
        }
        args.addAll(Arrays.asList("-cp", jdepsClassPath));
        args.addAll(localPaths);
        LOGGER.log(Level.FINEST, "JDEPS CMD: " + args);
        Map<String, Set<String>> depMap = RTSUtil.runJdeps(args);
        if (LOGGER.getLoggingLevel().intValue() == Level.FINEST.intValue()) {
            Writer.writeMapToFile(depMap, artifactsDir + File.separator + "jdeps-out");
        }
        return depMap;
    }

    private void addEdgesToGraphBuilder(DirectedGraphBuilder<String> builder, List<String> edges) {
        for (String edge : edges) {
            String[] parts = edge.split(WHITE_SPACE);
            if (parts.length != 2) {
                LOGGER.log(Level.SEVERE, "@@BrokenEdge: " + edge);
                continue;
            }
            String src = parts[0].intern();
            String dest = parts[1].intern();
            builder.addEdge(src, dest);
        }
    }

    public static Map<String, Set<String>> getTransitiveClosurePerClass(
            DirectedGraph<String> graph,
            List<String> classesToAnalyze
    ) {
        // 1) Indexer les sommets
        List<String> allVertices = new ArrayList<>(graph.getVertices());
        Map<String, Integer> nodeToId = new HashMap<>(allVertices.size());
        for (int nodeIndex = 0; nodeIndex < allVertices.size(); nodeIndex++) {
            nodeToId.put(allVertices.get(nodeIndex), nodeIndex);
        }
        final int vertexCount = allVertices.size();

        // 2) Construire et cacher l’adjacence en ids (une seule fois)
        int[][] successorsById = new int[vertexCount][];
        for (int nodeIndex = 0; nodeIndex < vertexCount; nodeIndex++) {
            String nodeName = allVertices.get(nodeIndex);
            Collection<String> successors = graph.getSuccessors(nodeName);
            int[] succIds = new int[successors.size()];
            int writePos = 0;
            for (String succName : successors) {
                Integer succId = nodeToId.get(succName);
                if (succId != null) {
                    succIds[writePos++] = succId;
                }
            }
            if (writePos != succIds.length) {
                succIds = Arrays.copyOf(succIds, writePos);
            }
            successorsById[nodeIndex] = succIds;
        }

        // 3) Mémoisation des fermetures
        BitSet[] memoClosure = new BitSet[vertexCount];
        boolean[] computed = new boolean[vertexCount];

        // 4) Résolution itérative en post-ordre (pas de récursion), avec mémo
        class Solver {
            BitSet solve(int startId) {
                if (computed[startId]) {
                    return memoClosure[startId];
                }
                // Pile pour DFS explicite : (nodeId, nextChildIdx)
                ArrayDeque<int[]> stack = new ArrayDeque<>();
                ArrayDeque<Integer> postOrder = new ArrayDeque<>();
                boolean[] onStack = new boolean[vertexCount];

                stack.push(new int[]{startId, 0});
                onStack[startId] = true;

                while (!stack.isEmpty()) {
                    int[] frame = stack.peek();
                    int nodeId = frame[0];
                    int nextChildIdx = frame[1];

                    if (computed[nodeId]) {
                        stack.pop();
                        continue;
                    }

                    int[] succIds = successorsById[nodeId];
                    if (nextChildIdx < succIds.length) {
                        int childId = succIds[nextChildIdx];
                        frame[1] = nextChildIdx + 1;

                        if (!computed[childId] && !onStack[childId]) {
                            stack.push(new int[]{childId, 0});
                            onStack[childId] = true;
                        }
                        // Si le child est déjà computed ou en cours, on avance.
                    } else {
                        // Tous les enfants sont traités => post-traitement du nœud
                        postOrder.push(nodeId);
                        stack.pop();
                    }
                }

                // Post-ordre : on construit les closures une seule fois par nœud
                while (!postOrder.isEmpty()) {
                    int nodeId = postOrder.pop();
                    BitSet closure = new BitSet(vertexCount);
                    // S’inclure soi-même
                    closure.set(nodeId);
                    // Union des closures enfants
                    for (int childId : successorsById[nodeId]) {
                        BitSet childClosure = memoClosure[childId];
                        if (childClosure != null) {
                            closure.or(childClosure);
                        } else {
                            // Cas cycle en cours de résolution : à ce stade, les enfants
                            // devraient être construits, mais par sécurité on ajoute le child.
                            closure.set(childId);
                        }
                    }
                    memoClosure[nodeId] = closure;
                    computed[nodeId] = true;
                }
                return memoClosure[startId];
            }
        }

        Solver solver = new Solver();

        // 5) Construire le résultat uniquement pour classesToAnalyze
        Map<String, Set<String>> result = new HashMap<>(classesToAnalyze.size());
        for (String className : classesToAnalyze) {
            Integer startId = nodeToId.get(className);
            if (startId == null) {
                // Classe absente du graphe : retourne au moins elle-même
                result.put(className, Set.of(className));
                continue;
            }
            BitSet closureBits = solver.solve(startId);
            // Matérialiser en Set<String> (si nécessaire)
            int expected = closureBits.cardinality();
            Set<String> deps = new HashSet<>((int) (expected / 0.75f) + 1);
            for (int bit = closureBits.nextSetBit(0); bit >= 0; bit = closureBits.nextSetBit(bit + 1)) {
                deps.add(allVertices.get(bit));
            }
            // Conforme à ton code : on s’assure d’ajouter la classe d’origine
            deps.add(className);
            result.put(className, deps);
        }
        return result;
    }



    public void setSurefireClasspath(Classpath surefireClasspath) {
        this.surefireClasspath = surefireClasspath;
    }
}
