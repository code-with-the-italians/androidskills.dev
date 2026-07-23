//! Canonical UTC timestamps for lexically sortable D1 TEXT columns.

use chrono::{DateTime, SecondsFormat, Utc};

pub type Timestamp = String;

/// Returns a fixed-width UTC ISO-8601 value. Fixed nanosecond precision keeps D1 TEXT timestamps
/// lexically sortable for expiry, scheduling, and index ordering.
pub fn now_iso() -> Timestamp {
    format_iso(Utc::now())
}

pub fn format_iso(value: DateTime<Utc>) -> Timestamp {
    value.to_rfc3339_opts(SecondsFormat::Nanos, true)
}

/// Parses only canonical UTC timestamps accepted by the D1 persistence layer.
pub fn parse_iso(value: &str) -> Option<DateTime<Utc>> {
    DateTime::parse_from_rfc3339(value)
        .ok()
        .map(|timestamp| timestamp.with_timezone(&Utc))
        .filter(|timestamp| format_iso(*timestamp) == value)
}

#[cfg(test)]
mod tests {
    use super::{format_iso, now_iso, parse_iso};
    use chrono::{TimeZone, Timelike, Utc};

    #[test]
    fn formats_canonical_utc_precision() {
        let timestamp = Utc.with_ymd_and_hms(2026, 7, 23, 12, 34, 56).unwrap();
        assert_eq!(format_iso(timestamp), "2026-07-23T12:34:56.000000000Z");
        assert!(parse_iso("2026-07-23T12:34:56.000000000Z").is_some());
        assert!(parse_iso("2026-07-23T12:34:56.123000000Z").is_some());
        assert!(parse_iso("2026-07-23T12:34:56.123456789Z").is_some());
    }

    #[test]
    fn rejects_noncanonical_timestamp_spellings() {
        assert!(parse_iso("2026-07-23T12:34:56Z").is_none());
        assert!(parse_iso("2026-07-23T12:34:56.000Z").is_none());
        assert!(parse_iso("2026-07-23T14:34:56+02:00").is_none());
        assert!(parse_iso("not-a-timestamp").is_none());
        assert!(parse_iso(&now_iso()).is_some());
    }

    #[test]
    fn fixed_width_timestamps_sort_in_chronological_order() {
        let exact_second = Utc.with_ymd_and_hms(2026, 7, 23, 12, 34, 56).unwrap();
        let next_nanosecond = exact_second.with_nanosecond(1).unwrap();
        assert!(format_iso(exact_second) < format_iso(next_nanosecond));
    }
}
