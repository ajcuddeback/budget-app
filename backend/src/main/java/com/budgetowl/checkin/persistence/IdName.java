package com.budgetowl.checkin.persistence;

import java.util.UUID;

/** An id and the name shown beside it. */
public record IdName(UUID id, String name) {

    @Override
    public String toString() {
        return "IdName[id=" + id + "]";
    }
}
