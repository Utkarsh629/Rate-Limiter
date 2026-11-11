package com.utkarsh.ratelimiter.policy;

import java.sql.SQLException;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads MySQL vendor error codes from an exception's cause chain.
 *
 * <p>Needed because Spring translates MySQL CHECK violations (error 3819, SQLState HY000) to
 * {@code UncategorizedSQLException} rather than {@code DataIntegrityViolationException}, and
 * because a duplicate key (1062) must be told apart from other integrity errors.
 */
public final class MySqlErrors {

    public static final int DUPLICATE_ENTRY = 1062;
    public static final int CHECK_CONSTRAINT_VIOLATED = 3819;

    private static final Pattern CHECK_NAME = Pattern.compile("Check constraint '([^']+)' is violated");

    private MySqlErrors() {
    }

    public static Optional<SQLException> findSqlException(Throwable error, int vendorCode) {
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof SQLException sql) {
                for (SQLException s = sql; s != null; s = s.getNextException()) {
                    if (s.getErrorCode() == vendorCode) {
                        return Optional.of(s);
                    }
                }
            }
        }
        return Optional.empty();
    }

    /** The violated CHECK constraint's name, e.g. {@code chk_policy_limit}. */
    public static Optional<String> checkConstraintName(Throwable error) {
        return findSqlException(error, CHECK_CONSTRAINT_VIOLATED).map(SQLException::getMessage).map(message -> {
            Matcher matcher = CHECK_NAME.matcher(message);
            return matcher.find() ? matcher.group(1) : "unknown";
        });
    }
}
