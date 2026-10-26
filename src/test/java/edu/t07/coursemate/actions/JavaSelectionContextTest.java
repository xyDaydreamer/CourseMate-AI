package edu.t07.coursemate.actions;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;

public final class JavaSelectionContextTest extends BasePlatformTestCase {
    public void testPsiFeaturesUseOnlySelectionAndFallbackOnIncompleteStatements() {
        var features=JavaSelectionContext.features(getProject(),"Map<String,Integer> counts=new HashMap<>(); counts.merge(\"java\",1,Integer::sum);");
        assertTrue(features.contains("Map"));assertTrue(features.contains("merge"));assertTrue(features.contains("generics"));
        var partial=JavaSelectionContext.features(getProject(),"class Child extends Parent {");assertTrue(partial.contains("inheritance"));
    }
    public void testUnknownMethodBodiesCommentsAndLiteralsAreNotInvented() {
        var features=JavaSelectionContext.features(getProject(),"// Map\nSystem.out.println(countWords(\"HashMap merge inheritance\"));");
        assertTrue(features.isEmpty());
    }
}
