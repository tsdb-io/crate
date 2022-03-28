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

package org.elasticsearch.transport;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.elasticsearch.client.Client;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.threadpool.ThreadPool;

import io.crate.action.FutureActionListener;
import io.crate.common.io.IOUtils;
import io.crate.replication.logical.metadata.ConnectionInfo;
import io.crate.types.DataTypes;


public class RemoteCluster implements Closeable {

    public enum ConnectionStrategy {
        SNIFF,
        PG_TUNNEL;
    }

    public static final Setting<ConnectionStrategy> REMOTE_CONNECTION_MODE = new Setting<>(
        "mode",
        ConnectionStrategy.SNIFF.name(),
        value -> ConnectionStrategy.valueOf(value.toUpperCase(Locale.ROOT)),
        DataTypes.STRING,
        Setting.Property.Dynamic
    );

    /**
     * A list of initial seed nodes to discover eligible nodes from the remote cluster
     */
    public static final Setting<List<String>> REMOTE_CLUSTER_SEEDS = Setting.listSetting(
        "seeds",
        null,
        s -> {
            // validate seed address
            RemoteConnectionParser.parsePort(s);
            return s;
        },
        s -> List.of(),
        value -> {},
        DataTypes.STRING_ARRAY,
        Setting.Property.Dynamic
    );

    public static Set<String> SETTING_NAMES = Set.of(
        REMOTE_CONNECTION_MODE.getKey(),
        REMOTE_CLUSTER_SEEDS.getKey()
    );

    private final String clusterName;
    private final ConnectionInfo connectionInfo;
    private final TransportService transportService;
    private final ConnectionStrategy connectionStrategy;
    private final ThreadPool threadPool;
    private final Settings settings;
    private final List<Closeable> toClose = new ArrayList<>();

    private volatile Client client;

    public RemoteCluster(String name,
                         Settings settings,
                         ConnectionInfo connectionInfo,
                         ThreadPool threadPool,
                         TransportService transportService) {
        this.settings = settings;
        this.threadPool = threadPool;
        this.clusterName = name;
        this.connectionInfo = connectionInfo;
        this.transportService = transportService;
        this.connectionStrategy = REMOTE_CONNECTION_MODE.get(connectionInfo.settings());
    }

    public Client client() {
        return client;
    }

    @Override
    public void close() throws IOException {
        IOUtils.close(toClose);
    }

    public CompletableFuture<Client> connectAndGetClient() {
        synchronized (this) {
            Client currentClient = client;
            if (currentClient != null) {
                return CompletableFuture.completedFuture(currentClient);
            }
        }
        var futureClient = switch (connectionStrategy) {
            case SNIFF -> connectSniff();
            case PG_TUNNEL -> connectPgTunnel();
        };
        return futureClient.whenComplete((c, err) -> {
            client = c;
        });
    }

    private CompletableFuture<Client> connectPgTunnel() {
        return CompletableFuture.failedFuture(new IllegalArgumentException("PG tunneling is not supported"));
    }

    private CompletableFuture<Client> connectSniff() {
        var remoteConnection = new RemoteClusterConnection(
            settings,
            connectionInfo.settings(),
            clusterName,
            transportService
        );
        toClose.add(remoteConnection);
        var remoteClient = new RemoteClusterClient(settings, threadPool, transportService, remoteConnection);
        FutureActionListener<Void, Client> listener = new FutureActionListener<>(ignored -> {
            return remoteClient;
        });
        remoteConnection.ensureConnected(listener);
        return listener;
    }
}