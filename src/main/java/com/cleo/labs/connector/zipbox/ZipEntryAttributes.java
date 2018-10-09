package com.cleo.labs.connector.zipbox;

import java.io.IOException;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.DosFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.Date;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;

/**
 * Zip file attribute views
 */
public class ZipEntryAttributes implements DosFileAttributes, DosFileAttributeView {
    ZipEntry entry;

    @SafeVarargs
    private final FileTime someTime(Supplier<FileTime>... attempts) {
        for (Supplier<FileTime> attempt : attempts) {
            FileTime result = attempt.get();
            if (result != null) {
                return result;
            }
        }
        return FileTime.from(new Date().getTime(), TimeUnit.MILLISECONDS);
    }

    public ZipEntryAttributes(ZipEntry entry) {
        this.entry = entry;
    }

    @Override
    public FileTime lastModifiedTime() {
        return someTime(() -> entry.getLastModifiedTime(),
                        () -> FileTime.from(entry.getTime(), TimeUnit.MILLISECONDS),
                        () -> entry.getCreationTime(),
                        () -> entry.getLastAccessTime());
    }

    @Override
    public FileTime lastAccessTime() {
        return someTime(() -> entry.getLastAccessTime(),
                        () -> entry.getLastModifiedTime(),
                        () -> FileTime.from(entry.getTime(), TimeUnit.MILLISECONDS),
                        () -> entry.getCreationTime());
    }

    @Override
    public FileTime creationTime() {
        return someTime(() -> entry.getCreationTime(),
                        () -> entry.getLastModifiedTime(),
                        () -> FileTime.from(entry.getTime(), TimeUnit.MILLISECONDS),
                        () -> entry.getLastAccessTime());
    }

    @Override
    public boolean isRegularFile() {
        return !entry.isDirectory();
    }

    @Override
    public boolean isDirectory() {
        return entry.isDirectory();
    }

    @Override
    public boolean isSymbolicLink() {
        return false;
    }

    @Override
    public boolean isOther() {
        return false;
    }

    @Override
    public long size() {
        return entry.getSize();
    }

    @Override
    public Object fileKey() {
        return null;
    }

    @Override
    public void setTimes(FileTime lastModifiedTime, FileTime lastAccessTime, FileTime createTime) throws IOException {
        if (lastModifiedTime != null || lastAccessTime != null || createTime != null) {
            throw new UnsupportedOperationException("setTimes() not supported on Zip streams");
        }
    }

    @Override
    public String name() {
        return "zipentry";
    }

    @Override
    public DosFileAttributes readAttributes() throws IOException {
        return this;
    }

    @Override
    public void setReadOnly(boolean value) throws IOException {
        throw new UnsupportedOperationException("setHidden() not supported on Zip streams");
    }

    @Override
    public void setHidden(boolean value) throws IOException {
        throw new UnsupportedOperationException("setHidden() not supported on Zip streams");
    }

    @Override
    public void setSystem(boolean value) throws IOException {
        throw new UnsupportedOperationException("setSystem() not supported on Zip streams");
    }

    @Override
    public void setArchive(boolean value) throws IOException {
        throw new UnsupportedOperationException("setArchive() not supported on Zip streams");
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public boolean isHidden() {
        return false;
    }

    @Override
    public boolean isArchive() {
        return false;
    }

    @Override
    public boolean isSystem() {
        return false;
    }

}
