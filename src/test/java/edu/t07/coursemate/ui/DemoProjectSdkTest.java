package edu.t07.coursemate.ui;

import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.TransactionGuard;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.JarFileSystem;
import com.intellij.openapi.projectRoots.ProjectJdkTable;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.projectRoots.SdkType;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.ModuleRootModificationUtil;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.concurrent.TimeUnit;

/** Real local SDK registration, roots and project binding; no UI clicks, downloads or model calls. */
public final class DemoProjectSdkTest extends BasePlatformTestCase {
    @Override
    protected boolean runInDispatchThread() { return false; }

    public void testRegistersLocalJdkAndBindsOnlyTheExplicitDemo() throws Exception {
        withDemoEnvironment(() -> {
            var manager = ProjectRootManager.getInstance(getProject());
            writeOnEdt(() -> manager.setProjectSdk(null));
            System.setProperty("coursemate.demoProjectPath", Path.of(getProject().getBasePath()).resolve("other-project").toString());
            DemoProjectSdk.configure(getProject()).get(20, TimeUnit.SECONDS);
            assertNull("A different project must not be configured", ReadAction.compute(manager::getProjectSdk));
            System.setProperty("coursemate.demoProjectPath", getProject().getBasePath());
            DemoProjectSdk.configure(getProject()).get(20, TimeUnit.SECONDS);
            Sdk sdk = ReadAction.compute(manager::getProjectSdk);
            assertTrue("Standard class files must really exist", DemoProjectSdk.isUsable(sdk));
            assertEquals(DemoProjectSdk.SDK_NAME, sdk.getName());
            assertEquals(Path.of(System.getProperty("java.home")).toAbsolutePath().normalize(), Path.of(sdk.getHomePath()).toAbsolutePath().normalize());
            System.setProperty("coursemate.demoJdkHome", "missing-jdk-that-must-not-replace-an-existing-sdk");
            DemoProjectSdk.configure(getProject()).get(20, TimeUnit.SECONDS);
            assertSame("Valid SDK must be preserved", sdk, ReadAction.compute(manager::getProjectSdk));
            assertStandardImportsResolve();
        });
    }

    public void testRepairsMissingHomeAndValidHomeWithEmptyRoots() throws Exception {
        withDemoEnvironment(() -> {
            var manager = ProjectRootManager.getInstance(getProject());
            var table = ProjectJdkTable.getInstance();
            var type = SdkType.findByName("JavaSDK");
            assertNotNull(type);
            for (boolean missingHome : new boolean[]{true, false}) {
                Sdk broken = table.createSdk(missingHome ? "CourseMate JDK 25-missing" : DemoProjectSdk.SDK_NAME, type);
                writeOnEdt(() -> {
                    var editor = broken.getSdkModificator();
                    editor.setHomePath(missingHome ? Path.of(System.getProperty("java.io.tmpdir"), "missing-openjdk-27-course-test").toString() : System.getProperty("java.home"));
                    editor.setVersionString("java version \"27.0.1\"");
                    editor.commitChanges();
                    Sdk prior = table.findJdk(broken.getName(), "JavaSDK");
                    if (prior == null) { table.addJdk(broken); manager.setProjectSdk(broken); }
                    else { table.updateJdk(prior, broken); manager.setProjectSdk(prior); }
                });
                assertFalse("A name alone is not a usable SDK", DemoProjectSdk.isUsable(ReadAction.compute(manager::getProjectSdk)));
                DemoProjectSdk.configure(getProject()).get(20, TimeUnit.SECONDS);
                Sdk repaired = ReadAction.compute(manager::getProjectSdk);
                assertNotNull(repaired);
                assertEquals(DemoProjectSdk.SDK_NAME, repaired.getName());
                assertTrue(DemoProjectSdk.isUsable(repaired));
            }
            assertStandardImportsResolve();
        });
    }

    public void testReusesRegisteredLocalJdkWithoutRenamingIt() throws Exception {
        withDemoEnvironment(() -> {
            var manager = ProjectRootManager.getInstance(getProject());
            writeOnEdt(() -> manager.setProjectSdk(null));
            DemoProjectSdk.configure(getProject()).get(20, TimeUnit.SECONDS);
            Sdk existing = ReadAction.compute(manager::getProjectSdk);
            String name = "CourseMate JDK 25-existing-valid";
            writeOnEdt(() -> {
                var editor = existing.getSdkModificator();
                editor.setName(name);
                editor.commitChanges();
                manager.setProjectSdk(null);
            });
            int count = ReadAction.compute(() -> ProjectJdkTable.getInstance().getAllJdks().length);
            DemoProjectSdk.configure(getProject()).get(20, TimeUnit.SECONDS);
            assertSame(existing, ReadAction.compute(manager::getProjectSdk));
            assertEquals(name, existing.getName());
            assertEquals(count, (int) ReadAction.compute(() -> ProjectJdkTable.getInstance().getAllJdks().length));
            assertStandardImportsResolve();
        });
    }

    private void assertStandardImportsResolve() {
        ApplicationManager.getApplication().invokeAndWait(() -> {
            ModuleRootModificationUtil.setSdkInherited(getModule());
            myFixture.configureByText("DemoCheck.java", "import java.util.Map;\nimport java.util.HashMap;\nclass DemoCheck { String value = \"ok\"; Map<String, Integer> counts = new HashMap<>(); }");
            myFixture.checkHighlighting(false, false, false);
        }, ModalityState.nonModal());
    }

    private void withDemoEnvironment(CheckedAction action) throws Exception {
        var manager = ProjectRootManager.getInstance(getProject());
        var table = ProjectJdkTable.getInstance();
        Sdk original = ReadAction.compute(manager::getProjectSdk);
        Set<Sdk> registered = ReadAction.compute(() -> Arrays.stream(table.getAllJdks()).collect(Collectors.toSet()));
        boolean inherited = ReadAction.compute(() -> ModuleRootManager.getInstance(getModule()).isSdkInherited());
        Sdk moduleSdk = ReadAction.compute(() -> ModuleRootManager.getInstance(getModule()).getSdk());
        String oldDemo = System.getProperty("coursemate.demoProjectPath");
        String oldHome = System.getProperty("coursemate.demoJdkHome");
        try {
            // The headless fixture has not refreshed bundled annotation jars as a real IDE startup does.
            var annotations = Path.of(PathManager.getHomePath(), "plugins/java/lib/resources/jdkAnnotations.jar");
            var annotationFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(annotations);
            assertNotNull(annotationFile);
            assertNotNull(JarFileSystem.getInstance().getJarRootForLocalFile(annotationFile));
            assertNotNull(getProject().getBasePath());
            System.setProperty("coursemate.demoJdkHome", System.getProperty("java.home"));
            System.setProperty("coursemate.demoProjectPath", getProject().getBasePath());
            action.run();
        } finally {
            if (oldDemo == null) System.clearProperty("coursemate.demoProjectPath"); else System.setProperty("coursemate.demoProjectPath", oldDemo);
            if (oldHome == null) System.clearProperty("coursemate.demoJdkHome"); else System.setProperty("coursemate.demoJdkHome", oldHome);
            writeOnEdt(() -> {
                manager.setProjectSdk(original);
                if (inherited) ModuleRootModificationUtil.setSdkInherited(getModule()); else ModuleRootModificationUtil.setModuleSdk(getModule(), moduleSdk);
                for (Sdk sdk : table.getAllJdks()) if (!registered.contains(sdk)) table.removeJdk(sdk);
            });
        }
    }

    @FunctionalInterface private interface CheckedAction { void run() throws Exception; }

    private static void writeOnEdt(Runnable action) {
        ApplicationManager.getApplication().invokeAndWait(() -> {
            TransactionGuard.getInstance().assertWriteSafeContext(ModalityState.current());
            WriteAction.run(action::run);
        }, ModalityState.nonModal());
    }
}
