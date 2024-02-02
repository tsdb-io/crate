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

package io.crate.exceptions;

import java.util.Locale;

import org.jetbrains.annotations.Nullable;

import io.crate.role.JwtProperties;

public class RoleAlreadyExistsException extends RuntimeException implements ConflictException, UnscopedException {

    private RoleAlreadyExistsException(String message) {
        super(message);
    }

    public static RoleAlreadyExistsException of(String roleName, @Nullable JwtProperties jwtProperties) {
        if (jwtProperties == null) {
            return new RoleAlreadyExistsException(String.format(Locale.ENGLISH, "Role '%s' already exists", roleName));
        } else {
            return new RoleAlreadyExistsException(String.format(
                Locale.ENGLISH,
                "Role '%s' or another role with the same combination of jwt properties already exists",
                roleName
            ));
        }
    }
}
