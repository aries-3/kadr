package com.teacode.kazakov.kadr.domain.files;

import java.nio.file.Path;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Photo folders on disk.
 */
public interface FileTree {

    /**
     * Checks that a folder exists and can be listed.
     *
     * @param folder absolute path of the folder
     * @return the real path of the folder, without symbolic links
     * @throws FolderUnavailableException if the folder doesn't exist, isn't a folder or can't be listed
     */
    Path realFolder(Path folder) throws FolderUnavailableException;

    /**
     * Walks the photo files inside a folder, see {@link PhotoFiles}.
     * <p>
     * Symbolic links aren't followed; skipped folders aren't entered; entries that can't be read
     * are skipped.
     *
     * @param folder        absolute path of the folder
     * @param stopRequested polled before each folder and file, photo or not; once it returns
     *                      {@code true}, the walk ends
     * @param visitor       gets each photo file
     */
    void walk(Path folder, BooleanSupplier stopRequested, Consumer<FileEntry> visitor);
}
