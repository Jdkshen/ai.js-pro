package com.stardust.autojs.apkbuilder;

import com.cmy.parser.ArscReader;
import com.cmy.parser.ResTablePrinter;
import com.cmy.parser.bean.ResTable;

import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;

import pxb.android.axml.AxmlReader;
import pxb.android.axml.AxmlWriter;
import pxb.android.axml.DumpAdapter;
import pxb.android.axml.Util;

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * @see <a href="http://d.android.com/tools/testing">Testing documentation</a>
 */
public class ExampleUnitTest {
    @Test
    public void addition_isCorrect() throws Exception {
        File manifest = new File("AndroidManifest.xml");

    }

    @Test
    public void test() throws Exception {
        File manifest = new File("AndroidManifest1.xml");
        Assume.assumeTrue("AndroidManifest1.xml sample is not available", manifest.isFile());
        AxmlReader reader = new AxmlReader(Util.readFile(manifest));
        reader.accept(new DumpAdapter());
    }

    @Test
    public void test1() throws IOException {
        File resources = new File("resources.arsc");
        Assume.assumeTrue("resources.arsc sample is not available", resources.isFile());
        ArscReader reader = new ArscReader(resources);
        PrintStream printStream = new PrintStream(new File("out.txt"), "utf-8");
        ResTable resTable = reader.read();
        new ResTablePrinter(resTable, printStream).printResTablePackage(true);
    }
}