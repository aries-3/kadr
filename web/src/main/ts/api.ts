// Calls of the Kadr REST API. The types come from the API contract: when it changes,
// the build checks this code and the pages against it.
import type { components } from "@generated/kadr-api";

type Schemas = components["schemas"];

export type Library = Schemas["Library"];
export type LibraryStatus = Schemas["LibraryStatus"];
export type Photo = Schemas["Photo"];
export type PhotoPage = Schemas["PhotoPage"];
export type SearchHit = Schemas["SearchHit"];
export type SearchResult = Schemas["SearchResult"];

/** An error answer of the API; the message is for the user. */
export class ApiError extends Error {
    constructor(readonly status: number, message: string) {
        super(message);
    }
}

export function listLibraries(): Promise<Library[]> {
    return request("/api/libraries");
}

export function getLibrary(id: number): Promise<Library> {
    return request(`/api/libraries/${id}`);
}

export function addLibrary(path: string): Promise<Library> {
    const body: Schemas["AddLibraryRequest"] = { path };
    return request("/api/libraries", { method: "POST", json: body });
}

export function rescanLibrary(id: number): Promise<Library> {
    return request(`/api/libraries/${id}/rescan`, { method: "POST" });
}

export function listLibraryPhotos(id: number, offset: number, limit: number): Promise<PhotoPage> {
    return request(`/api/libraries/${id}/photos?offset=${offset}&limit=${limit}`);
}

export function searchByText(query: string, limit: number): Promise<SearchResult> {
    const body: Schemas["TextSearchRequest"] = { query, limit };
    return request("/api/search/text", { method: "POST", json: body });
}

export function searchByImage(image: File, limit: number): Promise<SearchResult> {
    const form = new FormData();
    form.append("image", image);
    form.append("limit", String(limit));
    return request("/api/search/image", { method: "POST", body: form });
}

export function thumbnailUrl(photoId: number): string {
    return `/api/photos/${photoId}/thumbnail`;
}

export function fileUrl(photoId: number): string {
    return `/api/photos/${photoId}/file`;
}

interface RequestOptions {
    method?: string;
    /** Sent as JSON. */
    json?: unknown;
    /** Sent as it is, e.g. a form with a file. */
    body?: BodyInit;
}

async function request<T>(url: string, options: RequestOptions = {}): Promise<T> {
    const response = await fetch(url, {
        method: options.method ?? "GET",
        headers: options.json === undefined ? {} : { "Content-Type": "application/json" },
        body: options.json === undefined ? options.body : JSON.stringify(options.json),
    });
    if (!response.ok) {
        throw new ApiError(response.status, await problem(response));
    }
    return (await response.json()) as T;
}

/** What went wrong, from the Problem Details of the answer. */
async function problem(response: Response): Promise<string> {
    try {
        const problem = (await response.json()) as Schemas["ProblemDetail"];
        return problem.detail ?? problem.title ?? `Error ${response.status}`;
    } catch {
        return `Error ${response.status}`;
    }
}
