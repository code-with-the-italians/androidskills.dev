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

/// Parses UTC RFC3339 timestamps from both existing Kotlin fixtures and new fixed-width D1 rows.
/// Persistence writers must still use [format_iso] so new D1 TEXT values remain sortable.
pub fn parse_iso(value: &str) -> Option<DateTime<Utc>> {
    if value.ends_with("-00:00") {
        return None;
    }
    DateTime::parse_from_rfc3339(value)
        .ok()
        .filter(|timestamp| timestamp.offset().local_minus_utc() == 0)
        .map(|timestamp| timestamp.with_timezone(&Utc))
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
    fn accepts_existing_kotlin_utc_spellings_and_rejects_non_utc_values() {
        assert!(parse_iso("2026-07-23T12:34:56Z").is_some());
        assert!(parse_iso("2026-07-23T12:34:56.000Z").is_some());
        assert!(parse_iso("2026-07-23T14:34:56+02:00").is_none());
        assert!(parse_iso("2026-07-23T12:34:56-00:00").is_none());
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
