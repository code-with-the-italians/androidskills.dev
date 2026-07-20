//! D1 persistence rows and enums. Public OpenAPI DTOs remain a separate camelCase layer.

use serde::{Deserialize, Serialize};

pub type Id = String;
pub type Timestamp = String;

#[derive(Debug, Clone, Copy, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum UserRole {
    Member,
    Contributor,
    Admin,
}
#[derive(Debug, Clone, Copy, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum UserStatus {
    Active,
    Suspended,
}
#[derive(Debug, Clone, Copy, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum BundleKind {
    Repo,
    Zip,
}
#[derive(Debug, Clone, Copy, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum VersionSource {
    Manifest,
    GitHead,
    Upload,
}
#[derive(Debug, Clone, Copy, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum SkillStatus {
    Published,
    Unlisted,
    Flagged,
}
#[derive(Debug, Clone, Copy, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum SubmissionState {
    Draft,
    InReview,
    ChangesRequested,
    Published,
    Rejected,
}
#[derive(Debug, Clone, Copy, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum JobState {
    Queued,
    Running,
    Done,
    Failed,
}
#[derive(Debug, Clone, Copy, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum JobType {
    Review,
    Resync,
}
#[derive(Debug, Clone, Copy, Deserialize, Serialize, PartialEq, Eq)]
pub enum TokenBand {
    #[serde(rename = "100s")]
    Hundreds,
    #[serde(rename = "1k")]
    Thousands,
    #[serde(rename = "10k")]
    TenThousands,
    #[serde(rename = "100k")]
    HundredThousands,
}

#[derive(Debug, Clone, Deserialize)]
pub struct User {
    pub id: Id,
    pub github_id: i64,
    pub handle: String,
    pub name: Option<String>,
    pub avatar_url: Option<String>,
    pub role: UserRole,
    pub status: UserStatus,
    pub settings_json: Option<String>,
    pub deleted_at: Option<Timestamp>,
    pub created_at: Timestamp,
    pub updated_at: Timestamp,
}
#[derive(Debug, Clone, Deserialize)]
pub struct Category {
    pub id: Id,
    pub slug: String,
    pub name: String,
}
#[derive(Debug, Clone, Deserialize)]
pub struct Bundle {
    pub id: Id,
    pub kind: BundleKind,
    pub provenance: String,
    pub owner_user_id: Id,
    pub source_ref: Option<String>,
    pub installation_id: Option<i64>,
    pub synced_at: Option<Timestamp>,
    pub created_at: Timestamp,
}
#[derive(Debug, Clone, Deserialize)]
pub struct Skill {
    pub id: Id,
    pub bundle_id: Id,
    pub slug: String,
    pub source_dir: String,
    pub name: String,
    pub description: String,
    pub license: Option<String>,
    pub tags: String,
    pub category_id: Option<Id>,
    pub version: String,
    pub version_source: VersionSource,
    pub token_upfront: i64,
    pub token_ondemand: i64,
    pub token_band: TokenBand,
    #[serde(deserialize_with = "d1_bool::deserialize")]
    pub verified: bool,
    pub status: SkillStatus,
    #[serde(deserialize_with = "d1_bool::deserialize")]
    pub featured: bool,
    pub installs: i64,
    pub readme_md: Option<String>,
    pub security: Option<String>,
    pub created_at: Timestamp,
    pub updated_at: Timestamp,
}
#[derive(Debug, Clone, Deserialize)]
pub struct SkillFile {
    pub id: Id,
    pub skill_id: Id,
    pub path: String,
    pub size: i64,
    #[serde(deserialize_with = "d1_bool::deserialize")]
    pub is_binary: bool,
    pub r2_key: String,
}
#[derive(Debug, Clone, Deserialize)]
pub struct Version {
    pub id: Id,
    pub skill_id: Id,
    pub version: String,
    pub source_ref: String,
    pub r2_zip_key: Option<String>,
    pub created_at: Timestamp,
}
#[derive(Debug, Clone, Deserialize)]
pub struct Submission {
    pub id: Id,
    pub bundle_id: Option<Id>,
    pub skill_id: Option<Id>,
    pub submitter_id: Id,
    pub state: SubmissionState,
    pub lint_score: Option<i64>,
    pub note: Option<String>,
    pub payload: Option<String>,
    pub revision: i64,
    pub created_at: Timestamp,
    pub updated_at: Timestamp,
}
#[derive(Debug, Clone, Deserialize)]
pub struct Star {
    pub user_id: Id,
    pub skill_id: Id,
    pub created_at: Timestamp,
}
#[derive(Debug, Clone, Deserialize)]
pub struct Session {
    pub id: String,
    pub user_id: Id,
    pub created_at: Timestamp,
    pub expires_at: Timestamp,
}
#[derive(Debug, Clone, Deserialize)]
pub struct Job {
    pub id: Id,
    #[serde(rename = "type")]
    pub job_type: JobType,
    pub payload: String,
    pub state: JobState,
    pub attempts: i64,
    pub run_after: Timestamp,
    pub last_error: Option<String>,
    pub dedup_key: String,
    pub dispatched_at: Option<Timestamp>,
    pub lease_until: Option<Timestamp>,
    pub created_at: Timestamp,
    pub updated_at: Timestamp,
}
#[derive(Debug, Clone, Deserialize)]
pub struct AuditEvent {
    pub id: Id,
    pub actor_id: Id,
    pub action: String,
    pub target: String,
    pub meta: Option<String>,
    pub created_at: Timestamp,
}
#[derive(Debug, Clone, Deserialize)]
pub struct PlatformSetting {
    pub key: String,
    pub value: String,
}
#[derive(Debug, Clone, Deserialize)]
pub struct Report {
    pub id: Id,
    pub skill_id: Id,
    pub reporter_id: Option<Id>,
    pub reason: String,
    pub created_at: Timestamp,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PageRequest {
    pub page: u32,
    pub page_size: u32,
}
impl PageRequest {
    pub const DEFAULT_PAGE_SIZE: u32 = 24;
    pub const MAX_PAGE_SIZE: u32 = 60;
    pub const MAX_PAGE: u32 = 10_000;

    pub fn validate(&self) -> Result<(), PageError> {
        if self.page == 0 || self.page > Self::MAX_PAGE {
            return Err(PageError::Page);
        }
        if !(1..=Self::MAX_PAGE_SIZE).contains(&self.page_size) {
            return Err(PageError::PageSize);
        }
        Ok(())
    }
    pub fn offset(&self) -> Result<u32, PageError> {
        self.validate()?;
        Ok((self.page - 1) * self.page_size)
    }
}
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PageError {
    Page,
    PageSize,
}

mod d1_bool {
    use serde::{Deserialize, Deserializer};
    pub fn deserialize<'de, D: Deserializer<'de>>(deserializer: D) -> Result<bool, D::Error> {
        match i32::deserialize(deserializer)? {
            0 => Ok(false),
            1 => Ok(true),
            _ => Err(serde::de::Error::custom("expected D1 boolean 0 or 1")),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::{
        d1_bool, JobType, PageError, PageRequest, SubmissionState, TokenBand, VersionSource,
    };
    use serde::Deserialize;

    #[test]
    fn pagination_preserves_the_strict_http_contract() {
        assert_eq!(
            PageRequest {
                page: 1,
                page_size: 24
            }
            .offset(),
            Ok(0)
        );
        assert_eq!(
            PageRequest {
                page: 0,
                page_size: 24
            }
            .offset(),
            Err(PageError::Page)
        );
        assert_eq!(
            PageRequest {
                page: 10_001,
                page_size: 24
            }
            .offset(),
            Err(PageError::Page)
        );
        assert_eq!(
            PageRequest {
                page: 1,
                page_size: 61
            }
            .offset(),
            Err(PageError::PageSize)
        );
    }

    #[test]
    fn persisted_enums_preserve_d1_spellings() {
        assert_eq!(
            serde_json::from_str::<JobType>("\"review\"").unwrap(),
            JobType::Review
        );
        assert_eq!(
            serde_json::from_str::<VersionSource>("\"git_head\"").unwrap(),
            VersionSource::GitHead
        );
        assert_eq!(
            serde_json::from_str::<SubmissionState>("\"in_review\"").unwrap(),
            SubmissionState::InReview
        );
        assert_eq!(
            serde_json::from_str::<TokenBand>("\"100s\"").unwrap(),
            TokenBand::Hundreds
        );
    }

    #[derive(Deserialize)]
    struct D1Boolean {
        #[serde(deserialize_with = "d1_bool::deserialize")]
        value: bool,
    }

    #[test]
    fn d1_booleans_accept_only_zero_or_one() {
        assert!(
            serde_json::from_str::<D1Boolean>("{\"value\":1}")
                .unwrap()
                .value
        );
        assert!(
            !serde_json::from_str::<D1Boolean>("{\"value\":0}")
                .unwrap()
                .value
        );
        assert!(serde_json::from_str::<D1Boolean>("{\"value\":2}").is_err());
        assert!(serde_json::from_str::<D1Boolean>("{\"value\":-1}").is_err());
    }
}
