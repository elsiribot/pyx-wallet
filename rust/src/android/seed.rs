use serde::Serialize;

use fedimint_bip39::Language;

use crate::parse_mnemonic;

use super::error::{AndroidError, AndroidErrorCode};

const MAX_PREFIX_BYTES: usize = 16;
const MAX_SUGGESTIONS: usize = 8;
const MAX_WORDS_JSON_BYTES: usize = 1024;
const MAX_SUBMITTED_WORDS: usize = 24;
const MAX_WORD_BYTES: usize = 16;
const REQUIRED_WORDS: usize = 12;

#[derive(Serialize)]
struct SuggestionsDto {
    suggestions: Vec<&'static str>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct ValidationDto {
    valid: bool,
    word_count: usize,
    invalid_indices: Vec<usize>,
    checksum_valid: bool,
}

pub(crate) fn suggestions(prefix: &str) -> Result<String, AndroidError> {
    if prefix.len() > MAX_PREFIX_BYTES
        || !prefix.is_ascii()
        || !prefix.bytes().all(|byte| byte.is_ascii_lowercase())
    {
        return Err(invalid_prefix());
    }
    let suggestions = Language::English
        .word_list()
        .iter()
        .copied()
        .filter(|word| word.starts_with(prefix))
        .take(MAX_SUGGESTIONS)
        .collect();
    serialize(&SuggestionsDto { suggestions })
}

pub(crate) fn validate(words_json: &str) -> Result<String, AndroidError> {
    if words_json.is_empty() || words_json.len() > MAX_WORDS_JSON_BYTES {
        return Err(invalid_phrase_input());
    }
    let words: Vec<String> =
        serde_json::from_str(words_json).map_err(|_| invalid_phrase_input())?;
    if words.len() > MAX_SUBMITTED_WORDS {
        return Err(invalid_phrase_input());
    }
    let word_list = Language::English.word_list();
    let invalid_indices = words
        .iter()
        .enumerate()
        .filter_map(|(index, word)| {
            let structurally_valid = !word.is_empty()
                && word.len() <= MAX_WORD_BYTES
                && word.bytes().all(|byte| byte.is_ascii_lowercase());
            (!structurally_valid || word_list.binary_search(&word.as_str()).is_err())
                .then_some(index)
        })
        .collect::<Vec<_>>();
    let checksum_valid = words.len() == REQUIRED_WORDS
        && invalid_indices.is_empty()
        && parse_mnemonic(words.clone()).is_some();
    serialize(&ValidationDto {
        valid: checksum_valid,
        word_count: words.len(),
        invalid_indices,
        checksum_valid,
    })
}

fn invalid_prefix() -> AndroidError {
    AndroidError::new(
        AndroidErrorCode::InvalidArgument,
        "The seed word prefix is invalid.",
        false,
    )
}

fn invalid_phrase_input() -> AndroidError {
    AndroidError::new(
        AndroidErrorCode::InvalidArgument,
        "The recovery phrase input is invalid.",
        false,
    )
}

fn serialize<T: Serialize>(value: &T) -> Result<String, AndroidError> {
    serde_json::to_string(value).map_err(|_| AndroidError::internal())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn suggestions_are_lowercase_prefix_matches_and_bounded() {
        let value: serde_json::Value = serde_json::from_str(&suggestions("ab").unwrap()).unwrap();
        let matches = value["suggestions"].as_array().unwrap();
        assert!(!matches.is_empty());
        assert!(matches.len() <= MAX_SUGGESTIONS);
        assert!(
            matches
                .iter()
                .all(|word| word.as_str().unwrap().starts_with("ab"))
        );
        assert!(suggestions("AB").is_err());
        assert!(suggestions("é").is_err());
        assert!(suggestions(&"a".repeat(MAX_PREFIX_BYTES + 1)).is_err());
    }

    #[test]
    fn validation_reports_checksum_and_invalid_positions_without_echo() {
        let valid = r#"["abandon","abandon","abandon","abandon","abandon","abandon","abandon","abandon","abandon","abandon","abandon","about"]"#;
        assert_eq!(
            validate(valid).unwrap(),
            r#"{"valid":true,"wordCount":12,"invalidIndices":[],"checksumValid":true}"#
        );

        let bad_checksum = r#"["abandon","abandon","abandon","abandon","abandon","abandon","abandon","abandon","abandon","abandon","abandon","ability"]"#;
        assert_eq!(
            validate(bad_checksum).unwrap(),
            r#"{"valid":false,"wordCount":12,"invalidIndices":[],"checksumValid":false}"#
        );

        let secret = "notaword";
        let invalid = format!(r#"["abandon","{secret}","UPPER"]"#);
        let json = validate(&invalid).unwrap();
        assert_eq!(
            json,
            r#"{"valid":false,"wordCount":3,"invalidIndices":[1,2],"checksumValid":false}"#
        );
        assert!(!json.contains(secret));
        assert!(!json.contains("UPPER"));
    }

    #[test]
    fn validation_input_is_bounded_and_non_mutating() {
        assert_eq!(
            validate("[]").unwrap(),
            r#"{"valid":false,"wordCount":0,"invalidIndices":[],"checksumValid":false}"#
        );
        assert!(validate("not-json").is_err());
        assert!(validate(&"x".repeat(MAX_WORDS_JSON_BYTES + 1)).is_err());
        let too_many = serde_json::to_string(&vec!["abandon"; MAX_SUBMITTED_WORDS + 1]).unwrap();
        assert!(validate(&too_many).is_err());
    }
}
