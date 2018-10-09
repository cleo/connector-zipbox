package com.cleo.labs.connector.zipbox;

import static com.cleo.connector.api.command.ConnectorCommandName.ATTR;
import static com.cleo.connector.api.command.ConnectorCommandName.DELETE;
import static com.cleo.connector.api.command.ConnectorCommandName.DIR;
import static com.cleo.connector.api.command.ConnectorCommandName.GET;
import static com.cleo.connector.api.command.ConnectorCommandName.MKDIR;
import static com.cleo.connector.api.command.ConnectorCommandName.PUT;
import static com.cleo.connector.api.command.ConnectorCommandName.RENAME;
import static com.cleo.connector.api.command.ConnectorCommandName.RMDIR;
import static com.cleo.connector.api.command.ConnectorCommandOption.Delete;
import static com.cleo.connector.api.command.ConnectorCommandOption.Unique;

import java.io.File;
import java.io.IOException;
import java.nio.file.attribute.BasicFileAttributeView;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.apache.commons.io.FilenameUtils;

import com.cleo.connector.api.ConnectorClient;
import com.cleo.connector.api.ConnectorException;
import com.cleo.connector.api.annotations.Command;
import com.cleo.connector.api.command.ConnectorCommandResult;
import com.cleo.connector.api.command.ConnectorCommandResult.Status;
import com.cleo.connector.api.command.ConnectorCommandUtil;
import com.cleo.connector.api.command.DirCommand;
import com.cleo.connector.api.command.GetCommand;
import com.cleo.connector.api.command.OtherCommand;
import com.cleo.connector.api.command.PutCommand;
import com.cleo.connector.api.directory.Directory.Type;
import com.cleo.connector.api.directory.Entry;
import com.cleo.connector.api.helper.Attributes;
import com.cleo.connector.api.interfaces.IConnectorIncoming;
import com.cleo.connector.api.interfaces.IConnectorOutgoing;
import com.cleo.labs.connector.zipbox.ZipEditor.ZipProcessResult;
import com.google.common.base.Strings;

public class ZipBoxConnectorClient extends ConnectorClient {
    private ZipBoxConnectorConfig config;

    /**
     * Constructs a new {@code ZipBoxConnectorClient} for the schema
     * @param schema the {@code ZipBoxConnectorSchema}
     */
    public ZipBoxConnectorClient(ZipBoxConnectorSchema schema) {
        this.config = new ZipBoxConnectorConfig(this, schema);
    }

    /**
     * Returns a {@link File} for the configured ZIP file.
     * @return the {@link File}
     * @throws ConnectorException if the file does not exist
     */
    private File openFile(boolean mustExist) throws ConnectorException {
        File file = new File(config.getZipFile());
        if (mustExist && !file.exists()) {
            throw new ConnectorException(String.format("'%s' does not exist or is not accessible", config.getZipFile()),
                    ConnectorException.Category.fileNonExistentOrNoAccess);
        }
        return file;
    }

    @Command(name=DIR)
    public ConnectorCommandResult dir(DirCommand dir) throws ConnectorException, IOException
    {
        String source = dir.getSource().getPath();

        logger.debug(String.format("DIR '%s'", source));

        if (source.equals(".")) source = ""; // TODO: remove when Harmony is fixed
        List<Entry> list = new ArrayList<>();
        for (ZipEntry zipentry : new ZipEditor(config.getZipFile()).entries(source)) {
            Entry entry = new Entry(zipentry.isDirectory() ? Type.dir : Type.file);
            entry.setDate(Attributes.toLocalDateTime(zipentry.getTime()));
            entry.setPath(zipentry.getName());
            entry.setSize(zipentry.isDirectory() ? -1L : zipentry.getSize());
            list.add(entry);
        }
        return new ConnectorCommandResult(Status.Success, Optional.empty(), list);
    }

    @Command(name = GET)
    public ConnectorCommandResult get(GetCommand get) throws ConnectorException, IOException {
        String source = get.getSource().getPath();
        IConnectorIncoming destination = get.getDestination();

        logger.debug(String.format("GET remote '%s' to local '%s'", source, destination.getPath()));

        File file = new File(config.getZipFile());
        try (ZipFile zipFile = new ZipFile(file)) {
            ZipEntry entry = zipFile.getEntry(source);
            if (entry != null) {
                transfer(zipFile.getInputStream(entry), destination.getStream(), true);
                return new ConnectorCommandResult(ConnectorCommandResult.Status.Success);
            }
        } catch (IOException e) {
            // fall through to fileNonExistentOfNoAccess
        }
        throw new ConnectorException(String.format("'%s' does not exist or is not accessible", source),
            ConnectorException.Category.fileNonExistentOrNoAccess);
    }

    @Command(name = PUT, options = { Unique, Delete })
    public ConnectorCommandResult put(PutCommand put) throws ConnectorException, IOException {
        String destination = put.getDestination().getPath();
        IConnectorOutgoing source = put.getSource();

        logger.debug(String.format("PUT local '%s' to remote '%s'", source.getPath(), destination));

        File file = new File(config.getZipFile());
        ZipEditor zip = new ZipEditor(file).compressionLevel(config.getCompressionLevel());
        if (ConnectorCommandUtil.isOptionOn(put.getOptions(), Unique)) {
            Set<String> index = zip.entries().stream().map((ze)->ze.getName()).collect(Collectors.toCollection(HashSet::new));
            String base = FilenameUtils.getBaseName(destination);
            String ext = FilenameUtils.getExtension(destination).replaceFirst("^(?=[^\\.])", ".");
            int i = 0;
            while (index.contains(destination)) {
                i++;
                destination = String.format("%s.%d%s", base, i, ext);
            }
        }

        ZipProcessResult result = zip.add(destination, (os)->transfer(source.getStream(), os, false)).process();

        if (result.adds() != 1) {
            return new ConnectorCommandResult(ConnectorCommandResult.Status.Error,
                    String.format("'%s' not created.", destination));
        } else {
            return new ConnectorCommandResult(ConnectorCommandResult.Status.Success);
        }
    }

    /**
     * Get the file attribute view associated with a file path
     * 
     * @param path the file path
     * @return the file attributes
     * @throws com.cleo.connector.api.ConnectorException
     * @throws java.io.IOException
     */
    @Command(name = ATTR)
    public BasicFileAttributeView getAttributes(String path) throws ConnectorException, IOException {
        logger.debug(String.format("ATTR '%s'", path));
        File file = openFile(true);
        if (Strings.isNullOrEmpty(path) || path.equals(".")) { // TODO: remove . check once Harmony fixed
            // the root path gets the attributes of the file itself
            return new ZipFileAttributes(file);
        } else {
            Optional<ZipEntry> entry = new ZipEditor(file).entry(path);
            if (entry.isPresent()) {
                return new ZipEntryAttributes(entry.get());
            } else {
                throw new ConnectorException(String.format("'%s' does not exist or is not accessible", path),
                        ConnectorException.Category.fileNonExistentOrNoAccess);
            }
        }
    }

    @Command(name = DELETE)
    public ConnectorCommandResult delete(OtherCommand delete) throws ConnectorException, IOException {
        String source = delete.getSource();
        logger.debug(String.format("DELETE '%s'", source));
        
        File file = openFile(true);
        ZipEditor zip = new ZipEditor(file).compressionLevel(config.getCompressionLevel());
        ZipProcessResult result = zip.delete(source).process();
        if (result.deletes() == 0) {
            throw new ConnectorException(String.format("'%s' does not exist or is not accessible", source),
                    ConnectorException.Category.fileNonExistentOrNoAccess);
        }
        return new ConnectorCommandResult(ConnectorCommandResult.Status.Success);
    }

    @Command(name = RENAME)
    public ConnectorCommandResult rename(OtherCommand rename) throws ConnectorException, IOException {
        String from = rename.getSource();
        String to = rename.getDestination();
        logger.debug(String.format("RENAME '%s' '%s'", from, to));

        File file = openFile(true);
        ZipEditor zip = new ZipEditor(file).compressionLevel(config.getCompressionLevel());
        ZipProcessResult result = zip.rename(from, to).process();
        if (result.deletes() == 0) {
            throw new ConnectorException(String.format("'%s' does not exist or is not accessible", from),
                    ConnectorException.Category.fileNonExistentOrNoAccess);
        } else if (result.adds() == 0) {
            return new ConnectorCommandResult(Status.Error, "Rename failed.");
        } else {
            return new ConnectorCommandResult(Status.Success);
        }
    }

    @Command(name = MKDIR)
    public ConnectorCommandResult mkdir(OtherCommand mkdir) throws ConnectorException, IOException {
        String source = mkdir.getSource();
        logger.debug(String.format("MKDIR '%s'", source));

        if (Strings.isNullOrEmpty(source) || source.equals(".")) {
            return new ConnectorCommandResult(ConnectorCommandResult.Status.Success);
        //  return new ConnectorCommandResult(ConnectorCommandResult.Status.Error,
        //          String.format("'%s' already exists.", source));
        }

        File file = openFile(false);
        ZipEditor zip = new ZipEditor(file).compressionLevel(config.getCompressionLevel());
        ZipProcessResult result = zip.mkdir(source).process();
        if (result.deletes() > 0) {
            return new ConnectorCommandResult(ConnectorCommandResult.Status.Error,
                    String.format("'%s' already exists.", source));
        } else if (result.adds() == 0) {
            return new ConnectorCommandResult(ConnectorCommandResult.Status.Error,
                    String.format("'%s' not created.", source));
        }
        return new ConnectorCommandResult(ConnectorCommandResult.Status.Success);
    }

    @Command(name = RMDIR)
    public ConnectorCommandResult rmdir(OtherCommand rmdir) throws ConnectorException, IOException {
        String source = rmdir.getSource();
        logger.debug(String.format("RMDIR '%s'", source));

        if (Strings.isNullOrEmpty(source) || source.equals(".")) {
            throw new ConnectorException(String.format("'%s' does not exist or is not accessible", source),
                    ConnectorException.Category.fileNonExistentOrNoAccess);
        }

        File file = openFile(true);
        ZipEditor zip = new ZipEditor(file).compressionLevel(config.getCompressionLevel());
        ZipProcessResult result = zip.rmdir(source).process();
        if (result.deletes() == 0) {
            throw new ConnectorException(String.format("'%s' does not exist or is not accessible", source),
                    ConnectorException.Category.fileNonExistentOrNoAccess);
        }
        return new ConnectorCommandResult(ConnectorCommandResult.Status.Success);
    }
}
