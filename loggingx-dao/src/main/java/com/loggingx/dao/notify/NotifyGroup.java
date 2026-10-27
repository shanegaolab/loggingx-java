package com.loggingx.dao.notify;

import com.loggingx.dao.support.Timestamps;

import java.time.Instant;

/**
 * A group of alert recipients.
 *
 * @param userIds  JSON array of system user ids
 * @param contacts JSON array of other contacts: name, email and phone
 */
public record NotifyGroup(Long id, String name, String description, String userIds, String contacts,
                          Instant createdAt, Instant updatedAt) {

    public NotifyGroup {
        createdAt = Timestamps.storable(createdAt);
        updatedAt = Timestamps.storable(updatedAt);
    }

    public NotifyGroup withId(Long newId) {
        return new NotifyGroup(newId, name, description, userIds, contacts, createdAt, updatedAt);
    }
}
