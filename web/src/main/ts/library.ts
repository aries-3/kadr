// library.html?id=N: the photos of one folder, as thumbnails.
import Alpine from "alpinejs";
import { getLibrary, listLibraryPhotos, thumbnailUrl, type Library, type Photo } from "./api";
import { count, errorMessage, fileName, photoCount, statusLabel } from "./format";
import { registerViewer, viewPhotos } from "./viewer";

/** Photos per request: divides evenly into rows of 2 to 6, 8, 10 and 12. */
const PAGE_SIZE = 120;

Alpine.data("library", () => ({
    id: Number(new URLSearchParams(location.search).get("id")),
    library: null as Library | null,
    photos: [] as Photo[],
    total: 0,
    loaded: false,
    loading: false,
    error: "",
    count,
    fileName,
    photoCount,
    statusLabel,
    thumbnailUrl,

    async init() {
        if (!Number.isInteger(this.id) || this.id <= 0) {
            this.error = "No folder is chosen: open one from the folder list";
            return;
        }
        try {
            this.library = await getLibrary(this.id);
            document.title = `${fileName(this.library.path)} · Kadr`;
            await this.more();
            this.loaded = true;
        } catch (error) {
            this.error = errorMessage(error);
        }
    },

    async more() {
        this.loading = true;
        try {
            const page = await listLibraryPhotos(this.id, this.photos.length, PAGE_SIZE);
            this.photos.push(...page.items);
            this.total = page.total;
        } catch (error) {
            this.error = errorMessage(error);
        } finally {
            this.loading = false;
        }
    },

    view(index: number) {
        viewPhotos(this.photos.map((photo) => ({ id: photo.id, path: photo.files[0].path })), index);
    },
}));

registerViewer();
Alpine.start();
