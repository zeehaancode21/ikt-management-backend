package com.example.backend.security;

import com.example.backend.entity.User;
import com.example.backend.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads the user for JwtFilter, which runs on EVERY authenticated request.
 *
 * Previously that meant one "SELECT ... FROM users WHERE username=?" per API
 * call. The result is now kept in memory for a short time, so a burst of
 * calls from the same logged-in user costs one DB hit instead of one each.
 *
 * Safety: the entry is dropped immediately (evict) whenever a user's role is
 * changed or the user is deleted, and it expires on its own after
 * CACHE_TTL_MS anyway. Only the account's username/role/password-hash are
 * held (a few hundred bytes per user) and the map is size-capped.
 */
@Service
public class CustomUserDetailsService implements UserDetailsService {

    private static final long CACHE_TTL_MS = 60_000;
    private static final int CACHE_MAX_ENTRIES = 500;

    private record Cached(UserDetails details, long expiresAt) {}

    private final ConcurrentHashMap<String, Cached> cache = new ConcurrentHashMap<>();

    @Autowired
    private UserRepository userRepository;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        long now = System.currentTimeMillis();
        Cached hit = cache.get(username);
        if (hit != null && hit.expiresAt() > now) {
            return hit.details();
        }

        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));

        UserDetails details = new org.springframework.security.core.userdetails.User(
                user.getUsername(),
                user.getPassword(),
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_" + user.getRole()))
        );

        if (cache.size() >= CACHE_MAX_ENTRIES) {
            cache.entrySet().removeIf(e -> e.getValue().expiresAt() <= now);
            if (cache.size() >= CACHE_MAX_ENTRIES) {
                cache.clear();
            }
        }
        cache.put(username, new Cached(details, now + CACHE_TTL_MS));
        return details;
    }

    /** Call after a user's role changes or the user is deleted. */
    public void evict(String username) {
        if (username != null) {
            cache.remove(username);
        }
    }
}
