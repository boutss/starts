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
 * Usage :
 *   RunReport report = new RunReport(logger, logsDir, projectName);
 *   report.log("message");
 *   report.writeToFile();
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

        // TU
        if (unitCount == 0) {
            log("  [TU] Unitaires   : [-] Aucun");
        } else {
            log("  [TU] Unitaires   : " + (surefireOk ? "[OK] OK" : "[FAIL] ECHEC")
                    + " (" + unitCount + " tests)");
        }

        // TI
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
