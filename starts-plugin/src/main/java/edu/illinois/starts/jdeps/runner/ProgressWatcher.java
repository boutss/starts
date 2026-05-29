/*
 * Copyright (c) 2015 - Present. The STARTS Team. All Rights Reserved.
 */

package edu.illinois.starts.jdeps.runner;

import java.io.File;
import java.io.PrintStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Surveille un dossier de rapports Surefire/Failsafe en arriere-plan et affiche
 * la progression en temps reel dans la console.
 *
 * <p>Surefire ecrit un fichier {@code TEST-com.example.TestFoo.xml} dans
 * {@code surefire-reports/} a chaque classe terminee. Ce watcher compte ces
 * fichiers toutes les 500ms et met a jour la ligne de progression.
 *
 * <p>Utilisation :
 * <pre>
 *   ProgressWatcher watcher = new ProgressWatcher(reportsDir, total, CONSOLE);
 *   watcher.start();
 *   // ... lancer Maven ...
 *   watcher.stop();
 * </pre>
 */
public class ProgressWatcher {

    private final File        reportsDir;
    private final int         total;
    private final PrintStream console;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ScheduledExecutorService scheduler;
    private int                 lastCount       = -1;
    /** Largeur de ligne pour effacer le contenu precedent. */
    private static final int    LINE_WIDTH      = 100;
    /** nom -> lastModified au moment du start() */
    private Map<String, Long>   snapshotAtStart = new HashMap<>();
    /** noms deja comptes comme "nouveaux" pendant ce run (evite les doublons). */
    private Set<String>         countedNames    = new HashSet<>();

    /**
     * @param reportsDir dossier surefire-reports ou failsafe-reports
     * @param total      nombre total de classes de test attendues
     * @param console    flux console direct (non intercepte par Maven)
     */
    public ProgressWatcher(File reportsDir, int total, PrintStream console) {
        this.reportsDir = reportsDir;
        this.total      = total;
        this.console    = console;
    }

    /** Demarre la surveillance en arriere-plan. */
    public void start() {
        // Snapshot nom -> lastModified des fichiers deja presents
        if (reportsDir.exists()) {
            File[] existing = reportsDir.listFiles(
                    f -> f.getName().startsWith("TEST-") && f.getName().endsWith(".xml"));
            if (existing != null) {
                for (File f : existing) {
                    snapshotAtStart.put(f.getName(), f.lastModified());
                }
            }
        }
        running.set(true);
        console.print("  [0/" + total + "] en attente...");
        console.flush();
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "starts-progress-watcher");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::update, 0, 500, TimeUnit.MILLISECONDS);
    }

    /** Arrete la surveillance et affiche le bilan final. */
    public void stop() {
        running.set(false);
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        // Afficher l'etat final
        update();
    }

    private void update() {
        if (!reportsDir.exists()) {
            return;
        }
        // Lister les fichiers TEST-*.xml nouveaux ou modifies, en dedoublonnant par nom
        File[] xmlFiles = reportsDir.listFiles(f -> {
            if (!f.getName().startsWith("TEST-") || !f.getName().endsWith(".xml")) {
                return false;
            }
            Long prevModified = snapshotAtStart.get(f.getName());
            return prevModified == null || f.lastModified() > prevModified;
        });
        if (xmlFiles != null) {
            for (File f : xmlFiles) {
                countedNames.add(f.getName());
            }
        }
        int count = countedNames.size();

        if (count != lastCount) {
            lastCount = count;
            // Extraire le nom de la derniere classe completee
            String lastName = "";
            if (xmlFiles != null && xmlFiles.length > 0) {
                File latest = xmlFiles[0];
                for (File f : xmlFiles) {
                    if (f.lastModified() > latest.lastModified()) {
                        latest = f;
                    }
                }
                // TEST-com.example.TestFoo.xml -> TestFoo
                String name = latest.getName()
                        .replace("TEST-", "")
                        .replace(".xml", "");
                int dot = name.lastIndexOf('.');
                lastName = dot >= 0 ? name.substring(dot + 1) : name;
            }

            String progress = "  [" + count + "/" + total + "] " + lastName;
            // Padder a LINE_WIDTH pour effacer la fin de la ligne precedente
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
}