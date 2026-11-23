// search.html?q=...: search by a text description or by an example image.
import Alpine from "alpinejs";
import { searchByImage, searchByText, thumbnailUrl, type SearchHit, type SearchResult } from "./api";
import { errorMessage, fileName, photoCount } from "./format";
import { registerViewer, viewPhotos } from "./viewer";

const LIMIT = 60;

Alpine.data("search", () => ({
    query: "",
    hits: [] as SearchHit[],
    /** What the results are for: the query or the image name. */
    searchedFor: "",
    searching: false,
    error: "",
    fileName,
    thumbnailUrl,

    init() {
        // The query is kept in the address: a search can be bookmarked and reloaded
        const query = new URLSearchParams(location.search).get("q");
        if (query) {
            this.query = query;
            void this.byText();
        }
    },

    async byText() {
        const query = this.query.trim();
        history.replaceState(null, "", `?q=${encodeURIComponent(query)}`);
        await this.run(`“${query}”`, () => searchByText(query, LIMIT));
    },

    async byImage(event: Event) {
        const input = event.target as HTMLInputElement;
        const image = input.files?.[0];
        // The same file can be chosen again
        input.value = "";
        if (image) {
            history.replaceState(null, "", location.pathname);
            await this.run(image.name, () => searchByImage(image, LIMIT));
        }
    },

    summary(): string {
        return this.hits.length > 0
            ? `${photoCount(this.hits.length)} most like ${this.searchedFor}, best first`
            : `Nothing found for ${this.searchedFor}`;
    },

    async run(searchedFor: string, search: () => Promise<SearchResult>) {
        this.searching = true;
        this.error = "";
        try {
            this.hits = (await search()).items;
            this.searchedFor = searchedFor;
        } catch (error) {
            this.hits = [];
            this.error = errorMessage(error);
        } finally {
            this.searching = false;
        }
    },

    view(index: number) {
        viewPhotos(this.hits.map((hit) => ({ id: hit.photoId, path: hit.files[0].path })), index);
    },
}));

registerViewer();
Alpine.start();
