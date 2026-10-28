package com.onepassword.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

/** A single batch outcome. A successful deletion has both content and error null. */
public record Response<T, E>(T content, E error) {
    public Response {
        if (content != null && error != null) {
            throw new IllegalArgumentException("A response cannot have both content and error");
        }
    }

    @JsonIgnore
    public boolean isSuccess() {
        return error == null;
    }

    @Override
    public String toString() {
        return isSuccess() ? "Response[success]" : "Response[failure]";
    }
}
