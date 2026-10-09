package com.jdkshen.aijspro.model.explorer;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ExplorerFileItemTest {
    @Test
    public void textFilesUseTheBuiltInEditor() {
        assertTrue(ExplorerFileItem.isEditableFileName("main.js"));
        assertTrue(ExplorerFileItem.isEditableFileName("task.auto"));
        assertTrue(ExplorerFileItem.isEditableFileName("config.YAML"));
        assertTrue(ExplorerFileItem.isEditableFileName("README.md"));
        assertTrue(ExplorerFileItem.isEditableFileName("page.tsx"));
        assertTrue(ExplorerFileItem.isEditableFileName(".gitignore"));
        assertTrue(ExplorerFileItem.isEditableFileName("Dockerfile"));
    }

    @Test
    public void binaryFilesAreNotSentToTheTextEditor() {
        assertFalse(ExplorerFileItem.isEditableFileName("model.onnx"));
        assertFalse(ExplorerFileItem.isEditableFileName("engine.so"));
        assertFalse(ExplorerFileItem.isEditableFileName("package.apk"));
        assertFalse(ExplorerFileItem.isEditableFileName("images.zip"));
        assertFalse(ExplorerFileItem.isEditableFileName("photo.png"));
        assertFalse(ExplorerFileItem.isEditableFileName("no-extension"));
    }
}
