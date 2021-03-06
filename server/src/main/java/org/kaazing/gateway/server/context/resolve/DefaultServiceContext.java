/**
 * Copyright (c) 2007-2014 Kaazing Corporation. All rights reserved.
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.kaazing.gateway.server.context.resolve;

import static java.lang.String.format;
import static java.util.Arrays.asList;
import static org.kaazing.gateway.resource.address.ResourceAddress.CONNECT_REQUIRES_INIT;
import static org.kaazing.gateway.resource.address.ResourceAddress.TRANSPORT;
import static org.kaazing.gateway.server.context.resolve.DefaultClusterContext.CLUSTER_LOGGER_NAME;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.security.Key;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;

import org.apache.mina.core.buffer.IoBuffer;
import org.apache.mina.core.future.ConnectFuture;
import org.apache.mina.core.service.IoHandler;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.core.session.IoSessionInitializer;
import org.kaazing.gateway.resource.address.Protocol;
import org.kaazing.gateway.resource.address.ResourceAddress;
import org.kaazing.gateway.resource.address.ResourceAddressFactory;
import org.kaazing.gateway.resource.address.URLUtils;
import org.kaazing.gateway.security.AuthenticationContext;
import org.kaazing.gateway.security.CrossSiteConstraintContext;
import org.kaazing.gateway.security.RealmContext;
import org.kaazing.gateway.server.service.AbstractSessionInitializer;
import org.kaazing.gateway.service.AcceptOptionsContext;
import org.kaazing.gateway.service.ConnectOptionsContext;
import org.kaazing.gateway.service.Service;
import org.kaazing.gateway.service.ServiceContext;
import org.kaazing.gateway.service.ServiceProperties;
import org.kaazing.gateway.service.TransportOptionNames;
import org.kaazing.gateway.service.cluster.ClusterContext;
import org.kaazing.gateway.service.cluster.MemberId;
import org.kaazing.gateway.service.messaging.collections.CollectionsFactory;
import org.kaazing.gateway.transport.BridgeAcceptor;
import org.kaazing.gateway.transport.BridgeConnector;
import org.kaazing.gateway.transport.BridgeSessionInitializer;
import org.kaazing.gateway.transport.IoFilterAdapter;
import org.kaazing.gateway.transport.Transport;
import org.kaazing.gateway.transport.TransportFactory;
import org.kaazing.gateway.util.Encoding;
import org.kaazing.gateway.util.GL;
import org.kaazing.gateway.util.scheduler.SchedulerProvider;
import org.kaazing.mina.core.session.IoSessionEx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.hazelcast.core.IMap;

public class DefaultServiceContext implements ServiceContext {

    public static final String BALANCER_MAP_NAME = "balancerMap";
    public static final String MEMBERID_BALANCER_MAP_NAME = "memberIdBalancerMap";

    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private static final String[] EMPTY_REQUIRE_ROLES = new String[]{};

    private static final String AUTHENTICATION_CONNECT = "authenticationConnect";
    private static final String AUTHENTICATION_IDENTIFIER = "authenticationIdentifier";
    private static final String BALANCE_ORIGINS = "balanceOrigins";
    private static final String ENCRYPTION_KEY_ALIAS = "encryptionKeyAlias";
    private static final String GATEWAY_ORIGIN_SECURITY = "gatewayHttpOriginSecurity";
    private static final String LOGIN_CONTEXT_FACTORY = "loginContextFactory";
    private static final String ORIGIN_SECURITY = "originSecurity";
    private static final String REALM_AUTHENTICATION_COOKIE_NAMES = "realmAuthenticationCookieNames";
    private static final String REALM_AUTHENTICATION_HEADER_NAMES = "realmAuthenticationHeaderNames";
    private static final String REALM_AUTHENTICATION_PARAMETER_NAMES = "realmAuthenticationParameterNames";
    private static final String REALM_AUTHORIZATION_MODE = "realmAuthorizationMode";
    private static final String REALM_CHALLENGE_SCHEME = "realmChallengeScheme";
    private static final String REALM_DESCRIPTION = "realmDescription";
    private static final String REALM_NAME = "realmName";
    private static final String REQUIRED_ROLES = "requiredRoles";
    private static final String SERVICE_DOMAIN = "serviceDomain";
    private static final String TEMP_DIRECTORY = "tempDirectory";

    /**
     * Prefix to the authentication scheme to indicate that the Kaazing client application will handle the challenge rather than
     * delegate to the browser or the native platform.
     */
    public static final String AUTH_SCHEME_APPLICATION_PREFIX = "Application ";

    private final String serviceType;
    private final String serviceName;
    private final String serviceDescription;
    private final Service service;
    private final File tempDir;
    private final File webDir;
    private final Collection<URI> balances;
    private final Collection<URI> accepts;
    private final Collection<URI> connects;
    private final ServiceProperties properties;
    private final Map<String, String> mimeMappings;
    private final Map<URI, ? extends Map<String, ? extends CrossSiteConstraintContext>> acceptConstraintsByURI;
    private final TransportFactory transportFactory;
    private List<Map<URI, Map<String, CrossSiteConstraintContext>>> authorityToSetOfAcceptConstraintsByURI;
    private final String[] requireRoles;
    private final Map<URI, ResourceAddress> bindings;
    private final ConcurrentMap<Long, IoSessionEx> activeSessions;
    private final Map<URI, IoHandler> bindHandlers;
    private final ClusterContext clusterContext;
    private final AcceptOptionsContext acceptOptionsContext;
    private final ConnectOptionsContext connectOptionsContext;
    private final RealmContext serviceRealmContext;
    private final ResourceAddressFactory resourceAddressFactory;
    private final Key encryptionKey;
    private final Logger logger;
    private final SchedulerProvider schedulerProvider;
    private final boolean supportsAccepts;
    private final boolean supportsConnects;
    private final boolean supportsMimeMappings;
    private final int processorCount;
    private int hashCode = -1;

    private final AtomicBoolean started = new AtomicBoolean(false);
    private final Map<String, Object> serviceSpecificObjects;

    /**
     * Default Session Initializer
     */
    private IoSessionInitializer<ConnectFuture> sessionInitializer = new IoSessionInitializer<ConnectFuture>() {
        @Override
        public void initializeSession(IoSession session, ConnectFuture future) {
            // FIXME:  Do we need serviceContext and resource address passed in to be effective?
            session.getFilterChain().addLast(SESSION_FILTER_NAME, new IoFilterAdapter<IoSessionEx>() {
                @Override
                protected void doSessionOpened(NextFilter nextFilter, IoSessionEx session) throws Exception {
                    addActiveSession(session);
                    super.doSessionOpened(nextFilter, session);
                }

                @Override
                protected void doSessionClosed(NextFilter nextFilter, IoSessionEx session) throws Exception {
                    removeActiveSession(session);
                    super.doSessionClosed(nextFilter, session);
                }
            });
        }
    };

    public DefaultServiceContext(String serviceType, Service service) {
        this(serviceType,
                null,
                null,
                service,
                null,
                null,
                Collections.<URI>emptySet(),
                Collections.<URI>emptySet(),
                Collections.<URI>emptySet(),
                new DefaultServiceProperties(),
                EMPTY_REQUIRE_ROLES,
                Collections.<String, String>emptyMap(),
                Collections.<URI, Map<String, CrossSiteConstraintContext>>emptyMap(),
                null,
                new DefaultAcceptOptionsContext(),
                new DefaultConnectOptionsContext(),
                null,
                null,
                null,
                true,
                true,
                false,
                1,
                TransportFactory.newTransportFactory(Collections.EMPTY_MAP),
                ResourceAddressFactory.newResourceAddressFactory()
        );
    }

    public DefaultServiceContext(String serviceType,
                                 String serviceName,
                                 String serviceDescription,
                                 Service service,
                                 File webDir,
                                 File tempDir,
                                 Collection<URI> balances,
                                 Collection<URI> accepts,
                                 Collection<URI> connects,
                                 ServiceProperties properties,
                                 String[] requireRoles,
                                 Map<String, String> mimeMappings,
                                 Map<URI, Map<String, CrossSiteConstraintContext>> crossSiteConstraints,
                                 ClusterContext clusterContext,
                                 AcceptOptionsContext acceptOptionsContext,
                                 ConnectOptionsContext connectOptionsContext,
                                 RealmContext serviceRealmContext,
                                 Key encryptionKey,
                                 SchedulerProvider schedulerProvider,
                                 boolean supportsAccepts,
                                 boolean supportsConnects,
                                 boolean supportsMimeMappings,
                                 int processorCount,
                                 TransportFactory transportFactory,
                                 ResourceAddressFactory resourceAddressFactory) {
        this.serviceType = serviceType;
        this.serviceName = serviceName;
        this.serviceDescription = serviceDescription;
        this.service = service;
        this.webDir = webDir;
        this.tempDir = tempDir;
        this.balances = balances;
        this.accepts = accepts;
        this.connects = connects;
        this.properties = properties;
        this.requireRoles = requireRoles;
        this.mimeMappings = mimeMappings;
        this.acceptConstraintsByURI = crossSiteConstraints;
        this.bindings = new HashMap<>();
        this.activeSessions = new ConcurrentHashMap<>();
        this.bindHandlers = new HashMap<>(4);
        this.clusterContext = clusterContext;
        this.acceptOptionsContext = acceptOptionsContext;
        this.serviceRealmContext = serviceRealmContext;
        this.connectOptionsContext = connectOptionsContext;
        this.encryptionKey = encryptionKey;
        this.logger = LoggerFactory.getLogger("service." + serviceType.replace("$", "_"));
        this.schedulerProvider = schedulerProvider;
        this.supportsAccepts = supportsAccepts;
        this.supportsConnects = supportsConnects;
        this.supportsMimeMappings = supportsMimeMappings;
        this.processorCount = processorCount;
        this.transportFactory = transportFactory;
        this.resourceAddressFactory = resourceAddressFactory;
        this.serviceSpecificObjects = new HashMap<>();
    }

    @Override
    public boolean equals(Object otherObject) {
        if (otherObject instanceof ServiceContext) {
            ServiceContext otherServiceContext = (ServiceContext) otherObject;
            if (this.serviceType.equals(otherServiceContext.getServiceType())) {
                Collection<URI> otherAccepts = otherServiceContext.getAccepts();
                for (URI uri : this.accepts) {
                    if (!otherAccepts.contains(uri)) {
                        return false;
                    }
                }
                // same type, same accepts, return true
                return true;
            }
        }
        return false;
    }

    @Override
    public int hashCode() {
        if (hashCode == -1) {
            hashCode = Objects.hash(serviceType, accepts, getServiceName());
        }
        return hashCode;
    }

    @Override
    public int getProcessorCount() {
        return processorCount;
    }

    @Override
    public RealmContext getServiceRealm() {
        return serviceRealmContext;
    }

    @Override
    public String getAuthorizationMode() {
        if (serviceRealmContext != null &&
                serviceRealmContext.getAuthenticationContext() != null) {
            return serviceRealmContext.getAuthenticationContext().getAuthorizationMode();
        }
        return null;
    }

    @Override
    public String getSessionTimeout() {
        if (serviceRealmContext != null &&
                serviceRealmContext.getAuthenticationContext() != null) {
            return serviceRealmContext.getAuthenticationContext().getSessionTimeout();
        }
        return null;
    }

    @Override
    public String decrypt(String encrypted) throws Exception {
        ByteBuffer decoded = Encoding.BASE64.decode(ByteBuffer.wrap(encrypted.getBytes(UTF_8)));
        InputStream bin = IoBuffer.wrap(decoded).asInputStream();

        Cipher cipher = Cipher.getInstance(encryptionKey.getAlgorithm());
        cipher.init(Cipher.DECRYPT_MODE, encryptionKey);
        DataInputStream in = new DataInputStream(new CipherInputStream(bin, cipher));
        try {
            return in.readUTF();
        } catch (Exception e) {
            return "";
        }
    }

    @Override
    public String encrypt(String plaintext) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        Cipher cipher = Cipher.getInstance(encryptionKey.getAlgorithm());
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey);
        DataOutputStream out = new DataOutputStream(new CipherOutputStream(bos, cipher));
        out.writeUTF(plaintext);
        out.close();

        ByteBuffer encoded = Encoding.BASE64.encode(ByteBuffer.wrap(bos.toByteArray(), 0, bos.size()));
        return IoBuffer.wrap(encoded).getString(UTF_8.newDecoder());
    }

    @Override
    public AcceptOptionsContext getAcceptOptionsContext() {
        return acceptOptionsContext;
    }

    @Override
    public ConnectOptionsContext getConnectOptionsContext() {
        return connectOptionsContext;
    }

    public String getServiceType() {
        return serviceType;
    }

    public String getServiceName() {
        return serviceName;
    }

    public String getServiceDescription() {
        return serviceDescription;
    }

    @Override
    public Collection<URI> getAccepts() {
        return accepts;
    }

    @Override
    public Collection<URI> getBalances() {
        return balances;
    }

    @Override
    public Collection<URI> getConnects() {
        return connects;
    }

    @Override
    public Service getService() {
        return service;
    }

    @Override
    public ServiceProperties getProperties() {
        return properties;
    }

    @Override
    public String[] getRequireRoles() {
        return requireRoles;
    }

    @Override
    public Map<String, String> getMimeMappings() {
        return mimeMappings;
    }

    @Override
    public String getContentType(String fileExtension) {
        String contentType = fileExtension == null ? null : mimeMappings.get(fileExtension.toLowerCase());
        return contentType;
    }

    @Override
    public Map<URI, ? extends Map<String, ? extends CrossSiteConstraintContext>> getCrossSiteConstraints() {
        return acceptConstraintsByURI;
    }

    @Override
    public File getTempDirectory() {
        return tempDir;
    }

    @Override
    public File getWebDirectory() {
        return webDir;
    }

    @Override
    public Logger getLogger() {
        return logger;
    }

    @Override
    public SchedulerProvider getSchedulerProvider() {
        return schedulerProvider;
    }

    @Override
    public void bind(Collection<URI> bindURIs, IoHandler handler) {
        bind(bindURIs, handler, acceptOptionsContext);
    }

    @Override
    public void bind(Collection<URI> bindURIs, IoHandler handler, AcceptOptionsContext acceptOptionsContext) {
        bind(bindURIs, handler, acceptOptionsContext, null);
    }

    @Override
    public void bind(Collection<URI> bindURIs, IoHandler handler, BridgeSessionInitializer<ConnectFuture>
            bridgeSessionInitializer) {
        bind(bindURIs, handler, acceptOptionsContext, bridgeSessionInitializer);
    }

    @Override
    public void bindConnectsIfNecessary(Collection<URI> connectURIs) {

        for (URI connectURI : connectURIs) {
            // TODO: services should bind ResourceAddress directly, rather than passing URIs here
            Map<String, Object> connectOptions = buildResourceAddressOptions(connectURI, connectOptionsContext);
            ResourceAddress connectAddress = resourceAddressFactory.newResourceAddress(connectURI, connectOptions);
            bindConnectIfNecessary(connectAddress);
        }

    }

    @Override
    public void unbindConnectsIfNecessary(Collection<URI> connectURIs) {

        for (URI connectURI : connectURIs) {
            // TODO: services should bind ResourceAddress directly, rather than passing URIs here
            Map<String, Object> connectOptions = buildResourceAddressOptions(connectURI, connectOptionsContext);
            ResourceAddress connectAddress = resourceAddressFactory.newResourceAddress(connectURI, connectOptions);
            unbindConnectIfNecessary(connectAddress);
        }

    }

    private void bindConnectIfNecessary(ResourceAddress connectAddress) {
        if (connectAddress.getOption(CONNECT_REQUIRES_INIT)) {
            final URI transportURI = connectAddress.getResource();
            final String transportSchemeName = transportURI.getScheme();
            Transport transport = transportFactory.getTransportForScheme(transportSchemeName);
            assert transport != null;
            transport.getConnector(connectAddress).connectInit(connectAddress);
        } else {
            ResourceAddress connectTransport = connectAddress.getOption(TRANSPORT);
            if (connectTransport != null) {
                bindConnectIfNecessary(connectTransport);
            }
        }
    }

    private void unbindConnectIfNecessary(ResourceAddress connectAddress) {
        if (connectAddress.getOption(CONNECT_REQUIRES_INIT)) {
            final URI transportURI = connectAddress.getResource();
            final String transportSchemeName = transportURI.getScheme();
            Transport transport = transportFactory.getTransportForScheme(transportSchemeName);
            assert transport != null;
            transport.getConnector(connectAddress).connectDestroy(connectAddress);
        } else {
            ResourceAddress connectTransport = connectAddress.getOption(TRANSPORT);
            if (connectTransport != null) {
                unbindConnectIfNecessary(connectTransport);
            }
        }
    }

    @Override
    public void bind(Collection<URI> bindURIs,
                     IoHandler handler,
                     AcceptOptionsContext acceptOptionsContext,
                     final BridgeSessionInitializer<ConnectFuture> bridgeSessionInitializer) {
        if (handler == null) {
            throw new IllegalArgumentException("Cannot bind without handler");
        }

        for (URI uri : bindURIs) {
            bindHandlers.put(uri, handler);
        }

        Map<Transport, List<URI>> bindsByTransport = getURIsByTransport(bindURIs);

        // for each transport group, create resource address for URIs and bind to transport.
        for (Entry<Transport, List<URI>> entry : bindsByTransport.entrySet()) {
            Transport transport = entry.getKey();
            List<URI> transportAccepts = entry.getValue();

            for (URI transportAccept : transportAccepts) {

                Map<String, Object> options = buildResourceAddressOptions(transportAccept, acceptOptionsContext);

                ResourceAddress address = resourceAddressFactory.newResourceAddress(transportAccept, options);

                bindInternal(address, handler, transport, sessionInitializer, bridgeSessionInitializer);
                bindings.put(transportAccept, address);
            }
        }

        //
        // After the service has been physically bound, update the cluster state to reflect this service as a possible
        // balance target.
        //
        if (balances != null && balances.size() > 0) {
            if (!accepts.containsAll(bindURIs)) {
                // if this bind() call is for URIs that aren't the service accept URIs (typically just the broadcast service's
                // accept property) then don't update balancer state.  Only service accept URIs are balance targets.
                return;
            }

            CollectionsFactory factory = clusterContext.getCollectionsFactory();
            if (factory != null) {
                Map<MemberId, Map<URI, List<URI>>> memberIdBalancerUriMap = factory.getMap(MEMBERID_BALANCER_MAP_NAME);
                if (memberIdBalancerUriMap == null) {
                    throw new IllegalStateException("MemberId to BalancerMap is null");
                }

                MemberId localMember = clusterContext.getLocalMember();
                Map<URI, List<URI>> memberBalanceUriMap = memberIdBalancerUriMap.get(localMember);
                if (memberBalanceUriMap == null) {
                    memberBalanceUriMap = new HashMap<>();
                }

                List<URI> acceptUris = new ArrayList<>();
                if (accepts != null) {
                    acceptUris.addAll(accepts);
                }

                IMap<URI, Set<URI>> sharedBalanceUriMap = factory.getMap(BALANCER_MAP_NAME);
                for (URI balanceURI : balances) {
                    if (accepts != null) {
                        memberBalanceUriMap.put(balanceURI, acceptUris);

                        // get and add to the list here instead of overwriting it
                        Set<URI> balanceUris = null;
                        Set<URI> newBalanceUris = null;

                        do {
                            balanceUris = sharedBalanceUriMap.get(balanceURI);
                            if (balanceUris == null) {
                                newBalanceUris = new HashSet<>();
                                newBalanceUris.addAll(accepts);
                                balanceUris = sharedBalanceUriMap.putIfAbsent(balanceURI, newBalanceUris);
                                if (balanceUris == null) {
                                    break;
                                }
                            }

                            newBalanceUris = new HashSet<>(balanceUris);
                            newBalanceUris.addAll(accepts);
                            if (newBalanceUris.equals(balanceUris)) {
                                break;
                            }
                        } while (!sharedBalanceUriMap.replace(balanceURI, balanceUris, newBalanceUris));

                        GL.info(CLUSTER_LOGGER_NAME, "Cluster member {}: service {} bound", localMember, serviceType);
                        GL.debug(CLUSTER_LOGGER_NAME, "Added balance URIs {}, new global list is {}",
                                acceptUris, newBalanceUris);
                    }
                }

                memberIdBalancerUriMap.put(localMember, memberBalanceUriMap);
            }
        }
    }

    private Map<String, Object> buildResourceAddressOptions(URI transportURI, AcceptOptionsContext acceptOptionsContext) {
        // options is a new HashMap
        final Map<String, Object> options = acceptOptionsContext.asOptionsMap();
        injectServiceOptions(transportURI, options);

        // TODO: Instead of null, perhaps ServiceContext provides a
        // set of next protocol possibilities that are bound individually.
        options.put(TransportOptionNames.NEXT_PROTOCOL, null);
        return options;
    }

    private Map<String, Object> buildResourceAddressOptions(URI transportURI, ConnectOptionsContext connectOptionsContext) {
        // options is a new HashMap
        final Map<String, Object> options = connectOptionsContext.asOptionsMap();
        injectServiceOptions(transportURI, options);

        // TODO: Instead of null, perhaps ServiceContext provides a
        // set of next protocol possibilities that are bound individually.
        options.put(TransportOptionNames.NEXT_PROTOCOL, null);
        return options;
    }

    private void injectServiceOptions(URI transportURI, Map<String, Object> options) {

        Map<String, ? extends CrossSiteConstraintContext> acceptConstraints = acceptConstraintsByURI.get(transportURI);
        if (acceptConstraints == null && "balancer".equals(serviceType)) {
            if (transportURI.getPath() != null && transportURI.getPath().endsWith("/;e")) {
                transportURI = transportURI
                        .resolve(transportURI.getPath().substring(0, transportURI.getPath().length() - "/;e".length()));
            }
            acceptConstraints = acceptConstraintsByURI.get(URLUtils.modifyURIScheme(transportURI, "ws"));
            if (acceptConstraints == null && transportFactory.getProtocol(transportURI).isSecure()) {
                acceptConstraints = acceptConstraintsByURI.get(URLUtils.modifyURIScheme(transportURI, "wss"));
            }
        }
        if (acceptConstraints != null) {
            // needed by cross origin bridge filter to cache resources
            options.put(format("http[http/1.1].%s", ORIGIN_SECURITY), acceptConstraints);
            options.put(format("http[x-kaazing-handshake].%s", ORIGIN_SECURITY), acceptConstraints);
            options.put(format("http[httpxe/1.1].%s", ORIGIN_SECURITY), acceptConstraints);
            options.put(format("http[httpxe/1.1].http[http/1.1].%s", ORIGIN_SECURITY), acceptConstraints);
        }
        // needed for silverlight
        options.put(format("http[http/1.1].%s", GATEWAY_ORIGIN_SECURITY), authorityToSetOfAcceptConstraintsByURI);
        options.put(format("http[x-kaazing-handshake].%s", GATEWAY_ORIGIN_SECURITY), authorityToSetOfAcceptConstraintsByURI);
        options.put(format("http[httpxe/1.1].%s", GATEWAY_ORIGIN_SECURITY), authorityToSetOfAcceptConstraintsByURI);
        options.put(format("http[httpxe/1.1].http[http/1.1].%s", GATEWAY_ORIGIN_SECURITY),
                authorityToSetOfAcceptConstraintsByURI);

        //needed for correct enforcement of same origin in clustered gateway scenarios (KG-9686)
        final Collection<URI> balanceOriginUris = toHttpBalanceOriginURIs(getBalances());

        if (balanceOriginUris != null) {
            options.put(format("http[http/1.1].%s", BALANCE_ORIGINS), balanceOriginUris);
            options.put(format("http[x-kaazing-handshake].%s", BALANCE_ORIGINS), balanceOriginUris);
            options.put(format("http[httpxe/1.1].%s", BALANCE_ORIGINS), balanceOriginUris);
            options.put(format("http[httpxe/1.1].http[http/1.1].%s", BALANCE_ORIGINS), balanceOriginUris);
        }

        // needed by resources handler to serve (cached) resources
        // TODO: convert to HTTP cache concept and drop in as a filter instead?
        options.put(format("http[http/1.1].%s", TEMP_DIRECTORY), tempDir);

        // We only need this option for KG-3476: silently convert a directory
        // service configured with application- challenge scheme to a non-application scheme.
        boolean forceNativeChallengeScheme = "directory".equals(getServiceType());

        // Add realmName property and  based on whether the service
        // is protected, and whether it is application- or native- security that is desired.
        if (serviceRealmContext != null) {
            final AuthenticationContext authenticationContext = serviceRealmContext.getAuthenticationContext();
            if (authenticationContext != null) {

                String challengeScheme = authenticationContext.getHttpChallengeScheme();
                boolean isApplicationChallengeScheme = challengeScheme.startsWith(AUTH_SCHEME_APPLICATION_PREFIX);

                if (isApplicationChallengeScheme && !forceNativeChallengeScheme) {
                    options.put(format("http[http/1.1].%s", REALM_CHALLENGE_SCHEME),
                            authenticationContext.getHttpChallengeScheme());
                    for (String optionPattern : asList("http[httpxe/1.1].%s", "http[x-kaazing-handshake].%s")) {
                        options.put(format(optionPattern, REALM_NAME),
                                serviceRealmContext.getName());
                        options.put(format(optionPattern, REQUIRED_ROLES),
                                getRequireRoles());
                        options.put(format(optionPattern, REALM_AUTHORIZATION_MODE),
                                authenticationContext.getAuthorizationMode());
                        options.put(format(optionPattern, REALM_CHALLENGE_SCHEME),
                                authenticationContext.getHttpChallengeScheme());
                        options.put(format(optionPattern, REALM_DESCRIPTION),
                                serviceRealmContext.getDescription());
                        options.put(format(optionPattern, REALM_AUTHENTICATION_HEADER_NAMES),
                                authenticationContext.getHttpHeaders());
                        options.put(format(optionPattern, REALM_AUTHENTICATION_PARAMETER_NAMES),
                                authenticationContext.getHttpQueryParameters());
                        options.put(format(optionPattern, REALM_AUTHENTICATION_COOKIE_NAMES),
                                authenticationContext.getHttpCookieNames());
                        options.put(format(optionPattern, LOGIN_CONTEXT_FACTORY),
                                serviceRealmContext.getLoginContextFactory());

                        // We need this to support reading legacy service properties during authentication.
                        // authentication-connect, authentication-identifier, encryption.key.alias, service.domain
                        // The negotiate properties are replaced with client-side capabilities to use different
                        // KDCs on a per-realm basis. The ksessionid cookie properties are needed to write the cookie
                        // out if needed (recycle authorization mode).
                        options.put(format(optionPattern, AUTHENTICATION_CONNECT),
                                getProperties().get("authentication.connect"));
                        options.put(format(optionPattern, AUTHENTICATION_IDENTIFIER),
                                getProperties().get("authentication.identifier"));
                        options.put(format(optionPattern, ENCRYPTION_KEY_ALIAS),
                                getProperties().get("encryption.key.alias"));
                        options.put(format(optionPattern, SERVICE_DOMAIN),
                                getProperties().get("service.domain"));

                    }
                }

                // TODO: eliminate forceNativeChallengeScheme by locking down authentication schemes for "directory" service
                if (!isApplicationChallengeScheme || forceNativeChallengeScheme) {
                    String optionPattern = "http[http/1.1].%s";
                    options.put(format(optionPattern, REALM_NAME),
                            serviceRealmContext.getName());
                    options.put(format(optionPattern, REQUIRED_ROLES),
                            getRequireRoles());
                    options.put(format(optionPattern, REALM_AUTHORIZATION_MODE),
                            authenticationContext.getAuthorizationMode());
                    options.put(format(optionPattern, REALM_CHALLENGE_SCHEME),
                            authenticationContext.getHttpChallengeScheme());
                    options.put(format(optionPattern, REALM_DESCRIPTION),
                            serviceRealmContext.getDescription());
                    options.put(format(optionPattern, REALM_AUTHENTICATION_HEADER_NAMES),
                            authenticationContext.getHttpHeaders());
                    options.put(format(optionPattern, REALM_AUTHENTICATION_PARAMETER_NAMES),
                            authenticationContext.getHttpQueryParameters());
                    options.put(format(optionPattern, REALM_AUTHENTICATION_COOKIE_NAMES),
                            authenticationContext.getHttpCookieNames());
                    options.put(format(optionPattern, LOGIN_CONTEXT_FACTORY),
                            serviceRealmContext.getLoginContextFactory());

                    // see note above for why this is needed
                    options.put(format(optionPattern, AUTHENTICATION_CONNECT),
                            getProperties().get("authentication.connect"));
                    options.put(format(optionPattern, AUTHENTICATION_IDENTIFIER),
                            getProperties().get("authentication.identifier"));
                    options.put(format(optionPattern, ENCRYPTION_KEY_ALIAS),
                            getProperties().get("encryption.key.alias"));
                    options.put(format(optionPattern, SERVICE_DOMAIN),
                            getProperties().get("service.domain"));

                }
            }
        }
    }

    private Collection<URI> toHttpBalanceOriginURIs(Collection<URI> balances) {
        if (balances == null || balances.isEmpty()) {
            return balances;
        }

        List<URI> result = new ArrayList<>(balances.size());
        for (URI uri : balances) {
            if (uri != null) {
                try {
                    final String scheme = uri.getScheme();
                    if ("ws".equals(scheme)) {
                        result.add(new URI("http", uri.getAuthority(), uri.getPath(), uri.getQuery(), uri.getFragment()));
                    } else if ("wss".equals(scheme)) {
                        result.add(new URI("https", uri.getAuthority(), uri.getPath(), uri.getQuery(), uri.getFragment()));
                    } else {
                        result.add(uri);
                    }
                } catch (URISyntaxException e) {
                    if (logger.isDebugEnabled()) {
                        logger.warn(String.format("Cannot translate balanc uri '%s' into a http balance origin.", uri));
                    }
                }
            }
        }
        return result;
    }

    private void bindInternal(final ResourceAddress address,
                              final IoHandler handler,
                              final Transport transport,
                              final IoSessionInitializer<ConnectFuture> sessionInitializer,
                              final BridgeSessionInitializer<ConnectFuture> bridgeSessionInitializer) {
        BridgeAcceptor acceptor = transport.getAcceptor(address);
        try {
            acceptor.bind(address, handler, new BridgeSessionInitializer<ConnectFuture>() {
                @Override
                public BridgeSessionInitializer<ConnectFuture> getParentInitializer(Protocol protocol) {
                    return (bridgeSessionInitializer != null) ? bridgeSessionInitializer.getParentInitializer(protocol) : null;
                }

                @Override
                public void initializeSession(IoSession session, ConnectFuture future) {
                    sessionInitializer.initializeSession(session, future);

                    if (bridgeSessionInitializer != null) {
                        bridgeSessionInitializer.initializeSession(session, future);
                    }
                }
            });
        } catch (RuntimeException re) {
            // Catch this RuntimeException and add a bit more information
            // to its message (cf KG-1462)
            throw new RuntimeException(String.format("Error binding to %s: %s", address.getResource(), re.getMessage()), re);
        }
    }

    /**
     * Return the URIs organized by transport.
     * <p/>
     * NOTE: because this relies on gatewayContext, we cannot call it until after the service context has been completely set up
     * (and in fact not until GatewayContextResolver has constructed, as it doesn't create the DefaultGatewayContext until the
     * last step in construction.
     *
     * @param uris
     * @return
     */
    private Map<Transport, List<URI>> getURIsByTransport(Collection<URI> uris) {
        Map<Transport, List<URI>> urisByTransport = new HashMap<>();

        // iterate over URIs and group them by transport
        for (URI uri : uris) {
            String uriScheme = uri.getScheme();
            Transport transport = transportFactory.getTransportForScheme(uriScheme);
            List<URI> list = urisByTransport.get(transport);
            if (list == null) {
                list = new ArrayList<>();
                urisByTransport.put(transport, list);
            }
            list.add(uri);
        }
        return urisByTransport;
    }

    @Override
    public void unbind(Collection<URI> bindURIs, IoHandler handler) {
        if (handler == null) {
            throw new IllegalArgumentException("Cannot unbind without handler");
        } else {
            for (URI uri : bindURIs) {
                IoHandler bindHandler = bindHandlers.get(uri);
                if (bindHandler != null) {
                    if (!handler.equals(bindHandler)) {
                        throw new IllegalArgumentException("Cannot unbind with a handler " + handler
                                + " different from the one used for binding " + bindHandler + " to URI " + uri);
                    }
                    bindHandlers.remove(uri);
                }
            }
        }

        //
        // If there are balance URIs on the service, update the cluster state before physically unbinding
        // so that this cluster member is removed from the balance targets first.  This avoids a race
        // condition where a new connection comes in *after* this cluster member has been unbound but
        // before the cluster state has been updated, resulting in this cluster member being incorrectly
        // picked as the balancee.
        //
        if (balances != null && balances.size() > 0) {
            CollectionsFactory factory = clusterContext.getCollectionsFactory();
            if (factory != null) {
                Map<MemberId, Map<URI, List<URI>>> memberIdBalancerUriMap = factory
                        .getMap(MEMBERID_BALANCER_MAP_NAME);
                if (memberIdBalancerUriMap == null) {
                    throw new IllegalStateException("MemberId to BalancerMap is null");
                }

                MemberId localMember = clusterContext.getLocalMember();

                Map<URI, List<URI>> memberBalanceUriMap = memberIdBalancerUriMap.get(localMember);
                if (memberBalanceUriMap == null) {
                    throw new IllegalStateException("Member balancerMap is null for member " + localMember);
                }

                IMap<URI, Set<URI>> sharedBalanceUriMap = factory.getMap(BALANCER_MAP_NAME);
                for (URI balanceURI : balances) {
                    if (accepts != null) {
                        memberBalanceUriMap.remove(balanceURI);

                        // get and add to the list here instead of overwriting it
                        Set<URI> balanceUris = null;
                        Set<URI> newBalanceUris = null;
                        do {
                            boolean didRemove = false;
                            balanceUris = sharedBalanceUriMap.get(balanceURI);
                            if (balanceUris != null) {
                                newBalanceUris = new HashSet<>(balanceUris);
                                for (URI acceptUri : accepts) {
                                    didRemove = didRemove || newBalanceUris.remove(acceptUri);
                                }
                            }

                            if (!didRemove) {
                                // the current balancer entries were already removed, so since no work
                                // was done just skip the attempt to update cluster memory
                                break;
                            }
                            if (newBalanceUris.isEmpty()) {
                                if (sharedBalanceUriMap.remove(balanceURI, balanceUris)) {
                                    break;
                                } else {
                                    continue; // start over to refresh the newBalanceUris
                                }
                            }
                        } while (!sharedBalanceUriMap.replace(balanceURI, balanceUris, newBalanceUris));

                        GL.info(CLUSTER_LOGGER_NAME, "Cluster member {}: service {} unbound", localMember, serviceType);
                        GL.debug(CLUSTER_LOGGER_NAME, "Removed balance URIs {}, new global list is {}", accepts, newBalanceUris);
                    }
                }
                memberIdBalancerUriMap.put(localMember, memberBalanceUriMap);
            }
        }

        for (URI uri : bindURIs) {
            String uriScheme = uri.getScheme();
            Transport transport = transportFactory.getTransportForScheme(uriScheme);
            ResourceAddress address = bindings.remove(uri);
            if (address != null) {
                transport.getAcceptor(address).unbind(address);
            }
        }
    }

    @Override
    public ConnectFuture connect(URI connectURI, final IoHandler connectHandler,
                                 final IoSessionInitializer<ConnectFuture> connectSessionInitializer) {
        ResourceAddress address = resourceAddressFactory.newResourceAddress(connectURI, connectOptionsContext.asOptionsMap());
        return connect(address, connectHandler, connectSessionInitializer);
    }

    @Override
    public ConnectFuture connect(ResourceAddress address, final IoHandler connectHandler,
                                 final IoSessionInitializer<ConnectFuture> connectSessionInitializer) {
        String uriScheme = address.getExternalURI().getScheme();
        Transport transport = transportFactory.getTransportForScheme(uriScheme);

        BridgeConnector connector = transport.getConnector(address);
        return connector.connect(address, connectHandler, new IoSessionInitializer<ConnectFuture>() {
            @Override
            public void initializeSession(IoSession session, ConnectFuture future) {
                sessionInitializer.initializeSession(session, future);

                if (connectSessionInitializer != null) {
                    connectSessionInitializer.initializeSession(session, future);
                }
            }
        });
    }

    @Override
    public Collection<IoSessionEx> getActiveSessions() {
        return activeSessions.values();
    }

    @Override
    public IoSessionEx getActiveSession(Long sessionId) {
        if (sessionId == null) {
            return null;
        }
        return activeSessions.get(sessionId);
    }

    @Override
    public void addActiveSession(IoSessionEx session) {
        activeSessions.put(session.getId(), session);
    }

    @Override
    public void removeActiveSession(IoSessionEx session) {
        activeSessions.remove(session.getId());
    }

    /**
     * Session initializer for the 'standard' case.
     */
    public final class StandardSessionInitializer extends AbstractSessionInitializer {

        @Override
        public void initializeSession(IoSession session, ConnectFuture future) {
            super.initializeSession(session, future);
            session.getFilterChain().addLast(SESSION_FILTER_NAME, new ServiceSessionFilter());
        }
    }

    public class ServiceSessionFilter extends IoFilterAdapter<IoSessionEx> {

        @Override
        protected void doSessionOpened(NextFilter nextFilter, IoSessionEx session) throws Exception {
            activeSessions.put(session.getId(), session);
            super.doSessionOpened(nextFilter, session);
        }

        @Override
        protected void doSessionClosed(NextFilter nextFilter, IoSessionEx session) throws Exception {
            activeSessions.remove(session.getId());
            super.doSessionClosed(nextFilter, session);
        }
    }

    @Override
    public void init() throws Exception {
        getService().init(this);
    }

    @Override
    public void start() throws Exception {
        if (started.compareAndSet(false, true)) {
            getService().start();
        }
    }

    @Override
    public void stop() throws Exception {
        if (started.compareAndSet(true, false)) {
            // So management won't get screwed up, don't allow the service
            // to add any more sessions than there already are.
            service.quiesce();
            service.stop();
        }
    }

    @Override
    public void destroy() throws Exception {
        getService().destroy();
    }

    @Override
    public boolean supportsAccepts() {
        return this.supportsAccepts;
    }

    @Override
    public boolean supportsConnects() {
        return this.supportsConnects;
    }

    @Override
    public boolean supportsMimeMappings() {
        return this.supportsMimeMappings;
    }

    @Override
    public void setListsOfAcceptConstraintsByURI(List<Map<URI, Map<String, CrossSiteConstraintContext>>>
                                                             authorityToSetOfAcceptConstraintsByURI) {
        this.authorityToSetOfAcceptConstraintsByURI = authorityToSetOfAcceptConstraintsByURI;
    }

    @Override
    public Map<String, Object> getServiceSpecificObjects() {
        return serviceSpecificObjects;
    }

    @Override
    public IoSessionInitializer<ConnectFuture> getSessionInitializor() {
        return sessionInitializer;
    }

    @Override
    public void setSessionInitializor(IoSessionInitializer<ConnectFuture> ioSessionInitializer) {
        this.sessionInitializer = ioSessionInitializer;
    }
}
