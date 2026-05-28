/*
 * Copyright (c) 2015 - Present. The STARTS Team. All Rights Reserved.
 */

package edu.illinois.starts.jdeps.runner;

import java.util.List;

/**
 * Resultat de la separation des tests affectes en deux familles :
 * - unitTests  : classes sans "IT" dans le nom simple -> Surefire
 * - itTests    : classes avec "IT" dans le nom simple -> Failsafe
 */
public class TestSplitResult {

    private final List<String> unitTests;
    private final List<String> itTests;

    public TestSplitResult(List<String> unitTests, List<String> itTests) {
        this.unitTests = unitTests;
        this.itTests   = itTests;
    }

    public List<String> getUnitTests() {
        return unitTests;
    }

    public List<String> getItTests() {
        return itTests;
    }

    public int getUnitCount() {
        return unitTests.size();
    }

    public int getItCount() {
        return itTests.size();
    }

    public int getTotalCount() {
        return unitTests.size() + itTests.size();
    }

    public boolean hasUnitTests() {
        return !unitTests.isEmpty();
    }

    public boolean hasItTests() {
        return !itTests.isEmpty();
    }
}
