/*
 * Copyright (c) 2015 - Present. The STARTS Team. All Rights Reserved.
 */

package edu.illinois.starts.jdeps.runner;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Suivi des tests en echec entre runs.
 *
 * <p>Apres chaque run, scanne les rapports {@code surefire-reports/TEST-*.xml} et
 * {@code failsafe-reports/TEST-*.xml} pour identifier les classes en echec, et les
 * sauvegarde dans {@code .starts/failed-tests.txt}.
 *
 * <p>Au retry, lit ce fichier pour ne relancer que les tests precedemment en echec.
 */
public class FailedTestsTracker {

    /** Nom du fichier de persistance, sous .starts/ */
    public static final String FAILED_TESTS_FILE = "failed-tests.txt";

    private final File startsDir;
    private final File reportFile;

    public FailedTestsTracker(File projectBasedir) {
        this.startsDir  = new File(projectBasedir, ".starts");
        this.reportFile = new File(startsDir, FAILED_TESTS_FILE);
    }

    /**
     * Scanne les rapports XML de Surefire et Failsafe, extrait les classes en
     * echec et les enregistre dans {@code .starts/failed-tests.txt}.
     * Si la liste est vide, le fichier existant est supprime.
     *
     * @param targetDir le repertoire target/ du module
     * @return la liste des FQN en echec (jamais null)
     */
    public List<String> recordFailuresFromReports(File targetDir) throws IOException {
        Set<String> failed = new LinkedHashSet<>();
        scanReports(new File(targetDir, "surefire-reports"), failed);
        scanReports(new File(targetDir, "failsafe-reports"), failed);

        List<String> sorted = new ArrayList<>(failed);
        Collections.sort(sorted);

        if (sorted.isEmpty()) {
            // Tout passe : supprimer le fichier s'il existe
            if (reportFile.exists() && !reportFile.delete()) {
                throw new IOException("Impossible de supprimer " + reportFile.getAbsolutePath());
            }
        } else {
            startsDir.mkdirs();
            Files.write(reportFile.toPath(), sorted, StandardCharsets.UTF_8);
        }
        return sorted;
    }

    /**
     * Lit la liste des tests en echec depuis {@code .starts/failed-tests.txt}.
     * Retourne une liste vide si le fichier n'existe pas.
     */
    public List<String> readFailedTests() throws IOException {
        if (!reportFile.exists()) {
            return Collections.emptyList();
        }
        return Files.readAllLines(reportFile.toPath(), StandardCharsets.UTF_8);
    }

    /** @return le fichier de persistance (peut ne pas exister). */
    public File getReportFile() {
        return reportFile;
    }

    // -------------------------------------------------------------------------
    // Parsing XML
    // -------------------------------------------------------------------------

    private static void scanReports(File reportsDir, Set<String> failed) {
        if (!reportsDir.exists()) {
            return;
        }
        File[] xmlFiles = reportsDir.listFiles(
                f -> f.getName().startsWith("TEST-") && f.getName().endsWith(".xml"));
        if (xmlFiles == null) {
            return;
        }
        for (File xml : xmlFiles) {
            String fqn = extractFailedClassFqn(xml);
            if (fqn != null) {
                failed.add(fqn);
            }
        }
    }

    /**
     * Parse un fichier XML Surefire/Failsafe. Retourne le FQN de la classe si
     * elle contient au moins un echec ou une erreur, sinon null.
     */
    private static String extractFailedClassFqn(File xmlFile) {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            DocumentBuilder db = dbf.newDocumentBuilder();
            Document doc = db.parse(xmlFile);

            Element testsuite = doc.getDocumentElement();
            if (testsuite == null || !"testsuite".equals(testsuite.getNodeName())) {
                return null;
            }

            int failures = parseInt(testsuite.getAttribute("failures"));
            int errors   = parseInt(testsuite.getAttribute("errors"));
            if (failures + errors > 0) {
                String name = testsuite.getAttribute("name");
                return name != null && !name.isEmpty() ? name : null;
            }
        } catch (Exception e) {
            // XML invalide, fichier verrouille... on ignore en silence
        }
        return null;
    }

    private static int parseInt(String s) {
        if (s == null || s.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
