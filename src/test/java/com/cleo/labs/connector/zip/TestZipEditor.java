package com.cleo.labs.connector.zip;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;

import org.junit.Test;

import com.cleo.labs.connector.zip.ZipEditor.ZipProcessResult;
import com.cleo.labs.connector.zip.ZipEditor.ZipWriter;

public class TestZipEditor {
    static private final String HELLO = "hello, world!\n";
    static private final long HELLOL = HELLO.length();

    private final Path makeNewZip() throws IOException {
        Path zipfn = Files.createTempFile("ziptest", ".zip");
        ZipEditor zip = new ZipEditor(zipfn.toFile());
        ZipProcessResult result = zip.add("test1.txt", ZipWriter.of(HELLO))
                                     .mkdir("foo")
                                     .mkdir("bar/")
                                     .add("foo/test2.txt", ZipWriter.of(HELLO))
                                     .process();
        assertEquals(0, result.keeps());
        assertEquals(4, result.adds());
        assertEquals(0, result.deletes());
        assertArrayEquals(new String[] {"bar/","foo/","foo/test2.txt","test1.txt"}, zip.entries().stream().map((z)->z.getName()).toArray(String[]::new));
        assertArrayEquals(new Long[] {0L,0L,HELLOL,HELLOL}, zip.entries().stream().map((z)->z.getSize()).toArray(Long[]::new));
        return zipfn;
    }

    @Test
    public final void testNewZip() throws IOException {
        Path zipfn = Files.createTempFile("ziptest", ".zip");
        ZipEditor zip = new ZipEditor(zipfn.toFile());
        ZipProcessResult result = zip.add("test1.txt", ZipWriter.of(HELLO))
                                     .process();
        assertEquals(0, result.keeps());
        assertEquals(1, result.adds());
        assertEquals(0, result.deletes());
        assertArrayEquals(new String[] {"test1.txt"}, zip.entries().stream().map((z)->z.getName()).toArray(String[]::new));
        Files.delete(zipfn);
    }
    @Test
    public final void testMkdir () throws IOException {
        Path zipfn = makeNewZip();
        Files.delete(zipfn);
    }
    @Test
    public final void testEdit () throws IOException {
        Path zipfn = makeNewZip();
        ZipEditor zip = new ZipEditor(zipfn.toFile());
        ZipProcessResult result = zip.add("test2.txt", ZipWriter.of(HELLO+HELLO))
                                     .add("test1.txt", ZipWriter.of(HELLO+HELLO))
                                     .rmdir("foo")
                                     .process();
        assertEquals(1, result.keeps());
        assertEquals(2, result.adds());
        assertEquals(3, result.deletes());
        assertArrayEquals(new String[] {"bar/","test1.txt","test2.txt"}, zip.entries().stream().map((z)->z.getName()).toArray(String[]::new));
        assertArrayEquals(new Long[] {0L,2*HELLOL,2*HELLOL}, zip.entries().stream().map((z)->z.getSize()).toArray(Long[]::new));
        Files.delete(zipfn);
    }
    @Test
    public final void testNormalizePath() {
        assertEquals("", ZipEditor.normalizeDirectoryName(""));
        assertEquals("abc/", ZipEditor.normalizeDirectoryName("abc"));
        assertEquals("/", ZipEditor.normalizeDirectoryName("/"));
        assertEquals("abc/", ZipEditor.normalizeDirectoryName("abc/"));
    }
    @Test
    public final void testMkdirExisting () throws IOException {
        Path zipfn = makeNewZip();
        ZipEditor zip = new ZipEditor(zipfn.toFile());
        ZipProcessResult result = zip.mkdir("bar")
                                     .process();
        assertEquals(3, result.keeps());
        assertEquals(1, result.adds());
        assertEquals(1, result.deletes());
        Files.delete(zipfn);
    }
    @Test
    public final void testRename () throws IOException {
        Path zipfn = makeNewZip();
        ZipEditor zip = new ZipEditor(zipfn.toFile());
        ZipProcessResult result = zip.rename("foo/test2.txt", "bar/new.txt")
                                     .process();
        assertEquals(3, result.keeps());
        assertEquals(1, result.adds());
        assertEquals(1, result.deletes());
        assertArrayEquals(new String[] {"bar/","bar/new.txt","foo/","test1.txt"}, zip.entries().stream().map(ZipEntry::getName).toArray(String[]::new));
        assertArrayEquals(new Long[] {0L,HELLOL,0L,HELLOL}, zip.entries().stream().map(ZipEntry::getSize).toArray(Long[]::new));
        Files.delete(zipfn);
    }
    @Test
    public final void testPrefix() throws IOException {
        Path zipfn = Files.createTempFile("ziptest", ".zip");
        ZipEditor zip = new ZipEditor(zipfn.toFile());
        zip.add("test1.txt", ZipWriter.of(HELLO))
           .mkdir("foo")
           .mkdir("bar/")
           .add("foo/test2.txt", ZipWriter.of(HELLO))
           .add("bat/one/test1.txt", ZipWriter.of(HELLO))
           .add("bat/two/test1.txt", ZipWriter.of(HELLO))
           .process();
        String[] entries = zip.entries("").stream().map(ZipEntry::getName).toArray(String[]::new);
        assertArrayEquals(new String[] {"bar/","bat/","foo/","test1.txt"}, entries);
        entries = zip.entries("bat").stream().map(ZipEntry::getName).toArray(String[]::new);
        assertArrayEquals(new String[] {"bat/one/","bat/two/"}, entries);
        Files.delete(zipfn);
    }
}
