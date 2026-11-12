package com.vitallink.api.health;

import com.vitallink.api.account.CurrentUser;

/**
 * The account (and the phone link) behind a linked phone's upload token ({@code Authorization: Phone <token>}), resolved by WebConfig.
 * Only the {@code /v1/phone} routes take it, so the token can't read anything.
 */
public record PhoneCaller(CurrentUser me, java.util.UUID linkId) {}
