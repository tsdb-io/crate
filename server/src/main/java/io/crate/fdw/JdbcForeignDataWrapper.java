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

package io.crate.fdw;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Settings;

import io.crate.data.BatchIterator;
import io.crate.data.Row;
import io.crate.execution.dsl.projection.builder.InputColumns;
import io.crate.execution.engine.collect.CollectExpression;
import io.crate.execution.engine.pipeline.InputRowProjector;
import io.crate.expression.InputFactory;
import io.crate.expression.InputFactory.Context;
import io.crate.expression.operator.AndOperator;
import io.crate.expression.operator.EqOperator;
import io.crate.expression.operator.GtOperator;
import io.crate.expression.operator.GteOperator;
import io.crate.expression.operator.LtOperator;
import io.crate.expression.operator.LteOperator;
import io.crate.expression.operator.OrOperator;
import io.crate.expression.predicate.NotPredicate;
import io.crate.expression.symbol.Function;
import io.crate.expression.symbol.Symbol;
import io.crate.fdw.ServersMetadata.Server;
import io.crate.metadata.Reference;
import io.crate.metadata.RelationName;
import io.crate.metadata.TransactionContext;
import io.crate.metadata.settings.SessionSettings;
import io.crate.role.Role;

final class JdbcForeignDataWrapper implements ForeignDataWrapper {

    /**
     * Functions that any foreign database accessible via jdbc must support
     */
    private static final Set<String> SAFE_FUNCTIONS = Set.of(
        AndOperator.NAME,
        OrOperator.NAME,
        NotPredicate.NAME,
        EqOperator.NAME,
        GtOperator.NAME,
        GteOperator.NAME,
        LtOperator.NAME,
        LteOperator.NAME
    );

    private final InputFactory inputFactory;
    private final Settings settings;
    private final Setting<String> urlSetting = Setting.simpleString("url");
    private final List<Setting<?>> mandatoryServerOptions = List.of(urlSetting);

    private final Setting<String> schemaName = Setting.simpleString("schema_name");
    private final Setting<String> tableName = Setting.simpleString("table_name");
    private final List<Setting<?>> optionalTableOptions = List.of(
        schemaName,
        tableName
    );

    private final Setting<String> foreignUser = Setting.simpleString("user");
    private final Setting<String> foreignPw = Setting.simpleString("password");
    private final List<Setting<?>> optionalUserOptions = List.of(
        foreignUser,
        foreignPw
    );

    JdbcForeignDataWrapper(Settings settings, InputFactory inputFactory) {
        this.settings = settings;
        this.inputFactory = inputFactory;
    }

    @Override
    public List<Setting<?>> mandatoryServerOptions() {
        return mandatoryServerOptions;
    }

    @Override
    public List<Setting<?>> optionalTableOptions() {
        return optionalTableOptions;
    }

    @Override
    public List<Setting<?>> optionalUserOptions() {
        return optionalUserOptions;
    }

    @Override
    public CompletableFuture<BatchIterator<Row>> getIterator(Role currentUser,
                                                             Server server,
                                                             ForeignTable foreignTable,
                                                             TransactionContext txnCtx,
                                                             List<Symbol> collect,
                                                             Symbol query) {
        SessionSettings sessionSettings = txnCtx.sessionSettings();
        Settings userOptions = server.users().get(currentUser.name());
        if (userOptions == null) {
            userOptions = Settings.EMPTY;
        }
        String user = foreignUser.get(userOptions);
        String password = foreignPw.get(userOptions);
        var properties = new Properties();
        properties.setProperty("user", user.isEmpty() ? sessionSettings.userName() : user);
        if (!password.isEmpty()) {
            properties.setProperty("password", password);
        }

        // It's unknown if/what kind of scalars are supported by the remote.
        // Evaluate them locally and only fetch columns
        List<Reference> refs = new ArrayList<>(collect.size());
        for (var symbol : collect) {
            symbol.visit(Reference.class, refs::add);
        }

        Settings options = server.options();
        String url = urlSetting.get(options);
        if (!url.startsWith("jdbc:")) {
            throw new IllegalArgumentException("Invalid JDBC connection string provided");
        }
        URI uri;
        try {
            uri = new URI(url.substring(5));
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
        String host = uri.getHost();
        if (host == null) {
            throw new IllegalArgumentException("Invalid JDBC connection string provided");
        }
        InetAddress inetAddress;
        try {
            inetAddress = InetAddress.getByName(host);
        } catch (UnknownHostException e) {
            throw new RuntimeException(e);
        }
        if ((inetAddress.isAnyLocalAddress() || inetAddress.isLoopbackAddress())
                && !currentUser.isSuperUser()
                && !ForeignDataWrappers.ALLOW_LOCAL.get(settings)) {
            throw new UnsupportedOperationException(
                "Only a super user can connect to localhost unless `fdw.allow_local` is set to true");
        }
        String remoteSchema = schemaName.get(foreignTable.options());
        String remoteTable = tableName.get(foreignTable.options());
        RelationName remoteName = new RelationName(
            remoteSchema.isEmpty() ? foreignTable.name().schema() : remoteSchema,
            remoteTable.isEmpty() ? foreignTable.name().name() : remoteTable);

        assert supportsQueryPushdown(query)
            : "ForeignCollect must only have a query where `supportsQueryPushDown` is true";
        BatchIterator<Row> it = new JdbcBatchIterator(url, properties, refs, query, remoteName);
        if (!refs.containsAll(collect)) {
            var sourceRefs = new InputColumns.SourceSymbols(refs);
            List<Symbol> inputColumns = InputColumns.create(collect, sourceRefs);
            Context<CollectExpression<Row, ?>> inputCtx = inputFactory.ctxForInputColumns(txnCtx, inputColumns);
            InputRowProjector inputRowProjector = new InputRowProjector(inputCtx.topLevelInputs(), inputCtx.expressions());
            it = inputRowProjector.apply(it);
        }
        return CompletableFuture.completedFuture(it);
    }

    @Override
    public boolean supportsQueryPushdown(Symbol query) {
        return !query.any(x -> x instanceof Function fn && !SAFE_FUNCTIONS.contains(fn.name()));
    }
}
