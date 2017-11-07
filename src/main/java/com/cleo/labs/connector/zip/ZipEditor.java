package com.cleo.labs.connector.zip;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import com.google.common.base.Strings;
import com.google.common.io.ByteStreams;

public class ZipEditor {

    /**
     * Assume Zip files use {@code /} as a file name separator.
     */
    public static final String DELIMITER = "/";
    /**
     * Pattern matching NON-EMPTY strings that do NOT end with {@code /}.
     */
    private static final String NOT_ENDING_WITH_DELIMITER = "(?<=[^"+DELIMITER+"])$";

    /**
     * Returns the input path with a {@code /} appended UNLESS the
     * path already ends in {@code /} OR the path is empty.
     * @param path the input path (may be {@code null})
     * @return the path with {@code /} appended, if needed
     */
    public static String normalizeDirectoryName(String path) {
        return path == null ? "" : path.replaceFirst(NOT_ENDING_WITH_DELIMITER, DELIMITER);
    }

    private static class Edit {
        public enum Type {PUT, MKDIR, RENAME};
        public Type type;
        public String path;
        public ZipWriter writer;
        public String from;
        private Edit(Type type, String path, ZipWriter writer, String from) {
            this.type = type;
            this.path = path;
            this.writer = writer;
            this.from = from;
        }
        public static Edit put(String path, ZipWriter writer) {
            return new Edit(Type.PUT, path, writer, null);
        }
        public static Edit mkdir(String path) {
            path = normalizeDirectoryName(path);
            return new Edit(Type.MKDIR, path, null, null);
        }
        public static Edit rename(String path, String from) {
            return new Edit(Type.RENAME, path, null, from);
        }
    }

    /**
     * The {@code PathPrefixMatcher} collects a list of prefix
     * strings so they can be matched against candidate strings
     * easily, a bit like {@code String.startsWith(any of String[])}.
     */
    private static class PathPrefixMatcher {
        private List<String> prefixes;
        /**
         * Constructs a new empty matching list.  {@code matches()}
         * will return {@code false} until at least one prefix is added.
         */
        public PathPrefixMatcher() {
            this.prefixes = new ArrayList<>();
        }
        /**
         * Add a prefix string to the matching list.
         * @param prefix the string to add
         * @return {@code this}, to allow fluent style adds
         */
        public PathPrefixMatcher add(String prefix) {
            prefixes.add(normalizeDirectoryName(prefix));
            return this;
        }
        /**
         * Returns {@code true} if {@code path} starts with
         * any of the added prefix strings.
         * @param path the string to match
         * @return {@code true} if there is a match
         */
        public boolean matches(String path) {
            for (String prefix : prefixes) {
                if (path.startsWith(prefix)) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * The {@code EnumerationStepper} helper class assists in the traversal
     * of an {@link Enumeration} by exposing two properties: a {@code done}
     * flag and a current {@code value}.  If the {@code done} flag is {@code true},
     * the {@code value} is undefined.
     * <p/>
     * Can also be used as an {@link Iterator} or {@link Iterable}.
     * @param <T> the type of the {@link Enumeration}
     */
    private static class EnumerationStepper<T> implements Iterator<T>, Iterable<T> {
        private Enumeration<T> enumeration;
        private boolean done;
        private T value;
        /**
         * Creates a new stepper over an Enumeration.  Primes the
         * pump by setting the {@code done} flag based on the Enumeration's
         * {@code hasMoreElements}.  If the are elements, then the
         * {@code value} is set to the {@code nextElement}.
         * @param enumeration
         */
        public EnumerationStepper (Enumeration<T> enumeration) {
            this.enumeration = enumeration;
            this.done = !enumeration.hasMoreElements();
            if (!this.done) {
                this.value = enumeration.nextElement();
            }
        }
        /**
         * Creates a default empty stepper that is {@code done}.
         */
        public EnumerationStepper() {
            this.done = true;
        }
        /**
         * Returns {@code true} if the Enumeration is exhausted,
         * or {@code false} if there is a {@code value} available.
         * @return the done flag
         */
        public boolean done() {
            return done;
        }
        /**
         * Returns the current value of the Enumeration.  Note
         * that this value is undefined if {@code done} is {@code true}.
         * @return
         */
        public T value() {
            return value;
        }
        /**
         * Steps the Enumeration by one element, updating the {@code done}
         * flag and harvesting the {@code nextElement} into {@code value} if
         * a value is available.  It is safe (but pointless) to step an
         * Enumeration that is already {@code done}.
         */
        public void step() {
            if (!done && enumeration.hasMoreElements()) {
                this.value = enumeration.nextElement();
            } else {
                done = true;
            }
        }
        /**
         * Returns {@code this} as an {@link Iterator}.
         * @return {@code this}
         */
        @Override
        public Iterator<T> iterator() {
            return this;
        }
        /**
         * Returns the inverse of {@code done()}.
         * @return
         */
        @Override
        public boolean hasNext() {
            return !done();
        }
        /**
         * Returns the current value and steps the stepper.
         * @return the next value
         */
        @Override
        public T next() {
            T result = value();
            step();
            return result;
        }
    }

    private File original;
    private int compressionLevel;
    private TreeMap<String,Edit> adds;
    private Set<String> deletes;
    private PathPrefixMatcher rmdirs;

    /**
     * Resets the list of edits to empty.
     */
    private void reset() {
        this.adds = new TreeMap<>();
        this.deletes = new HashSet<>();
        this.rmdirs = new PathPrefixMatcher();
    }

    /**
     * Creates a new {@code ZipEditor} for a {@link File}.  Note that
     * the file need not exist and will be created as entries are added.
     * Zip file editing works by creating a new Zip file derived from the
     * orignal with edits applied, and then overwriting the original file
     * with the updated copy.  This means that write and overwrite privileges
     * are required.
     * @param original the (possibly existing but maybe not) Zip file
     */
    public ZipEditor(File original) {
        this.original = original;
        this.compressionLevel = Deflater.DEFAULT_COMPRESSION;
        reset();
    }

    /**
     * Creates a new {@code ZipEditor} from the {@link String} filename
     * as a convenience instead of a {@link File}.
     * @param filename
     */
    public ZipEditor(String filename) {
        this(new File(filename));
    }

    /**
     * Set the compressionLevel.
     * @param compressionLevel the compression level 0-9 or DEFAULT_COMPRESSION (-1)
     * @return {@code this} to allow fluent style setting
     */
    public ZipEditor compressionLevel(int compressionLevel) {
        this.compressionLevel = compressionLevel;
        return this;
    }

    /**
     * A {@code ZipWriter} is a {@code Consumer<OutputStream>} that is
     * allowed to throw an {@link IOException}.
     */
    public interface ZipWriter {
        /**
         * Write the content to the {@link OutputStream}.  It is
         * important not to close the OutputStream as subsequent
         * objects may yet be written to it.
         * @param os the {@link OutputStream} to write to (but not close)
         * @throws IOException
         */
        public void write(OutputStream os) throws IOException;
        /**
         * Returns a {@code ZipWriter} that drains and closes an
         * {@link InputStream} onto the {@link OutputStream} provided
         * to {@code write}.
         * @param is the {@link InputStream} to drain and close
         * @return a {@code ZipWriter} that consumes {@code is}
         */
        static public ZipWriter of(final InputStream is) {
            return new ZipWriter () {
                @Override
                public void write(OutputStream os) throws IOException {
                    ByteStreams.copy(is, os);
                    is.close();
                } 
            };
        }
        /**
         * Returns a {@code ZipWriter} for a {@link String}.
         * @param s a {@link String}
         * @return a {@code ZipWriter}
         */
        static public ZipWriter of(final String s) {
            return ZipWriter.of(new ByteArrayInputStream(s.getBytes()));
        }
    } 

    /**
     * Add the content supplied through {@code writer} in an
     * entry named {@code path}.  Any existing content at {@code path}
     * will be overwritten (deleted).
     * <p/>
     * Process results:<ul>
     * <li>adds will be incremented by 1</li>
     * <li>deletes will be incremented by 1 in case of an overwrite</li></ul>
     * @param path what to name the content
     * @param writer the supplier of the content (technically a {@link Consumer} of the {@link OutputStream})
     * @return {@code this}, allowing fluent-style editing
     */
    public ZipEditor add(String path, ZipWriter writer) {
        deletes.add(path);
        adds.put(path, Edit.put(path, writer));
        return this;
    }
    /**
     * Makes a "directory" in an entry named {@code path}.  By convention, Zip
     * directories end in {@code /}, so a {@code /} will be appended to {@code path}
     * if needed.  If a directory entry of the same name already exists, it will
     * be deleted.
     * <p/>
     * Process results:<ul>
     * <li>adds will be incremented by 1</li>
     * <li>deletes will be incremented by 1 in case of an overwrite</li></ul>
     * @param path what to name the directory ({@code /} will be appended if needed)
     * @return {@code this}, allowing fluent-style editing
     */
    public ZipEditor mkdir(String path) {
        path = normalizeDirectoryName(path);
        deletes.add(path);
        adds.put(path, Edit.mkdir(path));
        return this;
    }
    /**
     * Deletes an entry named {@code path}, if it exists.  By convention, Zip
     * directories end in {@code /}, so if {@code path} ends in {@code /} this
     * works as a kind of directory removal, but without the recursive deletion
     * behavior of {@code rmdir}.
     * <p/>
     * Process results:<ul>
     * <li>deletes will be incremented by 1 if the entry was found</li></ul>
     * @param path the entry to delete
     * @return {@code this}, allowing fluent-style editing
     */
    public ZipEditor delete(String path) {
        deletes.add(path);
        return this;
    }
    /**
     * Removes a directory named {@code path} and all entries prefix with
     * this path.  By convention, Zip directories end in {@code /}, so a
     * {@code /} will be appended to {@code path} if needed.
     * <p/>
     * Process results:<ul>
     * <li>deletes will be incremented by the number of entries recursively deleted</li></ul>
     * @param path
     * @return {@code this}, allowing fluent-style editing
     */
    public ZipEditor rmdir(String path) {
        path = normalizeDirectoryName(path);
        rmdirs.add(path);
        return this;
    }
    /**
     * 
     * @param from
     * @param to
     * @return {@code this}, allowing fluent-style editing
     */
    public ZipEditor rename(String from, String to) {
        deletes.add(from);
        adds.put(to, Edit.rename(to, from));
        return this;
    }

    private static final SimpleDateFormat SSS = new SimpleDateFormat("yyyyMMddHHmmss.SSS");
    /**
     * Generates a {@link File} whose name is derived from an existing
     * file's name with a unique timestamp suffix.
     * @param base the {@link File} to use as the template
     * @return a {@link File} with a unique name derived from {@code base}
     */
    private static File unique(File base) {
        String date = SSS.format(new Date());
        String candidate = base.getPath()+"-"+date;
        int i = 1;
        while (new File(candidate).exists()) {
            candidate = base.getPath()+"-"+date+"-"+String.valueOf(i);
            i++;
        }
        return new File(candidate);
    }

    /**
     * Returns the list of {@link ZipEntry} in the zip file as a {@link List}.
     * @return a {@link List} of {@link ZipEntry}, possibly empty, but never {@code null}
     */
    public List<ZipEntry> entries() {
        List<ZipEntry> result = new ArrayList<>();
        try (ZipFile zipFile = new ZipFile(original)) {
            new EnumerationStepper<>(zipFile.entries()).forEach(result::add);
            zipFile.close();
        } catch (IOException e) {
            // just return an empty stream
        }
        return result;
    }

    /**
     * Returns a {@link Set} of all the {@link ZipEntry} in the zip file.
     * @return a {@link Set} of {@link ZipEntry}, possibly empty, but never {@code null}
     */
    public Set<String> entrySet() {
        return entries().stream().map((ze)->ze.getName()).collect(Collectors.toCollection(HashSet::new));
    }

    /**
     * Returns a (possibly empty but never {@code null}) list of entries with a
     * specified {@code prefix}, emulating a directory listing.  The {@code prefix}
     * is normalized, and all unique entries with the normalized prefix and exactly
     * one additional path element are returned.
     * <p/>
     * If a Zip file contains entries named {@code prefix/subdir/item} but does not
     * contain an explicit "directory" entry names {@code prefix/subdir/}, an artificial
     * ZipEntry is synthesized and returned.
     * <p/>
     * Note that this means that {@code entries("")} will typically return a very
     * different result from {@code entries()}.
     * @param prefix the prefix to match
     * @return the list of entries
     */
    public List<ZipEntry> entries(String prefix) {
        prefix = normalizeDirectoryName(prefix);
        List<ZipEntry> list = entries();
        Set<String> directories = list.stream()
                                      .filter((e) -> e.getName().endsWith(DELIMITER))
                                      .map(ZipEntry::getName)
                                      .collect(Collectors.toCollection(HashSet::new));

        // ^prefix([^/]+)(?:/(.*))?$ 
        //   group(1) is what follows the prefix up-to-not-inlcuding /
        //   group(2) is after the trailing / if anything
        Pattern match = Pattern.compile("^"+prefix+"([^"+DELIMITER+"]+)(?:"+DELIMITER+"(.*))?$");

        List<ZipEntry> result = new ArrayList<>();
        for (ZipEntry zipentry : list) {
            Matcher m = match.matcher(zipentry.getName());
            if (m.matches()) {
                String name = m.group(1);
                boolean hasSuffix = !Strings.isNullOrEmpty(m.group(2));

                if (!hasSuffix) {
                    // a direct descendant of prefix: add it
                    result.add(zipentry);
                } else {
                    // a multi-level descendant of prefix -- make sure the implied subdirectory is there
                    String subdir = prefix+name+DELIMITER;
                    if (!directories.contains(subdir)) {
                        // found prefix/subdir/something, but prefix/subdir is not present -- fake it
                        ZipEntry fake = new ZipEntry(subdir);
                        fake.setTime(zipentry.getTime());
                        result.add(fake);
                        directories.add(subdir); // but only once...
                    }
                }
            }
        }
        return result;
    }
    /**
     * Find a {@link ZipEntry} by path name in the archive.  This
     * method uses a best-match heuristic for "directories", which by
     * convention have names ending in {@code /}.  When searching for
     * an entry, this method will prefer an exact match, with or without
     * an appended {@code /}.  If no exact match is found, but an
     * entry whose name begins with {@code path/}, a "fake" directory
     * entry will be returned using the timestamps from the first matching
     * entry.
     * <p/>
     * Returns {@code Optional.empty()} if the entry is not found.
     * @param path the path to find
     * @return the entry found
     */
    public Optional<ZipEntry> entry(String path) {
        if (Strings.isNullOrEmpty(path)) {
            // represents the ZipFile itself
        }
        String dir = normalizeDirectoryName(path);
        ZipEntry candidate = null;
        try (ZipFile zipFile = new ZipFile(original)) {
            for (ZipEntry entry : new EnumerationStepper<>(zipFile.entries())) {
                if (entry.getName().equals(path) || entry.getName().equals(dir)) {
                    return Optional.of(entry);
                }
                if (candidate == null && entry.getName().startsWith(dir)) {
                    candidate = entry;
                }
            }
        } catch (IOException e) {
            // fall through to empty
        }
        if (candidate != null) {
            ZipEntry result = new ZipEntry(dir);
            result.setCreationTime(candidate.getCreationTime());
            result.setLastAccessTime(candidate.getLastAccessTime());
            result.setLastModifiedTime(candidate.getLastModifiedTime());
            result.setTime(candidate.getTime());
            return Optional.of(result);
        }
        return Optional.empty();
    }

    /**
     * Records the result of processing a set of edits, including the
     * number of entries retained ({@code keeps()}), the number of
     * entries deleted ({@code deletes()}), and the number of new
     * entries added ({@code adds()}).  The total number of changes
     * is returned as {@code changes()}.
     * <p/>
     * Note that a replacement or rename will be counted as a
     * delete (if it existed before) and an add.
     */
    public static class ZipProcessResult {
        private int keeps = 0;
        private int adds = 0;
        private int deletes = 0;
        public void keep() {
            keeps++;
        }
        public int keeps() {
            return keeps;
        }
        public void add() {
            adds++;
        }
        public int adds() {
            return adds;
        }
        public void delete() {
            deletes++;
        }
        public int deletes() {
            return deletes;
        }
        public int changes() {
            return adds+deletes;
        }
    }

    /**
     * Processes the requested edits by creating a new Zip file from
     * the original Zip file, tracking the kinds of edits successfully
     * applied.  If no changes were applied, the new file is deleted and
     * the original file is left unchanged.  If any changes were applied,
     * the new Zip file overwrites the original.
     * <p/>
     * If the original Zip file does not exist yet, it is treated as
     * if it existed but is empty.
     * @return a summary of results in a {@link ZipProcessResult}
     * @throws IOException
     */
    public ZipProcessResult process() throws IOException {
        ZipProcessResult result = new ZipProcessResult();
        File temp = unique(original);
        ZipFile zipFile;
        EnumerationStepper<? extends ZipEntry> zipEntries;
        try {
            zipFile = new ZipFile(original);
            zipEntries = new EnumerationStepper<>(zipFile.entries());
            temp = unique(original);
        } catch (FileNotFoundException|ZipException e) {
            zipFile = null;
            zipEntries = new EnumerationStepper<>();
            temp = original;
        }
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(temp))) {
            zos.setLevel(compressionLevel);
            for (Edit add : adds.values()) {
                String addPath = add.path;
                // copy over existing zip entries up-to-but-not-including this new path.
                // this keeps the entries in sorted order (if they were already).
                while (!zipEntries.done() && zipEntries.value().getName().compareTo(addPath) < 0) {
                    String name = zipEntries.value().getName();
                    if (deletes.contains(name) || rmdirs.matches(name)) {
                        result.delete();
                    } else {
                        ZipEntry copy = new ZipEntry(zipEntries.value());
                        zos.putNextEntry(copy);
                        try (BufferedInputStream read = new BufferedInputStream(zipFile.getInputStream(zipEntries.value()))) {
                            ByteStreams.copy(read, zos);
                            result.keep();
                        } catch (IOException e) {
                            // don't increment the keep counter
                        } finally {
                            zos.closeEntry();
                        }
                    }
                    zipEntries.step();
                }
                // add in the new path
                ZipEntry addition = new ZipEntry(addPath);
                addition.setTime(new Date().getTime());
                switch (add.type) {
                case MKDIR:
                    zos.putNextEntry(addition);
                    zos.closeEntry();
                    result.add();
                    break;
                case PUT:
                    zos.putNextEntry(addition);
                    add.writer.write(new FilterOutputStream(zos) {
                        @Override
                        public void close() throws IOException {
                            // ignore it!!!
                        }
                    });
                    zos.closeEntry();
                    result.add();
                    break;
                case RENAME:
                    ZipEntry source = zipFile.getEntry(add.from);
                    if (source != null) {
                        addition.setTime(source.getTime());
                        zos.putNextEntry(addition);
                        try (BufferedInputStream read = new BufferedInputStream(zipFile.getInputStream(source))) {
                            ByteStreams.copy(read, zos);
                            result.add();
                        } catch (IOException e) {
                            // don't increment the add counter
                        } finally {
                            zos.closeEntry();
                        }
                    }
                    break;
                default:
                    break;
                }
            }
            // copy over any remaining entries
            while (!zipEntries.done()) {
                String name = zipEntries.value().getName();
                if (deletes.contains(name) || rmdirs.matches(name)) {
                    result.delete();
                } else {
                    ZipEntry copy = new ZipEntry(zipEntries.value());
                    zos.putNextEntry(copy);
                    try (BufferedInputStream read = new BufferedInputStream(zipFile.getInputStream(zipEntries.value()))) {
                        ByteStreams.copy(read, zos);
                        zos.closeEntry();
                    }
                    result.keep();
                }
                zipEntries.step();
            }
        } finally {
            if (zipFile != null) {
                zipFile.close();
            }
        }
        if (result.changes() == 0) {
            // nothing happened -- delete the file we just wrote
            Files.delete(temp.toPath());
        } else if (zipFile != null) {
            // we made a new file (not just a brand new file) -- overwrite the original
            Files.move(temp.toPath(), original.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        reset(); // once processed the updates are discarded
        return result;
    }
}