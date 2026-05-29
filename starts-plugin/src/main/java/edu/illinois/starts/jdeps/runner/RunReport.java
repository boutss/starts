/*
 * Copyright (c) 2015 - Present. The STARTS Team. All Rights Reserved.
 */

package edu.illinois.starts.jdeps.runner;

import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.logging.Level;

import edu.illinois.starts.helpers.Writer;
import edu.illinois.starts.util.Logger;

/**
 * Gere les logs combines :
 * - sortie console via Logger
 * - buffer interne pour ecriture dans un fichier horodate
 *
 * En cas de succes Maven, la sortie du sous-process n'est pas loggee.
 * En cas d'echec, seules les lignes pertinentes (tests KO) sont conservees,
 * via le filtre isFailureLine().
 */
public class RunReport {

    private final Logger logger;
    private final String logsDir;
    private final String projectName;
    private final StringBuilder buffer = new StringBuilder();

    public RunReport(Logger logger, String logsDir, String projectName) {
        this.logger      = logger;
        this.logsDir     = logsDir;
        this.projectName = projectName;
    }

    // -------------------------------------------------------------------------
    // Logging
    // -------------------------------------------------------------------------

    public void log(String message) {
        logger.log(Level.INFO, message);
        buffer.append(message).append(System.lineSeparator());
    }

    public void warn(String message) {
        logger.log(Level.WARNING, message);
        buffer.append("[WARN] ").append(message).append(System.lineSeparator());
    }

    public void separator() {
        log("==================================================");
    }

    public void section(String title) {
        log("");
        log("-- " + title + " " + "-".repeat(Math.max(0, 44 - title.length())));
    }

    // -------------------------------------------------------------------------
    // Bilan final
    // -------------------------------------------------------------------------

    public void printSummary(int unitCount, boolean surefireOk,
                             int itCount,   boolean failsafeOk,
                             boolean failsafeRan, boolean itOverLimit,
                             String jdbcInfo) {
        log("");
        separator();
        log("  BILAN");
        separator();

        if (unitCount == 0) {
            log("  [TU] Unitaires   : [-] Aucun");
        } else {
            log("  [TU] Unitaires   : " + (surefireOk ? "[OK] OK" : "[FAIL] ECHEC")
                        + " (" + unitCount + " tests)");
        }

        if (itCount == 0) {
            log("  [TI] Integration : [-] Aucun");
        } else if (itOverLimit) {
            log("  [TI] Integration : [SKIP] Seuil depasse (" + itCount + " > max)");
        } else if (!failsafeRan) {
            log("  [TI] Integration : [SKIP] Non lance");
        } else {
            log("  [TI] Integration : " + (failsafeOk ? "[OK] OK" : "[FAIL] ECHEC")
                        + " (" + itCount + " tests)");
        }

        separator();
    }

    // -------------------------------------------------------------------------
    // Filtre des lignes Maven utiles en cas d'echec
    // -------------------------------------------------------------------------

    /**
     * Retourne true pour les lignes qui identifient un test en echec dans la
     * sortie Surefire/Failsafe en mode batch.
     *
     * <p>Lignes conservees (exemples) :
     * <pre>
     *   [ERROR] Tests run: 3, Failures: 1, Errors: 0  ...  &lt;&lt;&lt; FAILURE!
     *   [ERROR]   TestFiltreRequete.testFiltreVide  Time elapsed: 0.12 s  &lt;&lt;&lt; FAILURE!
     *   [ERROR]   TestFiltreRequete.testFiltreVide  Time elapsed: 0.05 s  &lt;&lt;&lt; ERROR!
     *   [ERROR] BUILD FAILURE
     *   [INFO]  Tests run: 5, Failures: 0, Errors: 1, ...  (recap avec au moins un KO)
     * </pre>
     *
     * @param line une ligne de la sortie Maven
     * @return true si la ligne decrit un test en echec ou une erreur de build
     */
    /**
     * Retourne true pour les lignes de detail d'un test individuel en echec
     * (nom du test + duree), qui apparaissent juste avant le marqueur &lt;&lt;&lt;.
     *
     * <p>Exemples :
     * <pre>
     *   TestFiltreRequete.testFiltreVide  Time elapsed: 0.12 s  &lt;&lt;&lt; FAILURE!
     * </pre>
     *
     * @param line une ligne de la sortie Maven
     * @return true si la ligne decrit le detail d'un test en echec
     */
    public static boolean isTestDetailLine(String line) {
        if (line == null) {
            return false;
        }
        // Ligne sans prefixe [ERROR] mais contenant "Time elapsed" + "<<< FAILURE/ERROR"
        return line.contains("Time elapsed") && (line.contains("<<< FAILURE") || line.contains("<<< ERROR"));
    }

    public static boolean isFailureLine(String line) {
        if (line == null) {
            return false;
        }
        if (line.startsWith("[ERROR]")) {
            return line.contains("FAILURE")
                    || line.contains("ERROR")
                    || line.contains("Tests run:")
                    || line.contains("<<<");
        }
        // Ligne de recap Surefire/Failsafe : ne garder que celles avec un echec reel
        if (line.startsWith("[INFO]") && line.contains("Tests run:")) {
            return !line.contains("Failures: 0") || !line.contains("Errors: 0");
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // Ecriture fichier horodate
    // -------------------------------------------------------------------------

    public void writeToFile(File baseDir) {
        try {
            File dir = new File(logsDir);
            if (!dir.isAbsolute()) {
                dir = new File(baseDir, logsDir);
            }
            dir.mkdirs();

            String timestamp = LocalDateTime.now()
                    .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            File logFile = new File(dir, projectName + "-" + timestamp + ".log");

            Writer.writeToFile(List.of(buffer.toString()), logFile.getAbsolutePath());
            logger.log(Level.INFO, "[LOG] Log ecrit dans : " + logFile.getAbsolutePath());
        } catch (Exception e) {
            logger.log(Level.WARNING, "Impossible d'ecrire le fichier log : " + e.getMessage());
        }
    }
}