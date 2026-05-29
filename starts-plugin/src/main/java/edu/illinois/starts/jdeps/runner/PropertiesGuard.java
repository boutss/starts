/*
 * Copyright (c) 2015 - Present. The STARTS Team. All Rights Reserved.
 */

package edu.illinois.starts.jdeps.runner;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Desactive temporairement des proprietes dans un fichier .properties
 * avant l'execution des tests, puis restaure le fichier original.
 *
 * <p>Utilisation typique :
 * <pre>
 *   try (PropertiesGuard guard = new PropertiesGuard(file, report)) {
 *       guard.disable("ACTIVER_HIBERNATE");
 *       // lancer les tests ...
 *   }
 *   // le fichier est restaure a la sortie du bloc, meme en cas d'exception
 * </pre>
 */
public class PropertiesGuard implements AutoCloseable {

    private final File       file;
    private final RunReport  report;
    private       List<String> originalLines;
    private       boolean    patched = false;

    /**
     * @param file   le fichier .properties a patcher
     * @param report le rapport courant
     */
    public PropertiesGuard(File file, RunReport report) {
        this.file   = file;
        this.report = report;
    }

    /**
     * Desactive une propriete en la passant a {@code false} si elle vaut {@code true}.
     * Ajoute un commentaire explicatif sur la ligne precedente.
     * Sans effet si la propriete est absente ou deja a false.
     *
     * @param key la cle a desactiver (ex: {@code ACTIVER_HIBERNATE})
     */
    public void disable(String key) {
        if (!file.exists()) {
            report.log("  [PropertiesGuard] Fichier introuvable : " + file.getAbsolutePath());
            return;
        }

        try {
            originalLines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
            List<String> patched = new ArrayList<>(originalLines.size() + 1);

            boolean modified = false;
            for (String line : originalLines) {
                String trimmed = line.trim();
                // Matcher key=true ou key = true (insensible a la casse de la valeur)
                if (trimmed.startsWith(key)
                        && trimmed.replaceAll("\\s", "").equalsIgnoreCase(key + "=true")) {
                    patched.add("# Modifie par STARTS pour lancer les tests (valeur originale : " + trimmed + ")");
                    patched.add(key + "=false");
                    modified = true;
                } else {
                    patched.add(line);
                }
            }

            if (modified) {
                Files.write(file.toPath(), patched, StandardCharsets.UTF_8);
                this.patched = true;
                report.log("  [PropertiesGuard] " + key + " desactive dans "
                        + file.getName());
            }

        } catch (IOException e) {
            report.log("  [PropertiesGuard] Impossible de patcher " + file.getName()
                    + " : " + e.getMessage());
        }
    }

    /**
     * Restaure le fichier dans son etat original.
     * Appele automatiquement en fin de bloc try-with-resources.
     */
    @Override
    public void close() {
        if (patched && originalLines != null) {
            try {
                Files.write(file.toPath(), originalLines, StandardCharsets.UTF_8);
                report.log("  [PropertiesGuard] Fichier " + file.getName() + " restaure");
            } catch (IOException e) {
                report.log("  [PropertiesGuard] ERREUR restauration " + file.getName()
                        + " : " + e.getMessage());
                report.log("  Restaurez manuellement ACTIVER_HIBERNATE=true dans "
                        + file.getAbsolutePath());
            }
        }
    }
}
