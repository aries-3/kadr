// Values as the pages show them.
import type { LibraryStatus } from "./api";

// Record over every status: a status added to the contract fails the build until it has a label
const STATUS_LABELS: Record<LibraryStatus, string> = {
    SCANNING: "Scanning",
    INDEXING: "Indexing",
    READY: "Ready",
    UNAVAILABLE: "Unavailable",
};

const NUMBERS = new Intl.NumberFormat();
const DATE_TIMES = new Intl.DateTimeFormat(undefined, { dateStyle: "medium", timeStyle: "short" });

export function statusLabel(status: LibraryStatus): string {
    return STATUS_LABELS[status];
}

export function count(value: number): string {
    return NUMBERS.format(value);
}

export function photoCount(value: number): string {
    return `${count(value)} ${value === 1 ? "photo" : "photos"}`;
}

export function dateTime(iso: string): string {
    return DATE_TIMES.format(new Date(iso));
}

/** The last part of a path. */
export function fileName(path: string): string {
    return path.slice(path.lastIndexOf("/") + 1);
}

export function errorMessage(error: unknown): string {
    return error instanceof Error ? error.message : String(error);
}
