package com.jdkshen.aijspro.ui.editor;

import android.content.Context;
import android.view.View;

import androidx.test.platform.app.InstrumentationRegistry;

import com.jdkshen.aijspro.ui.edit.editor.CodeEditor;
import com.jdkshen.aijspro.ui.edit.theme.Theme;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Height-only viewport changes used by the editor's log console and keyboard. */
public class CodeEditorViewportTest {
    @Test
    public void shortDocumentFillsViewportAfterRepeatedLogToggles() {
        verifyViewport("// viewport regression\nconst value = 1;\n");
    }

    @Test
    public void longDocumentKeepsItsContentWhenViewportShrinksAndGrows() {
        StringBuilder source = new StringBuilder();
        for (int i = 0; i < 100; i++) source.append("const value").append(i).append(" = 1;\n");
        verifyViewport(source.toString());
    }

    private void verifyViewport(String source) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            CodeEditor editor = new CodeEditor(context);
            try {
                editor.setTheme(Theme.fromAssetsJson(context, "editor/theme/light_plus.json"));
                editor.setInitialText(source);
                editor.getCodeEditText().setSelection(3);
                // Normal editor -> log strip -> expanded log -> normal, including rotation.
                int[][] sizes = {{720, 1200}, {720, 135}, {720, 0}, {720, 1200},
                        {1200, 360}, {720, 135}, {720, 1200}};
                for (int[] size : sizes) {
                    editor.measure(View.MeasureSpec.makeMeasureSpec(size[0], View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(size[1], View.MeasureSpec.EXACTLY));
                    editor.layout(0, 0, size[0], size[1]);
                    assertEquals(size[1], editor.getCodeEditText().getMinHeight());
                    assertTrue(editor.getCodeEditText().getMeasuredHeight() >= size[1]);
                    assertEquals(source, editor.getText().toString());
                    assertEquals(3, editor.getCodeEditText().getSelectionStart());
                }
            } finally {
                editor.destroy();
            }
        });
    }
}
