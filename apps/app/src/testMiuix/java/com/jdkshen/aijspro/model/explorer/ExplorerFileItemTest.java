package com.jdkshen.aijspro.model.explorer;
import java.io.File;
import org.junit.Test;
import static org.junit.Assert.*;
public class ExplorerFileItemTest {
 @Test public void markdownFilesOpenInInternalEditorCaseInsensitively() {
   assertTrue(ExplorerFileItem.isEditableExtension("md"));
   assertTrue(ExplorerFileItem.isEditableExtension("MD"));
   assertTrue(ExplorerFileItem.isEditableExtension("markdown"));
 }

}
