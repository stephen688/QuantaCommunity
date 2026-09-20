package com.quanta.demo0.security;

import java.util.Set;

/**
 * Immutable security data that can be reused between authentication requests.
 *
 * <p>The JWT, current Redis session and ban marker are deliberately not part
 * of this value. They remain request-time checks in
 * {@link TokenAuthenticationServiceImpl}.</p>
 */
public record AuthenticationSnapshot(
        Integer accountStatus,
        boolean verified,
        Set<String> roles,
        Set<String> authorities,
        boolean admin
) {

    public AuthenticationSnapshot {
        roles = roles == null ? Set.of() : Set.copyOf(roles);
        authorities = authorities == null
                ? Set.of()
                : Set.copyOf(authorities);
    }
}
