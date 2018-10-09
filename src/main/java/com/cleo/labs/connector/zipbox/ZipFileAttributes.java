package com.cleo.labs.connector.zipbox;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.DosFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.Date;
import java.util.concurrent.TimeUnit;

/**
 * Zip file attribute views
 */
public class ZipFileAttributes implements DosFileAttributes, DosFileAttributeView {
    DosFileAttributes attrs;
    FileTime now = FileTime.from(new Date().getTime(), TimeUnit.MILLISECONDS);

    public ZipFileAttributes(File zipFile) throws IOException {
        try {
            this.attrs = Files.readAttributes(zipFile.toPath(), DosFileAttributes.class);
        } catch (Exception e) {
            // leave it null
        }
    }

    @Override
    public FileTime lastModifiedTime() {
        return attrs == null ? now :  attrs.lastModifiedTime();
    }

    @Override
    public FileTime lastAccessTime() {
        return attrs == null ? now : attrs.lastAccessTime();
    }

    @Override
    public FileTime creationTime() {
        return attrs == null ? now : attrs.creationTime();
    }

    @Override
    public boolean isRegularFile() {
        return false; // pretend the .zip file is a directory containing the ZipEntrys
    }

    @Override
    public boolean isDirectory() {
        return true; // pretend the .zip file is a directory containing the ZipEntrys
    }

    @Override
    public boolean isSymbolicLink() {
        return false; // pretend the .zip file is a directory containing the ZipEntrys
    }

    @Override
    public boolean isOther() {
        return false; // pretend the .zip file is a directory containing the ZipEntrys
    }

    @Override
    public long size() {
        return attrs == null ? 0L : attrs.size();
    }

    @Override
    public Object fileKey() {
        return attrs == null ? null : attrs.fileKey();
    }

    @Override
    public void setTimes(FileTime lastModifiedTime, FileTime lastAccessTime, FileTime createTime) throws IOException {
        if (lastModifiedTime != null || lastAccessTime != null || createTime != null) {
            throw new UnsupportedOperationException("setTimes() not supported on Zip streams");
        }
    }

    @Override
    public String name() {
        return "zipfile";
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
        return true;
    }

    @Override
    public boolean isHidden() {
        return attrs == null ? false : attrs.isHidden();
    }

    @Override
    public boolean isArchive() {
        return attrs == null ? false : attrs.isArchive();
    }

    @Override
    public boolean isSystem() {
        return attrs == null ? false : attrs.isSystem();
    }

}
