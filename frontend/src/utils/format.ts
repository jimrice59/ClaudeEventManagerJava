/**
 * Human-friendly formatting, via the browser's Intl APIs (so output follows the viewer's own
 * locale and time zone, with no date library).
 */

const dateTimeFormat = new Intl.DateTimeFormat(undefined, {
  weekday: "short",
  month: "short",
  day: "numeric",
  year: "numeric",
  hour: "numeric",
  minute: "2-digit",
});

/** e.g. "Wed, Nov 11, 2026, 2:39 PM" (exact punctuation varies by locale). */
export function formatDateTime(iso: string): string {
  return dateTimeFormat.format(new Date(iso));
}

const relativeFormat = new Intl.RelativeTimeFormat(undefined, { numeric: "auto" });

const UNITS: [Intl.RelativeTimeFormatUnit, number][] = [
  ["year", 365 * 24 * 3600],
  ["month", 30 * 24 * 3600],
  ["week", 7 * 24 * 3600],
  ["day", 24 * 3600],
  ["hour", 3600],
  ["minute", 60],
];

/** e.g. "5 minutes ago", "yesterday", "3 weeks ago"; "just now" under a minute. */
export function formatRelative(iso: string, now: Date = new Date()): string {
  const seconds = (new Date(iso).getTime() - now.getTime()) / 1000;
  for (const [unit, size] of UNITS) {
    if (Math.abs(seconds) >= size) {
      return relativeFormat.format(Math.round(seconds / size), unit);
    }
  }
  return "just now";
}

const moneyFormat = new Intl.NumberFormat(undefined, { style: "currency", currency: "USD" });

/** e.g. "$100.00". Prices in this app are USD. */
export function formatMoney(amount: number): string {
  return moneyFormat.format(amount);
}
