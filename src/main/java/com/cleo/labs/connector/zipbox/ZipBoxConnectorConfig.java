package com.cleo.labs.connector.zipbox;

import java.util.zip.Deflater;

import com.cleo.connector.api.property.ConnectorPropertyException;
import com.google.common.base.Strings;

public class ZipBoxConnectorConfig {
    private ZipBoxConnectorClient client;
    private ZipBoxConnectorSchema schema;

    public ZipBoxConnectorConfig(ZipBoxConnectorClient client, ZipBoxConnectorSchema schema) {
        this.client = client;
        this.schema = schema;
    }

    public String getZipFile() throws ConnectorPropertyException {
        return schema.zipFile.getValue(client);
    }

    public int getCompressionLevel() throws ConnectorPropertyException {
        String value = schema.compressionLEvel.getValue(client);
        if (Strings.isNullOrEmpty(value) || value.equalsIgnoreCase(ZipBoxConnectorSchema.DEFAULT)) {
            return Deflater.DEFAULT_COMPRESSION;
        } else {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                throw new ConnectorPropertyException(e);
            }
        }
    }
}
