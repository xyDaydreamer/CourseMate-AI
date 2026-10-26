package edu.t07.coursemate.ui;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.ProjectJdkTable;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.projectRoots.SdkType;
import com.intellij.openapi.roots.OrderRootType;
import com.intellij.openapi.roots.ProjectRootManager;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

/** Repairs missing/broken SDKs of the bundled demo; valid user SDKs and other projects are preserved. */
final class DemoProjectSdk {
    static final String SDK_NAME = "CourseMate Demo JDK 25";
    private static final Logger LOG = Logger.getInstance(DemoProjectSdk.class);
    private DemoProjectSdk() { }

    static Future<?> configure(Project project) {
        String demo = System.getProperty("coursemate.demoProjectPath", "");
        String home = System.getProperty("coursemate.demoJdkHome", "");
        if (demo.isBlank() || home.isBlank() || project.getBasePath() == null
                || !Path.of(demo).toAbsolutePath().normalize().equals(Path.of(project.getBasePath()).toAbsolutePath().normalize())) return CompletableFuture.completedFuture(null);
        var app = ApplicationManager.getApplication();
        return app.executeOnPooledThread(() -> {
            if (project.isDisposed()) return;
            Sdk initial = ReadAction.compute(() -> ProjectRootManager.getInstance(project).getProjectSdk());
            if (isUsable(initial)) return;
            SdkType type = SdkType.findByName("JavaSDK");
            if (type == null || !type.isValidSdkHome(home)) {
                LOG.warn("CourseMate demo JDK is unavailable; select an existing JDK with Setup SDK.");
                return;
            }
            ProjectJdkTable table = ProjectJdkTable.getInstance();
            Sdk existing = ReadAction.compute(() -> table.findJdk(SDK_NAME, "JavaSDK"));
            // Reuse a registered local JDK rather than rename it or create a duplicate.
            if (!isUsable(existing) || !sameHome(existing.getHomePath(), home)) {
                existing = Arrays.stream(ReadAction.compute(table::getAllJdks))
                        .filter(sdk -> sameHome(sdk.getHomePath(), home) && isUsable(sdk)).findFirst().orElse(existing);
            }
            Sdk candidate;
            if (isUsable(existing) && sameHome(existing.getHomePath(), home)) candidate = existing;
            else {
                String version = type.getVersionString(home);
                // The same name in every sandbox prevents a shared demo from referring to a foreign hash.
                candidate = table.createSdk(SDK_NAME, type);
                app.invokeAndWait(() -> WriteAction.run(() -> {
                    var editor = candidate.getSdkModificator();
                    editor.setHomePath(home);
                    editor.setVersionString(version);
                    editor.commitChanges();
                }), ModalityState.nonModal());
                // Java's SDK implementation scans the JDK on this worker, committing roots on the EDT.
                type.setupSdkPaths(candidate);
            }
            if (!isUsable(candidate)) {
                LOG.warn("CourseMate demo JDK has no usable standard classes; project SDK was left unchanged.");
                return;
            }
            Sdk expected = ReadAction.compute(() -> ProjectRootManager.getInstance(project).getProjectSdk());
            if (isUsable(expected)) return;
            app.invokeAndWait(() -> {
                if (project.isDisposed()) return;
                WriteAction.run(() -> {
                    var manager = ProjectRootManager.getInstance(project);
                    Sdk current = manager.getProjectSdk();
                    if (!sameSelection(current, expected)) { configure(project); return; }
                    Sdk registered = table.findJdk(candidate.getName(), "JavaSDK");
                    if (registered == null) { table.addJdk(candidate); registered = candidate; }
                    else if (registered != candidate) table.updateJdk(registered, candidate);
                    manager.setProjectSdk(registered);
                    LOG.info("CourseMate demo project JDK configured; verified String/Map/HashMap; class roots="
                            + registered.getRootProvider().getFiles(OrderRootType.CLASSES).length);
                });
            }, ModalityState.nonModal());
            // SDK registration must survive the application's next settings reload/restart.
            // Saving is blocking, so keep it on this worker and outside the write action.
            table.saveOnDisk();
        });
    }

    // Called on a worker: validate the disk home and actual class files, not a name or stale root URLs.
    static boolean isUsable(Sdk sdk) {
        if (sdk == null || !(sdk.getSdkType() instanceof SdkType type) || !"JavaSDK".equals(type.getName())) return false;
        String home = ReadAction.compute(sdk::getHomePath);
        if (home == null || !type.isValidSdkHome(home)) return false;
        return ReadAction.compute(() -> Arrays.stream(sdk.getRootProvider().getFiles(OrderRootType.CLASSES))
                .anyMatch(root -> root.isValid() && root.findFileByRelativePath("java/lang/String.class") != null
                        && root.findFileByRelativePath("java/util/Map.class") != null
                        && root.findFileByRelativePath("java/util/HashMap.class") != null));
    }

    private static boolean sameHome(String left, String right) {
        return left != null && right != null && Path.of(left).toAbsolutePath().normalize().equals(Path.of(right).toAbsolutePath().normalize());
    }
    private static boolean sameSelection(Sdk left, Sdk right) {
        return left == right || left != null && right != null && Objects.equals(left.getName(), right.getName())
                && Objects.equals(left.getHomePath(), right.getHomePath());
    }
}
