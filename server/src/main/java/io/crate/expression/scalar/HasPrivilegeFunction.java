/*
 * Licensed to Crate.io GmbH ("Crate") under one or more contributor
 * license agreements.  See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.  Crate licenses
 * this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial agreement.
 */

package io.crate.expression.scalar;

import java.util.Collection;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.jetbrains.annotations.Nullable;

import io.crate.data.Input;
import io.crate.exceptions.MissingPrivilegeException;
import io.crate.exceptions.RoleUnknownException;
import io.crate.expression.symbol.Symbol;
import io.crate.metadata.NodeContext;
import io.crate.metadata.Scalar;
import io.crate.metadata.Schemas;
import io.crate.metadata.TransactionContext;
import io.crate.metadata.functions.BoundSignature;
import io.crate.metadata.functions.Signature;
import io.crate.role.Permission;
import io.crate.role.Role;
import io.crate.role.Roles;
import io.crate.role.Securable;

public class HasPrivilegeFunction extends Scalar<Boolean, Object> {

    public static Role userByName(Roles roles, Object userName) {
        return roles.getUser((String) userName);
    }

    public static Role userByOid(Roles roles, Object userOid) {
        int oid = (int) userOid;
        var user = roles.findUser(oid);
        if (user == null) {
            throw new RoleUnknownException(oid);
        }
        return user;
    }

    private final ParsePermissions parsePermissions;

    private final BiFunction<Roles, Object, Role> getUser;

    private final CheckPrivilege checkPrivilege;

    public HasPrivilegeFunction(Signature signature,
                                BoundSignature boundSignature,
                                BiFunction<Roles, Object, Role> getUser,
                                CheckPrivilege checkPrivilege,
                                ParsePermissions parsePermissions) {
        super(signature, boundSignature);
        assert signature().hasFeature(Feature.STRICTNULL) : "HasPrivilegeFunctions are nullable";
        this.getUser = getUser;
        this.checkPrivilege = checkPrivilege;
        this.parsePermissions = parsePermissions;
    }

    @Override
    public Symbol normalizeSymbol(io.crate.expression.symbol.Function symbol, TransactionContext txnCtx, NodeContext nodeCtx) {
        return evaluateIfLiterals(this, txnCtx, nodeCtx, symbol);
    }

    @Override
    public Scalar<Boolean, Object> compile(List<Symbol> arguments, String currentUser, Roles roles) {
        // When possible, user is looked up only once.
        // Permission string normalization/mapping into CrateDB Permission is also done once if possible
        Object userValue = null;
        Symbol permissions = null;
        if (arguments.size() == 2) {
            userValue = currentUser;
            permissions = arguments.get(1);
        }
        if (arguments.size() == 3) {
            if (arguments.get(0) instanceof Input<?> input) {
                userValue = input.value();
            }
            permissions = arguments.get(2);
        }

        Collection<Permission> compiledPermissions = normalizePermissionIfLiteral(permissions);
        if (userValue == null) {
            // Fall to non-compiled version which returns null.
            return this;
        }

        // Compiled privileges can be null here but we don't fall to non-compiled version as
        // can mean that privilege string is not null but not Literal either.
        // When we pass NULL to the compiled version, it treats last argument like regular evaluate:
        // does null check and parses privileges string.
        var sessionUser = userByName(roles, currentUser);
        Role user = getUser.apply(roles, userValue);
        validateCallPrivileges(roles, sessionUser, user);
        return new CompiledHasPrivilege(roles, signature, boundSignature, sessionUser, user, compiledPermissions);
    }


    /**
     * @return List of {@link Permission} compiled from inout or NULL if cannot be compiled.
     */
    @Nullable
    private Collection<Permission> normalizePermissionIfLiteral(Symbol symbol) {
        if (symbol instanceof Input<?> input) {
            var value = input.value();
            if (value == null) {
                return null;
            }
            return parsePermissions.parse((String) value);
        }
        return null;
    }

    @Override
    public final Boolean evaluate(TransactionContext txnCtx, NodeContext nodeCtx, Input<Object>[] args) {
        Object userNameOrOid, schemaNameOrOid, privileges;
        Roles roles = nodeCtx.roles();

        var sessionUser = userByName(nodeCtx.roles(), txnCtx.sessionSettings().userName());
        Role user;
        if (args.length == 2) {
            schemaNameOrOid = args[0].value();
            privileges = args[1].value();
            user = sessionUser;
        } else {
            userNameOrOid = args[0].value();
            if (userNameOrOid == null) {
                return null;
            }
            user = getUser.apply(roles, userNameOrOid);
            validateCallPrivileges(roles, sessionUser, user);
            schemaNameOrOid = args[1].value();
            privileges = args[2].value();
        }

        if (schemaNameOrOid == null || privileges == null) {
            return null;
        }
        return checkPrivilege.check(roles, user, schemaNameOrOid, parsePermissions.parse((String) privileges), nodeCtx.schemas());
    }

    private class CompiledHasPrivilege extends Scalar<Boolean, Object> {

        private final Roles roles;
        private final Role sessionUser;
        private final Role user;

        // We don't use String to avoid unnecessary cast of the ignored argument
        // when function provides pre-computed results
        private final Function<Object, Collection<Permission>> getPermissions;

        private CompiledHasPrivilege(Roles roles,
                                     Signature signature,
                                     BoundSignature boundSignature,
                                     Role sessionUser,
                                     Role user,
                                     @Nullable Collection<Permission> compiledPermissions) {
            super(signature, boundSignature);
            this.roles = roles;
            this.sessionUser = sessionUser;
            this.user = user;
            if (compiledPermissions != null) {
                getPermissions = s -> compiledPermissions;
            } else {
                getPermissions = s -> parsePermissions.parse((String) s);
            }
        }

        @Override
        @SafeVarargs
        public final Boolean evaluate(TransactionContext txnCtx, NodeContext nodeContext, Input<Object>... args) {
            Object schema, privilege;
            if (args.length == 2) {
                // User is taken from the session
                schema = args[0].value();
                privilege = args[1].value();
            } else {
                // args[0] is resolved to a user
                validateCallPrivileges(roles, sessionUser, user);
                schema = args[1].value();
                privilege = args[2].value();
            }
            if (schema == null || privilege == null) {
                return null;
            }
            return checkPrivilege.check(roles, user, schema, getPermissions.apply(privilege), nodeContext.schemas());
        }
    }

    protected static void validateCallPrivileges(Roles roles, Role sessionUser, Role user) {
        // Only superusers can call this function for other users
        if (user.name().equals(sessionUser.name()) == false
            && roles.hasPrivilege(sessionUser, Permission.DQL, Securable.TABLE, "sys.privileges") == false
            && roles.hasPrivilege(sessionUser, Permission.AL, Securable.CLUSTER, "crate") == false) {
            throw new MissingPrivilegeException(sessionUser.name());
        }
    }

    public interface CheckPrivilege {
        Boolean check(Roles roles, Role user, Object object, Collection<Permission> permissions, Schemas schemas);
    }

    public interface ParsePermissions {

        /**
         * @param permissionNames is a comma separated list that will be mapped to a collection of {@link Permission}.
         * Refer to the concrete implementations for exact mappings.
         * Extra whitespaces between privilege names and repetition of a valid argument are allowed.
         *
         * @throws IllegalArgumentException if privilege contains invalid permission.
         * @return collection of permissions parsed
         */
        Collection<Permission> parse(String permissionNames);
    }
}
