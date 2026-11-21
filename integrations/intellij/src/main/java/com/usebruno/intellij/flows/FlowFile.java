package com.usebruno.intellij.flows;

import java.nio.file.Path;

record FlowFile(Path path, Path collectionRoot) {
    @Override
    public String toString() {
        if (!path.isAbsolute() || !collectionRoot.isAbsolute()) return path.toString().replace('\\', '/');
        return collectionRoot.relativize(path).toString().replace('\\', '/');
    }
}
