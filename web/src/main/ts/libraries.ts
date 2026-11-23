// index.html: the folders added to the catalog.
import Alpine from "alpinejs";
import { addLibrary, listLibraries, rescanLibrary, type Library } from "./api";
import { count, dateTime, errorMessage, statusLabel } from "./format";

/** How often the list is refreshed while a folder is being scanned, and while photos are being indexed. */
const SCANNING_REFRESH_MS = 2000;
const INDEXING_REFRESH_MS = 5000;

Alpine.data("libraries", () => ({
    libraries: [] as Library[],
    loaded: false,
    path: "",
    adding: false,
    error: "",
    refreshTimer: 0,
    count,
    statusLabel,

    async init() {
        await this.refresh();
    },

    async refresh() {
        window.clearTimeout(this.refreshTimer);
        try {
            this.libraries = await listLibraries();
            this.loaded = true;
        } catch (error) {
            this.error = errorMessage(error);
        }
        const statuses = new Set(this.libraries.map((library) => library.status));
        const interval = statuses.has("SCANNING") ? SCANNING_REFRESH_MS
            : statuses.has("INDEXING") ? INDEXING_REFRESH_MS
            : 0;
        if (interval > 0) {
            this.refreshTimer = window.setTimeout(() => this.refresh(), interval);
        }
    },

    async add() {
        this.adding = true;
        this.error = "";
        try {
            await addLibrary(this.path.trim());
            this.path = "";
            await this.refresh();
        } catch (error) {
            this.error = errorMessage(error);
        } finally {
            this.adding = false;
        }
    },

    async rescan(library: Library) {
        this.error = "";
        try {
            await rescanLibrary(library.id);
            await this.refresh();
        } catch (error) {
            this.error = errorMessage(error);
        }
    },

    lastScan(library: Library): string {
        if (library.status === "SCANNING") {
            return library.lastScanStartedAt ? `Scan started ${dateTime(library.lastScanStartedAt)}` : "";
        }
        return library.lastScanFinishedAt ? `Scanned ${dateTime(library.lastScanFinishedAt)}` : "Not scanned yet";
    },
}));

Alpine.start();
