package io.signalharvester.security.application;

import io.signalharvester.security.model.UserAccount;
import io.signalharvester.security.model.UserId;
import java.util.List;

/** Application boundary for administrative user identity management. */
public interface UserAccountOperations {
    UserAccount create(CreateUserCommand command);

    List<UserAccount> list();

    UserAccount get(UserId userId);

    UserAccount update(UserId userId, UpdateUserCommand command);

    boolean anyAdminExists();
}
