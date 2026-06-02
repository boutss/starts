/*
 * Copyright (c) 2015 - Present. The STARTS Team. All Rights Reserved.
 */

package edu.illinois.starts.jdeps;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;

import edu.illinois.starts.util.Pair;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;

import edu.illinois.starts.constants.StartsConstants;
import edu.illinois.starts.jdeps.runner.PathFinder;
import edu.illinois.starts.helpers.Writer;
import edu.illinois.starts.util.Logger;
import org.apache.maven.surefire.booter.Classpath;
import edu.illinois.yasgl.DirectedGraph;

/**
 * Affiche le plus court chemin (classe modifiee -> intermediaires -> test) dans
 * le graphe de dependances STARTS, pour comprendre pourquoi un test a ete
 * selectionne et identifier le "code glue" a refactoriser.
 *
 * <p>Trois modes via parametres (mutuellement exclusifs, evalues dans cet ordre) :
 * <ul>
 *   <li>{@code -DforClass=com.example.Foo}    : tous les chemins depuis cette classe
 *   <li>{@code -DforTest=com.example.TestBar} : tous les chemins arrivant a ce test
 *   <li>aucun                                  : tous les couples (modifiee x test affecte)
 * </ul>
 *
 * <p>Sortie : console + fichier {@code .starts/debug-paths.txt}
 */
@Mojo(name = "debug-paths", requiresDependencyResolution = ResolutionScope.TEST)
public class DebugPathsMojo extends DiffMojo implements StartsConstants {

    @Parameter(property = "forClass", defaultValue = "")
    private String forClass;

    @Parameter(property = "forTest", defaultValue = "")
    private String forTest;

    /** Limite du nombre de chemins ecrits par classe modifiee (anti-explosion). */
    @Parameter(property = "maxPathsPerClass", defaultValue = "1000")
    private int maxPathsPerClass;

    /**
     * Version de l'algorithme debug-paths. A incrementer a chaque modification
     * pour verifier facilement que la version buildee correspond bien a celle
     * attendue (affiche en console et en haut du fichier de sortie).
     */
    private static final String DEBUG_PATHS_VERSION = "v6 - maxPathsPerClass=1000 (2026-06-01)";

    @Override
    public void execute() throws MojoExecutionException {
        Logger.getGlobal().setLoggingLevel(Level.parse(loggingLevel));
        Logger logger = Logger.getGlobal();

        logger.log(Level.INFO, "");
        logger.log(Level.INFO, "==================================================");
        logger.log(Level.INFO, "  STARTS : debug-paths");
        logger.log(Level.INFO, "  " + DEBUG_PATHS_VERSION);
        logger.log(Level.INFO, "==================================================");

        // -- 1. Calcul des classes modifiees et tests affectes -----------------
        Pair<Set<String>, Set<String>> data = computeChangeData(false);
        Set<String> nonAffected         = data.getKey();
        Set<String> changedClassesUrls  = data.getValue();

        if (changedClassesUrls.isEmpty()) {
            logger.log(Level.INFO, "Aucune classe modifiee detectee. Rien a analyser.");
            return;
        }

        // changedClasses arrive au format URL fichier :
        //   file:/D:/.../target/classes/com%5cfoo%5cBar.class
        // Le graphe utilise le FQN Java : com.foo.Bar
        // Conversion necessaire pour que le BFS trouve les vertices.
        Set<String> changedClasses = new java.util.LinkedHashSet<>();
        for (String url : changedClassesUrls) {
            String fqn = urlToFqn(url);
            if (fqn != null) {
                changedClasses.add(fqn);
            }
        }
        logger.log(Level.INFO, "  [debug] Classes modifiees (FQN) : " + changedClasses);

        // -- 2. Reconstruire le graphe -----------------------------------------
        Classpath sfClassPath = getSureFireClassPath();
        String sfPathString = Writer.pathToString(sfClassPath.getClassPath());
        Result result = prepareForNextRun(
                sfPathString,
                sfClassPath,
                getAllClasses(),
                nonAffected,
                true);
        DirectedGraph<String> graph = result.getGraph();
        // En format ZLC, result.getAffectedTests() est null.
        // On reproduit la logique de TestSelector.computeAffectedTests() :
        //   affectedTests = TOUS les tests - nonAffectedTests
        setIncludesExcludes();
        @SuppressWarnings("unchecked")
        java.util.List<String> allTestsList = getTestClasses(CHECK_IF_ALL_AFFECTED);
        java.util.Set<String> affectedTests = new java.util.LinkedHashSet<>(allTestsList);
        affectedTests.removeAll(nonAffected);

        logger.log(Level.INFO, "  Classes modifiees : " + changedClasses.size());
        logger.log(Level.INFO, "  Tests affectes    : " + affectedTests.size());

        // -- 3. Filtrer selon -DforClass / -DforTest ---------------------------
        Set<String> sources = changedClasses;
        Set<String> targets = affectedTests;

        boolean hasFor = forClass != null && !forClass.isEmpty();
        boolean hasTest = forTest != null && !forTest.isEmpty();

        if (hasFor) {
            sources = filterByMatch(changedClasses, forClass);
            logger.log(Level.INFO, "  Filtre forClass='" + forClass + "' -> " + sources.size() + " classe(s)");
        }
        if (hasTest) {
            targets = filterByMatch(affectedTests, forTest);
            logger.log(Level.INFO, "  Filtre forTest='" + forTest + "' -> " + targets.size() + " test(s)");
        }

        if (sources.isEmpty() || targets.isEmpty()) {
            logger.log(Level.INFO, "Aucune classe ou aucun test apres filtrage.");
            return;
        }

        // -- 4. Calcul des chemins ---------------------------------------------
        PathFinder finder = new PathFinder(graph);

        // DEBUG : ecrire le graphe complet pour analyse et verifier le format des FQN
        java.util.Set<String> graphVertices = new java.util.HashSet<>(graph.getVertices());
        logger.log(Level.INFO, "  [debug] Vertices du graphe : " + graphVertices.size());
        int sample = 0;
        for (String v : graphVertices) {
            if (sample++ < 5) {
                logger.log(Level.INFO, "  [debug]   vertex sample : " + v);
            }
        }
        for (String modified : sources) {
            logger.log(Level.INFO, "  [debug] modified='" + modified
                    + "' present dans le graphe ? " + graphVertices.contains(modified));
        }
        for (String test : targets) {
            logger.log(Level.INFO, "  [debug] test='" + test
                    + "' present dans le graphe ? " + graphVertices.contains(test));
        }

        // Ecrire le graphe complet pour analyse (.starts/graph.txt)
        Writer.writeGraph(graph, getArtifactsDir(), true, "graph.txt");
        logger.log(Level.INFO, "  [debug] Graphe ecrit dans " + getArtifactsDir() + "/graph.txt");
        List<String> output = new ArrayList<>();
        output.add("# STARTS - debug-paths");
        output.add("# Version           : " + DEBUG_PATHS_VERSION);
        output.add("# Classes modifiees : " + changedClasses.size());
        output.add("# Tests affectes    : " + affectedTests.size());
        output.add("# Format : modifiee -> intermediaire -> ... -> test");
        output.add("");

        // PHASE 1 : pour chaque test, trouver la classe modifiee la PLUS PROCHE
        // (au sens du nombre de hops dans le graphe). Un test n'est rattache
        // qu'a une seule classe modifiee : la plus directement responsable.
        // Les inner classes du meme fichier source ne se concurrencent pas.
        java.util.Map<String, java.util.List<String>> bestPaths = new java.util.HashMap<>();
        java.util.Map<String, String> bestModifiedForTest = new java.util.HashMap<>();
        for (String test : targets) {
            int bestLen = Integer.MAX_VALUE;
            java.util.List<String> bestPath = null;
            String bestModified = null;
            for (String modified : sources) {
                java.util.List<String> path = finder.findShortestPath(modified, test);
                if (path.isEmpty()) {
                    continue;
                }
                if (path.size() < bestLen) {
                    bestLen = path.size();
                    bestPath = path;
                    bestModified = modified;
                }
            }
            if (bestPath != null) {
                bestPaths.put(test, bestPath);
                bestModifiedForTest.put(test, bestModified);
            }
        }

        // PHASE 2 : regrouper par classe modifiee
        java.util.Map<String, java.util.List<String>> testsPerModified = new java.util.LinkedHashMap<>();
        for (String modified : sources) {
            testsPerModified.put(modified, new java.util.ArrayList<>());
        }
        for (java.util.Map.Entry<String, String> e : bestModifiedForTest.entrySet()) {
            testsPerModified.get(e.getValue()).add(e.getKey());
        }

        // PHASE 3 : ecriture
        int totalPaths = 0;
        for (String modified : sources) {
            java.util.List<String> tests = testsPerModified.get(modified);
            if (tests.isEmpty()) {
                output.add("## " + modified + " -> aucun test propre (tous attaches a une autre classe modifiee plus proche)");
                output.add("");
                continue;
            }
            output.add("## " + modified + " -> " + tests.size() + " test(s)");
            int count = 0;
            for (String test : tests) {
                if (count >= maxPathsPerClass) {
                    output.add("  (... " + (tests.size() - count)
                                       + " test(s) supplementaire(s) tronque(s), augmenter -DmaxPathsPerClass)");
                    break;
                }
                output.add("  " + String.join(" -> ", bestPaths.get(test)));
                totalPaths++;
                count++;
            }
            output.add("");
        }

        // -- 5. Ecriture fichier + console -------------------------------------
        File startsDir = new File(getProject().getBasedir(), ".starts");
        startsDir.mkdirs();
        File outputFile = new File(startsDir, "debug-paths.txt");

        try {
            Files.write(outputFile.toPath(), output, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new MojoExecutionException("Impossible d'ecrire " + outputFile.getAbsolutePath()
                                                     + " : " + e.getMessage(), e);
        }

        // Afficher en console
        output.forEach(line -> logger.log(Level.INFO, line));

        logger.log(Level.INFO, "");
        logger.log(Level.INFO, "==================================================");
        logger.log(Level.INFO, "  " + totalPaths + " chemin(s) ecrit(s) dans :");
        logger.log(Level.INFO, "  " + outputFile.getAbsolutePath());
        logger.log(Level.INFO, "==================================================");
    }

    /**
     * Filtre un ensemble de FQN par sous-chaine (matche soit le FQN complet,
     * soit le nom simple en fin de FQN).
     */
    /**
     * Convertit une URL fichier ZLC (avec %5c pour les separateurs) en FQN Java.
     * Exemple :
     *   file:/D:/.../target/classes/com%5cfoo%5cBar.class
     *   -> com.foo.Bar
     */
    /**
     * Retourne le nom de l'unite de compilation d'une classe :
     * le FQN sans suffixe d'inner class.
     *
     * <p>Exemples :
     * <pre>
     *   com.foo.Bar       -> com.foo.Bar
     *   com.foo.Bar$1     -> com.foo.Bar
     *   com.foo.Bar$Inner -> com.foo.Bar
     *   com.foo.Bar$Inner$1 -> com.foo.Bar
     * </pre>
     */
    private static String sourceUnitOf(String fqn) {
        int dollar = fqn.indexOf('$');
        return dollar >= 0 ? fqn.substring(0, dollar) : fqn;
    }

    private static String urlToFqn(String url) {
        if (url == null) {
            return null;
        }
        int classesIdx = url.indexOf("/classes/");
        int testIdx    = url.indexOf("/test-classes/");
        int start;
        if (testIdx >= 0) {
            start = testIdx + "/test-classes/".length();
        } else if (classesIdx >= 0) {
            start = classesIdx + "/classes/".length();
        } else {
            return null;
        }
        String rel = url.substring(start);
        if (rel.endsWith(".class")) {
            rel = rel.substring(0, rel.length() - 6);
        }
        // Remplacer les separateurs encodes (%5c, %2f) et bruts par des points
        rel = rel.replace("%5c", ".")
                .replace("%5C", ".")
                .replace("%2f", ".")
                .replace("%2F", ".")
                .replace('/', '.')
                .replace('\\', '.');
        return rel;
    }

    private static Set<String> filterByMatch(Set<String> items, String pattern) {
        Set<String> result = new java.util.LinkedHashSet<>();
        for (String item : items) {
            if (item.equals(pattern) || item.endsWith("." + pattern) || item.contains(pattern)) {
                result.add(item);
            }
        }
        return result;
    }
}