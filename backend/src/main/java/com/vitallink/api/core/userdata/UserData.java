package com.vitallink.api.core.userdata;

import java.util.Map;
import java.util.UUID;

/**
 * The hook every module implements for the account's data rights (docs/05-api.md, Account):
 *
 * <ul>
 *   <li>{@code GET /me/export} runs each module's export queries and returns every row the user owns.
 *   <li>{@code DELETE /me} calls {@link #beforeDelete} on each module (e.g. to remove stored files), then
 *       deletes the user, which cascades to every user-owned table.
 * </ul>
 *
 * A guardrail test checks that every table with a {@code user_id} column is exported by some module.
 */
public interface UserData {

    /**
     * Section name → a SELECT whose rows are exported as JSON objects. {@code :u} is the user's id. Order the
     * rows so the export is stable.
     */
    Map<String, String> exportQueries();

    /** Runs inside the delete transaction, before the user's rows are removed. */
    default void beforeDelete(UUID userId) {}
}
