/*
 * Copyright (c) 2015 - Present. The STARTS Team. All Rights Reserved.
 */

package edu.illinois.starts.jdeps.runner;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import edu.illinois.yasgl.DirectedGraph;
import edu.illinois.yasgl.Edge;

/**
 * Calcule le plus court chemin entre une classe modifiee et un test affecte
 * dans le graphe de dependances STARTS.
 *
 * <p>Utilise un BFS sur la matrice d'adjacence inverse : on part du test
 * et on remonte vers les classes modifiees. Le chemin est ensuite inverse
 * pour afficher : classe_modifiee -> intermediaire -> ... -> test.
 */
public class PathFinder {

    /** classe -> classes qui en dependent (predecesseurs dans le graphe). */
    private final Map<String, Set<String>> reverseAdjacency = new HashMap<>();
    /** classe -> classes vers lesquelles elle pointe (successeurs). */
    private final Map<String, Set<String>> forwardAdjacency = new HashMap<>();

    /**
     * Construit le PathFinder a partir du graphe STARTS.
     *
     * @param graph le graphe de dependances (DirectedGraph YASGL)
     */
    public PathFinder(DirectedGraph<String> graph) {
        for (Edge<String> edge : graph.getEdges()) {
            String src = edge.getSource();
            String dst = edge.getDestination();
            forwardAdjacency.computeIfAbsent(src, k -> new HashSet<>()).add(dst);
            reverseAdjacency.computeIfAbsent(dst, k -> new HashSet<>()).add(src);
        }
    }

    /**
     * Trouve le plus court chemin du test vers la classe modifiee dans le graphe inverse,
     * et retourne le chemin dans le sens "modifiee -> ... -> test".
     *
     * @param modifiedClass FQN de la classe modifiee (point de depart logique)
     * @param test          FQN du test affecte (point d'arrivee logique)
     * @return liste de FQN du chemin, ou liste vide si aucun chemin
     */
    public List<String> findShortestPath(String modifiedClass, String test) {
        return findShortestPath(modifiedClass, test, Collections.emptySet());
    }

    /**
     * Trouve le plus court chemin de {@code modifiedClass} vers {@code test} en
     * EXCLUANT les noeuds {@code forbiddenIntermediates} comme intermediaires
     * (ils peuvent etre source ou destination).
     *
     * <p>Utile pour eviter qu'un chemin passe par une autre classe modifiee :
     * dans ce cas le test serait selectionne meme sans la classe en cours d'analyse,
     * et le chemin n'aide pas a comprendre pourquoi CETTE classe le tire.
     *
     * @param modifiedClass          point de depart
     * @param test                   point d'arrivee
     * @param forbiddenIntermediates noeuds a ne pas traverser (sauf en debut/fin)
     * @return chemin ou liste vide si aucun chemin sans traverser un noeud interdit
     */
    public List<String> findShortestPath(String modifiedClass, String test,
                                         Set<String> forbiddenIntermediates) {
        if (test.equals(modifiedClass)) {
            return Collections.singletonList(test);
        }

        // STARTS construit le graphe ainsi : si A depend de B, arete A -> B.
        // Donc pour trouver les tests qui dependent de modifiedClass, on part
        // de modifiedClass et on suit les aretes INVERSES (les classes qui
        // pointent vers nous), jusqu'a atteindre test.
        Map<String, String> parents = new HashMap<>();
        Set<String> visited = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();

        queue.add(modifiedClass);
        visited.add(modifiedClass);

        boolean found = false;
        while (!queue.isEmpty()) {
            String current = queue.poll();
            Set<String> dependents = reverseAdjacency.get(current);
            if (dependents == null) {
                continue;
            }
            for (String dep : dependents) {
                if (visited.add(dep)) {
                    parents.put(dep, current);
                    if (dep.equals(test)) {
                        found = true;
                        break;
                    }
                    // Ne pas traverser les noeuds interdits comme intermediaires
                    if (forbiddenIntermediates.contains(dep)) {
                        continue;
                    }
                    queue.add(dep);
                }
            }
            if (found) {
                break;
            }
        }

        if (!found) {
            return Collections.emptyList();
        }

        // Reconstruire en remontant les parents : test -> ... -> modifiedClass
        // Puis inverser pour avoir modifiedClass -> ... -> test
        List<String> reversed = new ArrayList<>();
        String node = test;
        while (node != null) {
            reversed.add(node);
            if (node.equals(modifiedClass)) {
                break;
            }
            node = parents.get(node);
        }
        Collections.reverse(reversed);
        return reversed;
    }

    /**
     * Pour une classe modifiee donnee, trouve tous les tests qu'elle atteint
     * (parmi un ensemble de tests candidats).
     *
     * @param modifiedClass classe modifiee
     * @param candidateTests ensemble des tests potentiellement affectes
     * @return sous-ensemble de candidateTests atteignables depuis modifiedClass
     */
    public Set<String> findReachableTests(String modifiedClass, Set<String> candidateTests) {
        return findReachableTests(modifiedClass, candidateTests, Collections.emptySet());
    }

    /**
     * Variante avec exclusion : ne compte que les tests atteignables SANS
     * traverser un noeud {@code forbiddenIntermediates}.
     */
    public Set<String> findReachableTests(String modifiedClass, Set<String> candidateTests,
                                          Set<String> forbiddenIntermediates) {
        Set<String> reachable = new HashSet<>();
        Set<String> visited = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(modifiedClass);
        visited.add(modifiedClass);

        while (!queue.isEmpty()) {
            String current = queue.poll();
            if (candidateTests.contains(current)) {
                reachable.add(current);
            }
            Set<String> dependents = reverseAdjacency.get(current);
            if (dependents == null) {
                continue;
            }
            for (String dep : dependents) {
                if (visited.add(dep)) {
                    // Ne pas traverser les noeuds interdits comme intermediaires
                    // (mais on peut quand meme les compter s'ils sont des tests cibles)
                    if (forbiddenIntermediates.contains(dep)) {
                        if (candidateTests.contains(dep)) {
                            reachable.add(dep);
                        }
                        continue;
                    }
                    queue.add(dep);
                }
            }
        }
        return reachable;
    }
}