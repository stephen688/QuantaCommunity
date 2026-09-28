package com.quanta.demo0.platform.security.service;

import com.quanta.demo0.platform.security.model.AuthenticationSnapshot;

/**
 * Short-lived local cache for the database-backed portion of authentication.
 */
public interface AuthenticationSnapshotCache {

    /**
     * Returns the current database-backed security snapshot, or {@code null}
     * when the user no longer exists.
     */
    AuthenticationSnapshot get(Long userId, boolean serviceToken);

    /**
     * Removes both the ordinary-token and service-token variants for a user.
     */
    void evict(Long userId);
}
