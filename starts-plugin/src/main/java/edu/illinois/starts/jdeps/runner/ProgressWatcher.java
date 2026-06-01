/*
 * Copyright (c) 2015 - Present. The STARTS Team. All Rights Reserved.
 */

package edu.illinois.starts.jdeps.runner;

import java.io.PrintStream;
import java.util.HashSet;
import java.util.Set;

/**
 * Affiche la progression des tests en temps reel dans la console.
 *
 * <p>Recoit les lignes de sortie Maven via {@link #onMavenLine(String)} et detecte
 * les lignes "Tests run: ... - in com.example.TestFoo" emises par Surefire/Failsafe
 * a chaque classe terminee. Chaque classe est comptee une seule fois.
 */
public class ProgressWatcher {

    private static final int LINE_WIDTH = 100;

    private final int         total;
    private final PrintStream console;
    private final Set<String> completedClasses = new HashSet<>();
    private String           lastClassName    = "";

    /**
     * @param total   nombre total de classes de test attendues
     * @param console flux console direct (non intercepte par Maven)
     */
    public ProgressWatcher(int total, PrintStream console) {
        this.total   = total;
        this.console = console;
    }

    /** Affiche le message initial avant le lancement de Maven. */
    public void start() {
        showPhase("demarrage Maven");
    }

    private void showPhase(String phase) {
        String progress = "  [" + completedClasses.size() + "/" + total + "] " + phase + "...";
        if (progress.length() < LINE_WIDTH) {
            StringBuilder sb = new StringBuilder(progress);
            while (sb.length() < LINE_WIDTH) {
                sb.append(' ');
            }
            progress = sb.toString();
        }
        console.print("\r" + progress);
        console.flush();
    }

    /** Termine proprement la ligne de progression. */
    public void stop() {
        // Forcer un dernier affichage et passer a la ligne
        display();
        console.println();
        int skipped = total - completedClasses.size();
        if (skipped > 0) {
            console.println("  (" + skipped
                                    + " classe(s) sans test execute : classes abstraites, utilitaires ou sans @Test)");
        }
    }

    /**
     * A appeler pour chaque ligne emise par Maven. Detecte les lignes Surefire/Failsafe
     * "Tests run: ... - in com.example.TestFoo" et incremente le compteur.
     *
     * @param line une ligne de la sortie Maven
     */
    public void onMavenLine(String line) {
        if (line == null) {
            return;
        }

        // 1. Detecter les phases Maven : "[INFO] --- maven-compiler-plugin:..."
        //    pour afficher ce que fait Maven pendant l'attente des tests.
        if (completedClasses.isEmpty() && line.contains("--- ") && line.contains("-plugin:")) {
            String phase = extractPhase(line);
            if (phase != null) {
                showPhase(phase);
            }
            return;
        }

        // 2. Detecter les classes terminees. Selon la version de Surefire :
        //    - Surefire 2.x : "Tests run: ... - in com.example.TestFoo"
        //    - Surefire 3.x : "Tests run: ... -- in com.example.TestFoo"
        if (!line.contains("Tests run:")) {
            return;
        }
        int idx;
        int sepLen;
        int ddIdx = line.indexOf(" -- in ");
        if (ddIdx >= 0) {
            idx = ddIdx;
            sepLen = 7; // " -- in "
        } else {
            int sdIdx = line.indexOf(" - in ");
            if (sdIdx < 0) {
                return;
            }
            idx = sdIdx;
            sepLen = 6; // " - in "
        }
        String fqn = line.substring(idx + sepLen).trim();
        if (completedClasses.add(fqn)) {
            lastClassName = fqn;
            display();
        }
    }

    /**
     * Extrait le nom court d'une phase Maven depuis une ligne de la forme :
     * "[INFO] --- maven-compiler-plugin:3.8.1:compile (default-compile) @ archi-app ---"
     * -> "compile"
     */
    private static String extractPhase(String line) {
        int start = line.indexOf("-plugin:");
        if (start < 0) {
            return null;
        }
        // Chercher le ":" apres la version
        int versionEnd = line.indexOf(':', start + 8);
        if (versionEnd < 0) {
            return null;
        }
        int goalEnd = line.indexOf(' ', versionEnd + 1);
        if (goalEnd < 0) {
            goalEnd = line.length();
        }
        return line.substring(versionEnd + 1, goalEnd).trim();
    }

    private void display() {
        int count = completedClasses.size();
        String lastName = "";
        if (!lastClassName.isEmpty()) {
            int dot = lastClassName.lastIndexOf('.');
            lastName = dot >= 0 ? lastClassName.substring(dot + 1) : lastClassName;
        }

        String progress = "  [" + count + "/" + total + "] " + lastName;
        if (progress.length() < LINE_WIDTH) {
            StringBuilder sb = new StringBuilder(progress);
            while (sb.length() < LINE_WIDTH) {
                sb.append(' ');
            }
            progress = sb.toString();
        }
        console.print("\r" + progress);
        console.flush();
    }
}