// The photo viewer: a full-screen <dialog x-data="viewer"> that shows original files.
// A page opens it with viewPhotos(); the arrow keys go to the previous and the next photo.
import Alpine from "alpinejs";
import { fileUrl } from "./api";
import { fileName } from "./format";

export interface ViewedPhoto {
    id: number;
    path: string;
}

interface ViewRequest {
    photos: ViewedPhoto[];
    index: number;
}

const VIEW_EVENT = "view-photos";

/** Opens the viewer on one of the photos. */
export function viewPhotos(photos: ViewedPhoto[], index: number): void {
    window.dispatchEvent(new CustomEvent<ViewRequest>(VIEW_EVENT, { detail: { photos, index } }));
}

/** Registers the component; call before Alpine.start(). */
export function registerViewer(): void {
    Alpine.data("viewer", () => ({
        photos: [] as ViewedPhoto[],
        index: 0,
        fileUrl,
        fileName,

        get current(): ViewedPhoto | undefined {
            return this.photos[this.index];
        },

        open(request: ViewRequest) {
            this.photos = request.photos;
            this.index = request.index;
            (this.$root as HTMLDialogElement).showModal();
        },

        close() {
            (this.$root as HTMLDialogElement).close();
        },

        previous() {
            this.index = Math.max(0, this.index - 1);
        },

        next() {
            this.index = Math.min(this.photos.length - 1, this.index + 1);
        },
    }));
}
