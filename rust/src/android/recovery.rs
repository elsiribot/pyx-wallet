use serde::Serialize;

use crate::client::{ConduitClient, RecoveryExpirySnapshot};

use super::error::AndroidError;
use super::handles::{HandleKind, global_get};

const MAX_SUCCESSOR_INVITE_BYTES: usize = 64 * 1024;

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct RecoveryExpiryDto {
    has_pending_recoveries: bool,
    expires_at_epoch_seconds: Option<i64>,
    successor_invite_present: bool,
    successor_invite: Option<String>,
}

pub(crate) async fn snapshot(client_handle: u64) -> Result<String, AndroidError> {
    let client = global_get::<ConduitClient>(client_handle, HandleKind::Client)?;
    snapshot_json(client.recovery_expiry_snapshot().await)
}

fn snapshot_json(snapshot: RecoveryExpirySnapshot) -> Result<String, AndroidError> {
    let successor_invite = snapshot
        .successor_invite
        .filter(|invite| !invite.is_empty() && invite.len() <= MAX_SUCCESSOR_INVITE_BYTES);
    let successor_invite_present = successor_invite.is_some();
    serde_json::to_string(&RecoveryExpiryDto {
        has_pending_recoveries: snapshot.has_pending_recoveries,
        expires_at_epoch_seconds: snapshot
            .expires_at_epoch_seconds
            .filter(|value| *value >= 0),
        successor_invite_present,
        successor_invite,
    })
    .map_err(|_| AndroidError::internal())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn snapshot_shape_exposes_bounded_successor_only_for_confirmation() {
        let json = snapshot_json(RecoveryExpirySnapshot {
            has_pending_recoveries: true,
            expires_at_epoch_seconds: Some(1_800_000_000),
            successor_invite: Some("fedimint:successor".into()),
        })
        .unwrap();
        crate::android::assert_read_dto_snapshot("recovery.expiry", &json);
        assert_eq!(
            json,
            r#"{"hasPendingRecoveries":true,"expiresAtEpochSeconds":1800000000,"successorInvitePresent":true,"successorInvite":"fedimint:successor"}"#
        );

        let bounded = snapshot_json(RecoveryExpirySnapshot {
            has_pending_recoveries: false,
            expires_at_epoch_seconds: Some(-1),
            successor_invite: Some("x".repeat(MAX_SUCCESSOR_INVITE_BYTES + 1)),
        })
        .unwrap();
        assert_eq!(
            bounded,
            r#"{"hasPendingRecoveries":false,"expiresAtEpochSeconds":null,"successorInvitePresent":false,"successorInvite":null}"#
        );
    }
}
