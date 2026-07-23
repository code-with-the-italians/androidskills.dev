//! Stable HTTP error envelope shared by every Worker route.

use std::collections::BTreeMap;

use serde::Serialize;
use worker::{Response, Result};

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
pub struct ErrorBody {
    pub code: String,
    pub message: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub fields: Option<BTreeMap<String, String>>,
}

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
pub struct ErrorResponse {
    pub error: ErrorBody,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ApiError {
    status: u16,
    body: ErrorBody,
}

impl ApiError {
    pub fn new(status: u16, code: impl Into<String>, message: impl Into<String>) -> Self {
        assert!(
            (400..=599).contains(&status),
            "API errors must use an HTTP error status"
        );
        Self {
            status,
            body: ErrorBody {
                code: code.into(),
                message: message.into(),
                fields: None,
            },
        }
    }

    pub fn validation(
        code: impl Into<String>,
        message: impl Into<String>,
        fields: BTreeMap<String, String>,
    ) -> Self {
        Self {
            status: 422,
            body: ErrorBody {
                code: code.into(),
                message: message.into(),
                fields: Some(fields),
            },
        }
    }

    pub fn not_found() -> Self {
        Self::new(404, "not_found", "Not found")
    }
    pub fn unauthorized() -> Self {
        Self::new(401, "unauthorized", "Authentication required")
    }
    pub fn forbidden() -> Self {
        Self::new(403, "forbidden", "Forbidden")
    }
    pub fn bad_request(message: impl Into<String>) -> Self {
        Self::new(400, "bad_request", message)
    }
    pub fn conflict(code: impl Into<String>, message: impl Into<String>) -> Self {
        Self::new(409, code, message)
    }
    pub fn internal() -> Self {
        Self::new(500, "internal", "Internal server error")
    }

    pub fn status(&self) -> u16 {
        self.status
    }
    pub fn response(&self) -> Result<Response> {
        Response::from_json(&ErrorResponse {
            error: self.body.clone(),
        })
        .map(|response| response.with_status(self.status))
    }
}

#[cfg(test)]
mod tests {
    use super::ApiError;
    use std::collections::BTreeMap;

    #[test]
    fn preserves_the_openapi_error_envelope() {
        let error = ApiError::validation(
            "validation_failed",
            "Validation failed",
            BTreeMap::from([("slug".into(), "required".into())]),
        );
        assert_eq!(error.status(), 422);
        let encoded = serde_json::to_value(error.body).unwrap();
        assert_eq!(encoded["code"], "validation_failed");
        assert_eq!(encoded["fields"]["slug"], "required");
    }

    #[test]
    fn standard_errors_have_stable_codes() {
        assert_eq!(ApiError::not_found().status(), 404);
        assert_eq!(ApiError::unauthorized().status(), 401);
        assert_eq!(ApiError::forbidden().status(), 403);
        assert_eq!(ApiError::internal().status(), 500);
    }
}
