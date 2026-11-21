package com.usebruno.intellij.flows;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.openapi.vfs.VfsUtil;
import java.nio.file.*;

public class FlowFileOperationsTest extends BrunoPlatformTestCase {
    private Path temporary;
    @Override protected void tearDown() throws Exception {
        try { if (temporary != null) com.intellij.openapi.util.io.FileUtil.delete(temporary.toFile()); }
        finally { super.tearDown(); }
    }
    public void testCreateDiscoverDuplicateAndDelete() throws Exception {
        temporary = Files.createTempDirectory("bruno-flow-vfs-");
        var root = com.intellij.openapi.application.WriteAction.compute(() -> com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByNioFile(temporary));
        assertNotNull(root);
        var file = FlowFileOperations.create(getProject(), root, "smoke");
        assertTrue(file.isValid());
        assertNotNull(root.findChild("bruno.json"));
        assertTrue(VfsUtil.loadText(file).contains("start-end"));
        assertEquals(1, FlowLocator.findFlows(Path.of(root.getPath())).size());
        try { FlowFileOperations.create(getProject(), root, "smoke"); fail("Must not overwrite an existing flow"); }
        catch (java.io.IOException expected) { assertTrue(expected.getMessage().contains("already exists")); }
        var output = Path.of(System.getProperty("bruno.screenshotDir")); Files.createDirectories(output);
        Files.writeString(output.resolve("starter.flow.bru"), VfsUtil.loadText(file));
        FlowFileOperations.delete(getProject(), file);
        assertFalse(file.isValid());
        assertEquals(0, FlowLocator.findFlows(Path.of(root.getPath())).size());
    }
    public void testRejectsInvalidNamesAndNonFlowDeletion() throws Exception {
        var root = myFixture.getTempDirFixture().findOrCreateDir("invalid");
        try { FlowFileOperations.create(getProject(), root, "../escape"); fail("Must reject path traversal"); }
        catch (java.io.IOException expected) { }
        var file = myFixture.getTempDirFixture().createFile("invalid/request.bru", "request");
        try { FlowFileOperations.delete(getProject(), file); fail("Must not delete a request"); }
        catch (java.io.IOException expected) { }
        assertTrue(file.isValid());
    }
}
