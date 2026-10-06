"""decide(): every rule in docs/06-security-and-consent.md §6.1, without a database."""

import uuid
from datetime import UTC, datetime, timedelta

import pytest

from app.core.audit import RefusalReason, Scope
from app.core.consent import Grant, Viewer, decide

NOW = datetime(2026, 9, 13, 15, 0, tzinfo=UTC)
OWNER = uuid.uuid4()
VIEWER = Viewer(uuid.uuid4(), "dr.bennett@example.com", email_confirmed=True)


def grant(**kw) -> Grant:
    base = dict(
        id=uuid.uuid4(),
        grantee_user_id=None,
        grantee_email=VIEWER.email,
        scopes=frozenset({Scope.vitals}),
        starts_at=NOW - timedelta(days=1),
        expires_at=None,
        revoked_at=None,
        created_at=NOW - timedelta(days=1),
    )
    return Grant(**(base | kw))


def test_1_owner_reads_own_data():
    me = Viewer(OWNER, "me@example.com", email_confirmed=False)
    assert decide(me, OWNER, Scope.records, [], NOW).allowed


def test_2_unconfirmed_email_matches_nothing():
    viewer = Viewer(VIEWER.user_id, VIEWER.email, email_confirmed=False)
    d = decide(viewer, OWNER, Scope.vitals, [grant()], NOW)
    assert (d.allowed, d.reason) == (False, RefusalReason.email_unconfirmed)


def test_3_no_grant():
    assert decide(VIEWER, OWNER, Scope.vitals, [], NOW).reason is RefusalReason.no_grant


def test_3_grant_to_someone_else_does_not_match():
    other = grant(grantee_email="someone@example.com")
    assert decide(VIEWER, OWNER, Scope.vitals, [other], NOW).reason is RefusalReason.no_grant


def test_3_bound_grant_never_matches_by_email_again():
    bound_elsewhere = grant(grantee_user_id=uuid.uuid4())  # same email, bound to another account
    assert decide(VIEWER, OWNER, Scope.vitals, [bound_elsewhere], NOW).reason is RefusalReason.no_grant


def test_3_bound_grant_follows_the_account_not_the_email():
    bound = grant(grantee_user_id=VIEWER.user_id, grantee_email="old.address@example.com")
    assert decide(VIEWER, OWNER, Scope.vitals, [bound], NOW).allowed


def test_4_scope_not_granted():
    d = decide(VIEWER, OWNER, Scope.records, [grant(scopes=frozenset({Scope.vitals}))], NOW)
    assert d.reason is RefusalReason.scope_not_granted


def test_5_revoked():
    assert decide(VIEWER, OWNER, Scope.vitals, [grant(revoked_at=NOW)], NOW).reason is RefusalReason.revoked


def test_6_not_yet_valid():
    d = decide(VIEWER, OWNER, Scope.vitals, [grant(starts_at=NOW + timedelta(seconds=1))], NOW)
    assert d.reason is RefusalReason.not_yet_valid


@pytest.mark.parametrize("expires", [NOW, NOW - timedelta(seconds=1)], ids=["at-expiry", "after"])
def test_7_expired_and_expiry_is_exclusive(expires):
    assert (
        decide(VIEWER, OWNER, Scope.vitals, [grant(expires_at=expires)], NOW).reason is RefusalReason.expired
    )


def test_8_live_grant_allows_and_asks_for_binding():
    g = grant(expires_at=NOW + timedelta(seconds=1))
    d = decide(VIEWER, OWNER, Scope.vitals, [g], NOW)
    assert d.allowed and d.grant == g and d.needs_binding


def test_8_already_bound_grant_needs_no_binding():
    d = decide(VIEWER, OWNER, Scope.vitals, [grant(grantee_user_id=VIEWER.user_id)], NOW)
    assert d.allowed and not d.needs_binding


def test_any_live_grant_wins_over_dead_ones():
    dead = grant(revoked_at=NOW, created_at=NOW)
    live = grant(created_at=NOW - timedelta(days=5))
    assert decide(VIEWER, OWNER, Scope.vitals, [dead, live], NOW).allowed


def test_vitals_never_opens_records():
    every_other = frozenset(set(Scope) - {Scope.records})
    assert not decide(VIEWER, OWNER, Scope.records, [grant(scopes=every_other)], NOW).allowed
