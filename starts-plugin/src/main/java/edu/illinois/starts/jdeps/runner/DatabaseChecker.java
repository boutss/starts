/*
 * Copyright (c) 2015 - Present. The STARTS Team. All Rights Reserved.
 */

package edu.illinois.starts.jdeps.runner;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;

/**
 * Lit le fichier properties du projet pour detecter la configuration BDD locale.
 *
 * Role INFORMATIF uniquement : indique dans le log quelle base est utilisee,
 * ou avertit si aucune n'est configuree. Ne bloque jamais l'execution des TI.
 *
 * Ignore les lignes commentees (commencant par #).
 *
 * Exemple de contenu attendu dans framework2.properties :
 *   JDBC_CONNECT_STRING=jdbc:postgresql:postgres
 */
public class DatabaseChecker {

    private static final String KEY = "JDBC_CONNECT_STRING=";

    private final String propertiesFilePath;
    private final File   baseDir;
    private final RunReport report;

    public DatabaseChecker(String propertiesFilePath, File baseDir, RunReport report) {
        this.propertiesFilePath = propertiesFilePath;
        this.baseDir            = baseDir;
        this.report             = report;
    }

    // -------------------------------------------------------------------------
    // Log informatif - jamais bloquant
    // -------------------------------------------------------------------------

    /**
     * Lit JDBC_CONNECT_STRING et loggue l'information.
     * Retourne la valeur trouvee (ou null si absente/commentee),
     * mais cette valeur n'est utilisee qu'a titre informatif dans le bilan.
     *
     * @return la valeur de JDBC_CONNECT_STRING, ou null si non trouvee
     */
    public String logDatabaseInfo() {
        String jdbc = readJdbcConnectString();

        if (jdbc != null) {
            report.log("  [BDD] Base configuree : " + jdbc);
        } else {
            report.log("  [WARN] JDBC_CONNECT_STRING absent ou commente dans :");
            report.log("         " + resolveFile().getAbsolutePath());
            report.log("         Les TI vont tourner mais risquent d'echouer si une BDD est requise.");
        }

        return jdbc;
    }

    // -------------------------------------------------------------------------
    // Lecture du fichier properties
    // -------------------------------------------------------------------------

    private String readJdbcConnectString() {
        File file = resolveFile();

        if (!file.exists()) {
            report.warn("Fichier properties introuvable : " + file.getAbsolutePath());
            return null;
        }

        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                // Ignorer les lignes vides et commentees
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                if (trimmed.startsWith(KEY)) {
                    String value = trimmed.substring(KEY.length()).trim();
                    return value.isEmpty() ? null : value;
                }
            }
        } catch (IOException e) {
            report.warn("Erreur lecture properties : " + e.getMessage());
        }

        return null;
    }

    private File resolveFile() {
        File file = new File(propertiesFilePath);
        return file.isAbsolute() ? file : new File(baseDir, propertiesFilePath);
    }
}
