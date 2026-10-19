package com.stardust.pio;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;

public class SafeDirectoryListerTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void preservesUnicodeAndHiddenNames() throws Exception {
        File directory = temporary.newFolder();
        new File(directory, "中文😀.txt").createNewFile();
        new File(directory, ".hidden").createNewFile();
        new File(directory, "child").mkdir();
        String[] actual = SafeDirectoryLister.list(directory);
        Arrays.sort(actual);
        assertArrayEquals(new String[]{".hidden", "child", "中文😀.txt"}, actual);
    }

    @Test public void nonDirectoriesAndMissingPathsReturnEmpty() throws Exception {
        assertArrayEquals(new String[0], SafeDirectoryLister.list(temporary.newFile()));
        assertArrayEquals(new String[0], SafeDirectoryLister.list(new File(temporary.getRoot(), "missing")));
        assertArrayEquals(new String[0], SafeDirectoryLister.list(temporary.newFolder()));
    }
}
