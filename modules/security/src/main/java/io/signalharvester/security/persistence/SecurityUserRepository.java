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

    boolean anyEnabledAdminExists();

    void insert(UserAccount account, String passwordHash);

    /**
     * Executes the lock-sensitive administrative update sequence on one transaction-bound persistence handle.
     */
    <T> T withAdministratorStateLock(AdministratorStateOperation<T> operation);

    /** Persistence operations that must share the administrator-state lock and the same database handle. */
    interface AdministratorState {
        Optional<UserAccount> findById(UserId userId);

        boolean anyOtherEnabledAdminExists(UserId excludedUserId);

        boolean update(UserAccount account);
    }

    /** Callback executed while the administrator-state lock is held. */
    @FunctionalInterface
    interface AdministratorStateOperation<T> {
        T apply(AdministratorState state);
    }
}
