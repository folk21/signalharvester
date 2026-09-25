package io.signalharvester.security.application;

import io.signalharvester.operations.api.OperationalChangeContext;
import io.signalharvester.security.model.UserAccount;
import io.signalharvester.security.model.UserId;
import java.util.List;

/** Application boundary for administrative user identity management. */
public interface UserAccountOperations {
    default UserAccount create(CreateUserCommand command) {
        return create(command, OperationalChangeContext.untrackedSystem());
    }

    UserAccount create(CreateUserCommand command, OperationalChangeContext changeContext);

    List<UserAccount> list();

    UserAccount get(UserId userId);

    default UserAccount update(UserId userId, UpdateUserCommand command) {
        return update(userId, command, OperationalChangeContext.untrackedSystem());
    }

    UserAccount update(UserId userId, UpdateUserCommand command, OperationalChangeContext changeContext);

    boolean anyEnabledAdminExists();
}
