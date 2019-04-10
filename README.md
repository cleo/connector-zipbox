# README #

The Cleo Harmony/VLTrader (aka VersaLex) Connector Shell API allows new connector
technologies to be plugged in to be configured and used by the administrator.

This sample project provides an extended example of a connector, intended to expand
on the concept introduced in the
[Random](https://github.com/cleo/connector-random/) connector.
It provides support for handling ZIP archives as if they were expanded folders,
including uploading and downloading files and manipulation of directories.
For example, if a ZIPBox connection is used as a VersaLex user's home directory,
all of the user's files will be managed in a single Zip archive in the underlying
file system.

## TL;DR ##

The POM for this project creates a ZIP archive intended to be expanded from
the Harmony/VLTrader installation directory (`$CLEOHOME` below).

```
git clone git@github.com:cleo/connector-zipbox.git
mvn clean package
cp target/zipbox-5.6.0.0-distribution.zip $CLEOHOME
cd $CLEOHOME
unzip -o zipbox-5.6.0.0-distribution.zip
./Harmonyd stop
./Harmonyd start
```

When Harmony/VLTrader restarts, you will see a new `Template` in the host tree
under `Connections` > `Generic` > `Generic ZIPBox`.  Select `Clone and Activate`
and a new `ZIPBox` connection (host) will appear on the `Active` tab.

Change the default `<receive>` action to read `GET test.bin` (instead of `GET *`)
and run the action.  You will find a 1k (1024 byte) file of nulls in `inbox/test.bin`.

## New Connector Shell Concepts ##


Some basics of the Connector Shell were introduced in the
[Random Connector](https://github.com/cleo/connector-random/)
In particular, the Random
connector illustrates the structure of a Connector Shell project and its schema,
configuration, and client command processor classes.  But as a simple source and
sink of random byte streams, its command support is artificially limited to
`GET`, `PUT`, and simple stubs for `DELETE` and `ATTR`.

The ZipBox Connector emulates a complete read/write file system folder packed into
a single Zip file.  It rounds out the command set to include:

* file upload and download with `GET` and `PUT`
* directory listing with `DIR` and the `Entry` class
* directory manipulation with `MKDIR` and `RMDIR`
* file manipulation with `DELETE`, `RENAME`, and a full implementation of `ATTR`

