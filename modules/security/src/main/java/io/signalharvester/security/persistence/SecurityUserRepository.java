package io.signalharvester.security.persistence;

import io.signalharvester.security.model.UserAccount;
import io.signalharvester.security.model.UserId;
import java.util.List;
import java.util.Optional;

/** Persistence contract for security-owned user identities and role assignments. */
public interface SecurityUserRepository {
    List<UserAccount> findAll();

    Optional<UserAccount> findById(UserId userId);

    Optional<UserAccount> findByUsername(String username);

    Optional<StoredCredentials> findCredentialsByUsername(String username);

    boolean anyAdminExists();

    void insert(UserAccount account, String passwordHash);

    boolean update(UserAccount account);
}
