//! Typed encoding for JSON stored in D1 TEXT columns.

use serde::{de::DeserializeOwned, Serialize};

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum JsonColumnError {
    Encode(String),
    Decode(String),
}

impl core::fmt::Display for JsonColumnError {
    fn fmt(&self, formatter: &mut core::fmt::Formatter<'_>) -> core::fmt::Result {
        match self {
            Self::Encode(error) => write!(formatter, "could not encode JSON column: {error}"),
            Self::Decode(error) => write!(formatter, "could not decode JSON column: {error}"),
        }
    }
}

pub fn encode<T: Serialize>(value: &T) -> Result<String, JsonColumnError> {
    let mut value =
        serde_json::to_value(value).map_err(|error| JsonColumnError::Encode(error.to_string()))?;
    omit_object_nulls(&mut value);
    serde_json::to_string(&value).map_err(|error| JsonColumnError::Encode(error.to_string()))
}

pub fn decode<T: DeserializeOwned>(value: &str) -> Result<T, JsonColumnError> {
    serde_json::from_str(value).map_err(|error| JsonColumnError::Decode(error.to_string()))
}

fn omit_object_nulls(value: &mut serde_json::Value) {
    match value {
        serde_json::Value::Object(object) => {
            object.retain(|_, value| !value.is_null());
            for value in object.values_mut() {
                omit_object_nulls(value);
            }
        }
        serde_json::Value::Array(values) => {
            for value in values {
                omit_object_nulls(value);
            }
        }
        _ => {}
    }
}

#[cfg(test)]
mod tests {
    use super::{decode, encode, JsonColumnError};
    use serde::Serialize;

    #[test]
    fn round_trips_json_columns() {
        let encoded = encode(&vec!["android", "kotlin"]).unwrap();
        assert_eq!(encoded, r#"["android","kotlin"]"#);
        assert_eq!(
            decode::<Vec<String>>(&encoded).unwrap(),
            ["android", "kotlin"]
        );
    }

    #[test]
    fn surfaces_corrupt_json() {
        assert!(matches!(
            decode::<Vec<String>>("{"),
            Err(JsonColumnError::Decode(_))
        ));
    }

    #[derive(Serialize)]
    struct KotlinCompatiblePayload {
        present: Option<&'static str>,
        absent: Option<&'static str>,
        nested: NestedPayload,
        array: Vec<Option<&'static str>>,
    }

    #[derive(Serialize)]
    struct NestedPayload {
        absent: Option<&'static str>,
    }

    #[test]
    fn omits_object_nulls_like_kotlins_shared_json_encoder() {
        let encoded = encode(&KotlinCompatiblePayload {
            present: Some("value"),
            absent: None,
            nested: NestedPayload { absent: None },
            array: vec![Some("value"), None],
        })
        .unwrap();
        assert_eq!(
            encoded,
            r#"{"array":["value",null],"nested":{},"present":"value"}"#
        );
    }
}
