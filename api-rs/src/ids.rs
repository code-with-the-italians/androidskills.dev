//! UUIDv4 identifiers for the D1 TEXT primary-key contract.

use uuid::Uuid;

/// Creates a canonical, hyphenated UUIDv4 string suitable for every 36-character D1 ID column.
pub fn new_id() -> String {
    Uuid::new_v4().hyphenated().to_string()
}

/// Accepts only canonical UUIDv4 strings, preventing non-canonical identities from entering D1.
pub fn is_canonical_v4(value: &str) -> bool {
    Uuid::parse_str(value)
        .is_ok_and(|uuid| uuid.get_version_num() == 4 && uuid.hyphenated().to_string() == value)
}

#[cfg(test)]
mod tests {
    use super::{is_canonical_v4, new_id};

    #[test]
    fn generates_canonical_v4_ids() {
        let id = new_id();
        assert_eq!(id.len(), 36);
        assert!(is_canonical_v4(&id));
    }

    #[test]
    fn rejects_other_uuid_versions_and_spellings() {
        assert!(!is_canonical_v4("6ba7b810-9dad-11d1-80b4-00c04fd430c8"));
        assert!(!is_canonical_v4("550E8400-E29B-41D4-A716-446655440000"));
        assert!(!is_canonical_v4("not-a-uuid"));
    }
}
