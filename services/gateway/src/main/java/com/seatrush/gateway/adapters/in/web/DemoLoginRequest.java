package com.seatrush.gateway.adapters.in.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * A demo login as the API takes it (README section 12.1).
 *
 * @param username who to be for the next hour, which is also what the UI displays
 * @param admin whether the token should carry the role the admin endpoints demand. It is asked for
 *     rather than decided, because this endpoint exists in the local and bench profiles only: a
 *     demo needs an operator who can create an event and a buyer who cannot, and the quickest way
 *     to have both is to let the caller say which one they are
 */
public record DemoLoginRequest(@NotBlank @Size(max = 60) String username, boolean admin) {}
