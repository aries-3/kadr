package com.teacode.kazakov.kadr.domain.catalog;

/**
 * A folder with the counts of its files.
 *
 * @param library  the folder
 * @param progress counts of the files and photos inside the folder
 */
public record LibraryOverview(Library library, LibraryProgress progress) {

    /**
     * {@return the state of the folder as shown to the user}
     * {@link LibraryStatus#SCANNING} while a scan is requested or running;
     * {@link LibraryStatus#UNAVAILABLE} if the last scan couldn't read the folder; otherwise
     * {@link LibraryStatus#INDEXING} while photos are pending, else {@link LibraryStatus#READY}.
     */
    public LibraryStatus status() {
        if (library.scanState() == ScanState.SCANNING) {
            return LibraryStatus.SCANNING;
        }
        if (library.scanOutcome() == ScanOutcome.UNAVAILABLE) {
            return LibraryStatus.UNAVAILABLE;
        }
        return progress.pending() > 0 ? LibraryStatus.INDEXING : LibraryStatus.READY;
    }
}
