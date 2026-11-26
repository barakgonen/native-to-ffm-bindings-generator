#!/bin/sh
# Installs the plugin into a Maven repository with neither Maven nor a network: copies its jar and its POM into the
# repository's folder layout, which is all Maven needs to find the plugin (offline included).
#
#   install-offline.sh <native-to-ffm-bindings-generator-VERSION.jar> <its pom.xml> [repository]
#
# repository: default ~/.m2/repository (the local repository). For a shared repository manager, upload the two files
# there instead (or mvn deploy:deploy-file).
set -eu
JAR=${1:?usage: install-offline.sh <plugin jar> <plugin pom.xml> [repository]}
POM=${2:?usage: install-offline.sh <plugin jar> <plugin pom.xml> [repository]}
REPO=${3:-$HOME/.m2/repository}
ARTIFACT=native-to-ffm-bindings-generator
NAME=$(basename "$JAR" .jar)
VERSION=${NAME#"$ARTIFACT"-}
[ "$VERSION" != "$NAME" ] || { echo "install-offline.sh: $JAR is not $ARTIFACT-<version>.jar"; exit 1; }
DIR=$REPO/io/nativeffm/$ARTIFACT/$VERSION
mkdir -p "$DIR"
cp "$JAR" "$DIR/$ARTIFACT-$VERSION.jar"
cp "$POM" "$DIR/$ARTIFACT-$VERSION.pom"
echo "installed io.nativeffm:$ARTIFACT:$VERSION in $DIR"
