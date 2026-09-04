use std::any::Any;
use std::fmt;

/// Stable error codes crossing the Kotlin/Rust boundary.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum AndroidErrorCode {
    InvalidArgument,
    InvalidHandle,
    WrongHandleType,
    Cancelled,
    RuntimeUnavailable,
    Internal,
}

impl AndroidErrorCode {
    pub(crate) const fn as_str(self) -> &'static str {
        match self {
            Self::InvalidArgument => "invalid_argument",
            Self::InvalidHandle => "invalid_handle",
            Self::WrongHandleType => "wrong_handle_type",
            Self::Cancelled => "cancelled",
            Self::RuntimeUnavailable => "runtime_unavailable",
            Self::Internal => "internal",
        }
    }
}

/// Sanitized error DTO. `user_message` must never contain bridge inputs or
/// wallet secrets; detailed causes remain on the Rust side.
#[derive(Clone, Debug, Eq, PartialEq)]
pub(crate) struct AndroidError {
    pub(crate) code: AndroidErrorCode,
    pub(crate) user_message: &'static str,
    pub(crate) retryable: bool,
}

impl AndroidError {
    pub(crate) const fn new(
        code: AndroidErrorCode,
        user_message: &'static str,
        retryable: bool,
    ) -> Self {
        Self {
            code,
            user_message,
            retryable,
        }
    }

    pub(crate) const fn invalid_handle() -> Self {
        Self::new(
            AndroidErrorCode::InvalidHandle,
            "The native object is no longer available.",
            false,
        )
    }

    pub(crate) const fn wrong_handle_type() -> Self {
        Self::new(
            AndroidErrorCode::WrongHandleType,
            "The native object has an unexpected type.",
            false,
        )
    }

    pub(crate) const fn internal() -> Self {
        Self::new(
            AndroidErrorCode::Internal,
            "The wallet could not complete the operation.",
            false,
        )
    }

    /// Convert a caught panic without formatting its payload. Panic strings can
    /// accidentally contain sensitive inputs and must not cross JNI.
    pub(crate) fn from_panic(_: Box<dyn Any + Send>) -> Self {
        Self::internal()
    }
}

impl fmt::Display for AndroidError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter.write_str(self.user_message)
    }
}

impl std::error::Error for AndroidError {}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn panic_payload_is_not_exposed() {
        let secret = "twelve secret seed words";
        let panic =
            std::panic::catch_unwind(|| panic!("{secret}")).expect_err("the fixture must panic");
        let error = AndroidError::from_panic(panic);

        assert_eq!(error.code, AndroidErrorCode::Internal);
        assert!(!error.to_string().contains(secret));
        assert!(!error.retryable);
    }

    #[test]
    fn codes_are_stable_snake_case_values() {
        assert_eq!(
            AndroidErrorCode::InvalidArgument.as_str(),
            "invalid_argument"
        );
        assert_eq!(AndroidErrorCode::InvalidHandle.as_str(), "invalid_handle");
        assert_eq!(
            AndroidErrorCode::WrongHandleType.as_str(),
            "wrong_handle_type"
        );
        assert_eq!(
            AndroidErrorCode::RuntimeUnavailable.as_str(),
            "runtime_unavailable"
        );
        assert_eq!(AndroidErrorCode::Cancelled.as_str(), "cancelled");
        assert_eq!(AndroidErrorCode::Internal.as_str(), "internal");
    }
}
