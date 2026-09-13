package org.program.pair.shared.security;

import lombok.Getter;
import org.program.pair.domain.user.User;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Getter
public class UserPrincipal implements UserDetails {

    private final User user;

    /**
     * La session qui a émis le jeton d'accès de la requête (P-BS-03), ou
     * {@code null} pour un jeton émis avant les sessions persistées. C'est elle
     * qu'un changement de mot de passe épargne.
     */
    private final UUID sessionId;

    public UserPrincipal(User user) {
        this(user, null);
    }

    public UserPrincipal(User user, UUID sessionId) {
        this.user = user;
        this.sessionId = sessionId;
    }

    public UUID getId() {
        return user.getId();
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_USER"));
    }

    @Override
    public String getPassword() {
        return user.getPasswordHash();
    }

    @Override
    public String getUsername() {
        return user.getEmail();
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return Boolean.TRUE.equals(user.getIsActive());
    }
}
