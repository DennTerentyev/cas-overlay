/*
 * Licensed to Apereo under one or more contributor license
 * agreements. See the NOTICE file distributed with this work
 * for additional information regarding copyright ownership.
 * Apereo licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License.  You may obtain a
 * copy of the License at the following location:
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
package org.jasig.cas.support.oauth;

import com.codahale.metrics.annotation.Counted;
import com.codahale.metrics.annotation.Metered;
import com.codahale.metrics.annotation.Timed;
import org.apache.commons.lang3.StringUtils;
import org.jasig.cas.CentralAuthenticationService;
import org.jasig.cas.authentication.AuthenticationException;
import org.jasig.cas.authentication.principal.Principal;
import org.jasig.cas.authentication.principal.Service;
import org.jasig.cas.authentication.principal.SimpleWebApplicationServiceImpl;
import org.jasig.cas.services.ServicesManager;
import org.jasig.cas.support.oauth.authentication.principal.OAuthCredential;
import org.jasig.cas.support.oauth.metadata.ClientMetadata;
import org.jasig.cas.support.oauth.metadata.PrincipalMetadata;
import org.jasig.cas.support.oauth.personal.PersonalAccessToken;
import org.jasig.cas.support.oauth.personal.PersonalAccessTokenManager;
import org.jasig.cas.support.oauth.scope.InvalidScopeException;
import org.jasig.cas.support.oauth.scope.Scope;
import org.jasig.cas.support.oauth.scope.ScopeManager;
import org.jasig.cas.support.oauth.services.OAuthRegisteredService;
import org.jasig.cas.support.oauth.token.AccessToken;
import org.jasig.cas.support.oauth.token.AccessTokenImpl;
import org.jasig.cas.support.oauth.token.AuthorizationCode;
import org.jasig.cas.support.oauth.token.AuthorizationCodeImpl;
import org.jasig.cas.support.oauth.token.InvalidTokenException;
import org.jasig.cas.support.oauth.token.RefreshToken;
import org.jasig.cas.support.oauth.token.RefreshTokenImpl;
import org.jasig.cas.support.oauth.token.Token;
import org.jasig.cas.support.oauth.token.TokenType;
import org.jasig.cas.support.oauth.token.registry.TokenRegistry;
import org.jasig.cas.ticket.ServiceTicket;
import org.jasig.cas.ticket.TicketException;
import org.jasig.cas.ticket.TicketGrantingTicket;
import org.jasig.cas.ticket.registry.TicketRegistry;
import org.jasig.cas.util.UniqueTicketIdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.Assert;

import javax.validation.constraints.NotNull;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Central OAuth Service implementation.
 *
 * @author Michael Haselton
 * @since 4.1.0
 */
public final class CentralOAuthServiceImpl implements CentralOAuthService {

    /** Log instance for logging events, info, warnings, errors, etc. */
    private static final Logger LOGGER = LoggerFactory.getLogger(CentralOAuthService.class);

    /** CentralAuthenticationService for requesting tickets as needed. */
    @NotNull
    private final CentralAuthenticationService centralAuthenticationService;

    /** ServicesManager for verifying service endpoints. */
    @NotNull
    private final ServicesManager servicesManager;

    /** TokenRegistry for storing and retrieving tokens as needed. */
    @NotNull
    private final TicketRegistry ticketRegistry;

    /** TokenRegistry for storing and retrieving tokens as needed. */
    @NotNull
    private final TokenRegistry tokenRegistry;

    /** ScopeManager for storing and retrieving scopes as needed. */
    @NotNull
    private final ScopeManager scopeManager;

    /** PersonalAccessTokenManager for retrieving personal tokens. */
    @NotNull
    private final PersonalAccessTokenManager personalAccessTokenManager;

    /**
     * UniqueTicketIdGenerator to generate ids for AuthorizationCodes
     * created.
     */
    @NotNull
    private final UniqueTicketIdGenerator authorizationCodeUniqueIdGenerator;

    /**
     * UniqueTicketIdGenerator to generate ids for RefreshTokens
     * created.
     */
    @NotNull
    private final UniqueTicketIdGenerator refreshTokenUniqueIdGenerator;

    /**
     * UniqueTicketIdGenerator to generate ids for AccessTokens
     * created.
     */
    @NotNull
    private final UniqueTicketIdGenerator accessTokenUniqueIdGenerator;

    /**
     * Build the central oauth service implementation.
     *
     * @param centralAuthenticationService the central authentication service.
     * @param servicesManager the services manager.
     * @param ticketRegistry the ticket registry.
     * @param tokenRegistry the token registry.
     * @param authorizationCodeUniqueIdGenerator the authorization code unique id generator.
     * @param refreshTokenUniqueIdGenerator the refresh token unique id generator.
     * @param accessTokenUniqueIdGenerator the access token unique id generator.
     * @param scopeManager the scope manager.
     * @param personalAccessTokenManager the personal access token manager.
     */
    public CentralOAuthServiceImpl(final CentralAuthenticationService centralAuthenticationService,
                                   final ServicesManager servicesManager,
                                   final TicketRegistry ticketRegistry,
                                   final TokenRegistry tokenRegistry,
                                   final UniqueTicketIdGenerator authorizationCodeUniqueIdGenerator,
                                   final UniqueTicketIdGenerator refreshTokenUniqueIdGenerator,
                                   final UniqueTicketIdGenerator accessTokenUniqueIdGenerator,
                                   final ScopeManager scopeManager,
                                   final PersonalAccessTokenManager personalAccessTokenManager) {
        this.centralAuthenticationService = centralAuthenticationService;
        this.servicesManager = servicesManager;
        this.ticketRegistry = ticketRegistry;
        this.tokenRegistry = tokenRegistry;
        this.authorizationCodeUniqueIdGenerator = authorizationCodeUniqueIdGenerator;
        this.refreshTokenUniqueIdGenerator = refreshTokenUniqueIdGenerator;
        this.accessTokenUniqueIdGenerator = accessTokenUniqueIdGenerator;
        this.scopeManager = scopeManager;
        this.personalAccessTokenManager = personalAccessTokenManager;
    }

    @Override
    public OAuthRegisteredService getRegisteredService(final String clientId) {
        return OAuthUtils.getRegisteredOAuthService(servicesManager, clientId);
    }

    @Override
    public AuthorizationCode grantAuthorizationCode(final TokenType type, final String clientId,
                                                    final String ticketGrantingTicketId, final String redirectUri,
                                                    final Set<String> scopes) throws TicketException {
        final Service service = new SimpleWebApplicationServiceImpl(redirectUri);
        final ServiceTicket serviceTicket = centralAuthenticationService.grantServiceTicket(ticketGrantingTicketId, service);

        final AuthorizationCodeImpl authorizationCode = new AuthorizationCodeImpl(
                this.authorizationCodeUniqueIdGenerator.getNewTicketId(AuthorizationCode.PREFIX),
                type, clientId, serviceTicket.getGrantingTicket().getAuthentication().getPrincipal().getId(),
                serviceTicket, scopes);
        LOGGER.debug("{} : {}", OAuthConstants.AUTHORIZATION_CODE, authorizationCode);

        this.tokenRegistry.addToken(authorizationCode);

        return authorizationCode;
    }

    @Override
    public RefreshToken grantOfflineRefreshToken(final AuthorizationCode authorizationCode, final String redirectUri)
            throws InvalidTokenException {
        final Principal principal = authorizationCode.getServiceTicket().getGrantingTicket().getAuthentication().getPrincipal();
        final OAuthCredential credential = new OAuthCredential(principal.getId(), principal.getAttributes(), TokenType.OFFLINE);

        final TicketGrantingTicket ticketGrantingTicket;
        try {
            ticketGrantingTicket = centralAuthenticationService.createTicketGrantingTicket(credential);
        } catch (final AuthenticationException | TicketException e) {
            throw new InvalidTokenException(authorizationCode.getId());
        }

        final RefreshToken refreshToken = new RefreshTokenImpl(
                refreshTokenUniqueIdGenerator.getNewTicketId(RefreshToken.PREFIX), authorizationCode.getClientId(),
                authorizationCode.getServiceTicket().getGrantingTicket().getAuthentication().getPrincipal().getId(),
                ticketGrantingTicket, authorizationCode.getServiceTicket().getService(), authorizationCode.getScopes());
        LOGGER.debug("Offline {} : {}", OAuthConstants.REFRESH_TOKEN, refreshToken);

        // remove the service ticket, doing so will cascade and remove the authorization code token
        ticketRegistry.deleteTicket(authorizationCode.getTicket().getId());
        tokenRegistry.addToken(refreshToken);

        return refreshToken;
    }

    @Override
    public AccessToken grantCASAccessToken(final TicketGrantingTicket ticketGrantingTicket, final Service service)
            throws TicketException {
        final AccessToken accessToken = new AccessTokenImpl(
                accessTokenUniqueIdGenerator.getNewTicketId(AccessToken.PREFIX), TokenType.CAS, null,
                ticketGrantingTicket.getAuthentication().getPrincipal().getId(), ticketGrantingTicket, service, null,
                scopeManager.getCASScopes());
        LOGGER.debug("CAS {} : {}", OAuthConstants.ACCESS_TOKEN, accessToken);

        tokenRegistry.addToken(accessToken);

        return accessToken;
    }

    @Override
    public AccessToken grantPersonalAccessToken(final PersonalAccessToken personalAccessToken) throws InvalidTokenException {
        final OAuthCredential credential = new OAuthCredential(personalAccessToken.getPrincipalId(), TokenType.PERSONAL);

        final TicketGrantingTicket ticketGrantingTicket;
        try {
            ticketGrantingTicket = centralAuthenticationService.createTicketGrantingTicket(credential);
        } catch (final AuthenticationException | TicketException e) {
            throw new InvalidTokenException(personalAccessToken.getId());
        }

        final AccessToken accessToken = new AccessTokenImpl(
                personalAccessToken.getId(), TokenType.PERSONAL, null, personalAccessToken.getPrincipalId(), ticketGrantingTicket,
                null, null, personalAccessToken.getScopes());
        LOGGER.debug("Personal {} : {}", OAuthConstants.ACCESS_TOKEN, accessToken);

        tokenRegistry.addToken(accessToken);

        return accessToken;
    }

    @Override
    public AccessToken grantOfflineAccessToken(final RefreshToken refreshToken) throws InvalidTokenException {
        final ServiceTicket serviceTicket;
        try {
            serviceTicket = centralAuthenticationService.grantServiceTicket(refreshToken.getTicketGrantingTicket().getId(),
                    refreshToken.getService());
        } catch (final TicketException e) {
            throw new InvalidTokenException(refreshToken.getId());
        }

        final AccessToken accessToken = new AccessTokenImpl(
                accessTokenUniqueIdGenerator.getNewTicketId(AccessToken.PREFIX), TokenType.OFFLINE, refreshToken.getClientId(),
                refreshToken.getTicketGrantingTicket().getAuthentication().getPrincipal().getId(), null, null,
                serviceTicket, refreshToken.getScopes());
        LOGGER.debug("Offline {} : {}", OAuthConstants.ACCESS_TOKEN, accessToken);

        tokenRegistry.addToken(accessToken);

        return accessToken;
    }

    @Override
    public AccessToken grantOnlineAccessToken(final AuthorizationCode authorizationCode) throws InvalidTokenException {
        final Principal principal = authorizationCode.getServiceTicket().getGrantingTicket().getAuthentication().getPrincipal();
        final OAuthCredential credential = new OAuthCredential(principal.getId(), principal.getAttributes(), TokenType.ONLINE);

        final TicketGrantingTicket ticketGrantingTicket;
        try {
            ticketGrantingTicket = centralAuthenticationService.createTicketGrantingTicket(credential);
        } catch (final AuthenticationException | TicketException e) {
            throw new InvalidTokenException(authorizationCode.getId());
        }

        final AccessToken accessToken = new AccessTokenImpl(
                accessTokenUniqueIdGenerator.getNewTicketId(AccessToken.PREFIX), TokenType.ONLINE, authorizationCode.getClientId(),
                authorizationCode.getServiceTicket().getGrantingTicket().getAuthentication().getPrincipal().getId(),
                ticketGrantingTicket, authorizationCode.getServiceTicket().getService(), null, authorizationCode.getScopes());
        LOGGER.debug("Online {} : {}", OAuthConstants.ACCESS_TOKEN, accessToken);

        // remove the service ticket, doing so will cascade and remove the authorization code token
        ticketRegistry.deleteTicket(authorizationCode.getTicket().getId());
        tokenRegistry.addToken(accessToken);

        return accessToken;
    }

    @Override
    public Boolean revokeToken(final Token token) {
        return ticketRegistry.deleteTicket(token.getTicket().getId());
    }

    @Override
    public Boolean revokeClientTokens(final String clientId, final String clientSecret) {
        final OAuthRegisteredService service = getRegisteredService(clientId);
        if (service == null) {
            LOGGER.error("OAuth Registered Service could not be found for clientId : {}", clientId);
            return Boolean.FALSE;
        }
        if (!service.getClientSecret().equals(clientSecret)) {
            LOGGER.error("Invalid client secret");
            return Boolean.FALSE;
        }

        final Collection<RefreshToken> refreshTokens = tokenRegistry.getClientTokens(clientId, RefreshToken.class);
        for (final RefreshToken token : refreshTokens) {
            LOGGER.debug("Revoking refresh token : {}", token.getId());
            ticketRegistry.deleteTicket(token.getTicket().getId());
        }

        final Collection<AccessToken> accessTokens = tokenRegistry.getClientTokens(clientId, AccessToken.class);
        for (final AccessToken token : accessTokens) {
            LOGGER.debug("Revoking access token : {}", token.getId());
            ticketRegistry.deleteTicket(token.getTicket().getId());
        }

        return Boolean.TRUE;
    }

    @Override
    public Boolean revokeClientPrincipalTokens(final AccessToken accessToken, final String clientId) {
        final String targetClientId;
        if (accessToken.getType() == TokenType.CAS) {
            // Only CAS Tokens are allowed to specify the client id for revocation.
            if (StringUtils.isBlank(clientId)) {
                LOGGER.warn("CAS Token used for revocation, Client ID must be specified");
                return Boolean.FALSE;
            }
            targetClientId = clientId;
        } else {
            if (!accessToken.getClientId().equals(clientId)) {
                LOGGER.warn("Access Token's Client ID and specified Client ID must match");
                return Boolean.FALSE;
            }
            targetClientId = accessToken.getClientId();
        }

        final Collection<RefreshToken> refreshTokens = tokenRegistry.getClientPrincipalTokens(targetClientId,
                accessToken.getPrincipalId(), RefreshToken.class);
        for (final RefreshToken token : refreshTokens) {
            LOGGER.debug("Revoking refresh token : {}", token.getId());
            ticketRegistry.deleteTicket(token.getTicketGrantingTicket().getId());
        }

        final Collection<AccessToken> accessTokens = tokenRegistry.getClientPrincipalTokens(targetClientId,
                accessToken.getPrincipalId(), TokenType.ONLINE, AccessToken.class);
        for (final AccessToken token : accessTokens) {
            LOGGER.debug("Revoking access token : {}", token.getId());
            ticketRegistry.deleteTicket(token.getTicketGrantingTicket().getId());
        }

        return Boolean.TRUE;
    }

    @Override
    public ClientMetadata getClientMetadata(final String clientId, final String clientSecret) {
        final OAuthRegisteredService service = getRegisteredService(clientId);
        if (service == null) {
            LOGGER.error("OAuth Registered Service could not be found for clientId : {}", clientId);
            return null;
        }
        if (!service.getClientSecret().equals(clientSecret)) {
            LOGGER.error("Invalid Client Secret specified for Client ID [{}]]", clientId);
            return null;
        }

        return new ClientMetadata(service.getClientId(), service.getName(), service.getDescription(),
                tokenRegistry.getPrincipalCount(clientId));
    }

    @Override
    public Collection<PrincipalMetadata> getPrincipalMetadata(final AccessToken accessToken)
            throws InvalidTokenException {
        if (accessToken.getType() != TokenType.CAS) {
            // Only CAS Tokens are allowed to access principal metadata.
            LOGGER.warn("Principal Metadata can only be accessed with an Access Token of type CAS");
            throw new InvalidTokenException(accessToken.getId());
        }

        final Map<String, PrincipalMetadata> metadata = new HashMap<>();
        for (final Token token : tokenRegistry.getPrincipalTokens(accessToken.getPrincipalId(), RefreshToken.class)) {
            final PrincipalMetadata serviceDetail;
            if (!metadata.containsKey(token.getClientId())) {
                final OAuthRegisteredService service = getRegisteredService(token.getClientId());

                serviceDetail = new PrincipalMetadata(service.getClientId(), service.getName(), service.getDescription());
                metadata.put(token.getClientId(), serviceDetail);
            } else {
                serviceDetail = metadata.get(token.getClientId());
            }

            serviceDetail.getScopes().addAll(token.getScopes());
        }

        for (final Token token : tokenRegistry.getPrincipalTokens(accessToken.getPrincipalId(), AccessToken.class)) {
            final PrincipalMetadata serviceDetail;
            if (!metadata.containsKey(token.getClientId())) {
                final OAuthRegisteredService service = getRegisteredService(token.getClientId());

                serviceDetail = new PrincipalMetadata(service.getClientId(), service.getName(), service.getDescription());
                metadata.put(token.getClientId(), serviceDetail);
            } else {
                serviceDetail = metadata.get(token.getClientId());
            }

            serviceDetail.getScopes().addAll(token.getScopes());
        }

        return metadata.values();
    }

    @Override
    public Boolean isRefreshToken(final String clientId, final String principalId, final Set<String> scopes) {
        return tokenRegistry.isToken(clientId, principalId, scopes, RefreshToken.class);
    }

    @Override
    public Boolean isAccessToken(final TokenType type, final String clientId, final String principalId,
                                 final Set<String> scopes) {
        return tokenRegistry.isToken(type, clientId, principalId, scopes, AccessToken.class);
    }

    @Override
    public Token getToken(final String tokenId) throws InvalidTokenException {
        Assert.notNull(tokenId, "tokenId cannot be null");

        if (tokenId.startsWith(AuthorizationCode.PREFIX)) {
            return getToken(tokenId, AuthorizationCode.class);
        } else if (tokenId.startsWith(RefreshToken.PREFIX)) {
            return getToken(tokenId, RefreshToken.class);
        }
        return getToken(tokenId, AccessToken.class);
    }

    @Override
    @Timed(name = "GET_TOKEN_TIMER")
    @Metered(name = "GET_TOKEN_METER")
    @Counted(name="GET_TOKEN_COUNTER", monotonic=true)
    public <T extends Token> T getToken(final String tokenId, final Class<T> clazz)
            throws InvalidTokenException {
        Assert.notNull(tokenId, "tokenId cannot be null");

        final T token = this.tokenRegistry.getToken(tokenId, clazz);
        if (token == null) {
            LOGGER.error("Token [{}] by type [{}] cannot be found in the token registry.", tokenId, clazz.getSimpleName());
            throw new InvalidTokenException(tokenId);
        }

        if (token.getTicket().isExpired()) {
            // cleanup the expired ticket and token.
            ticketRegistry.deleteTicket(token.getTicket().getId());

            LOGGER.error("Token [{}] ticket [{}] is expired.", tokenId, token.getTicket().getId());
            throw new InvalidTokenException(tokenId);
        }

        return token;
    }

    @Override
    public PersonalAccessToken getPersonalAccessToken(final String tokenId) {
        Assert.notNull(tokenId, "tokenId cannot be null");

        if (personalAccessTokenManager != null) {
            return personalAccessTokenManager.getToken(tokenId);
        }

        return null;
    }

    @Override
    public Map<String, Scope> getScopes(final Set<String> scopeSet) throws InvalidScopeException {
        Assert.notNull(scopeSet, "scopeSet cannot be null");

        final Map<String, Scope> scopeMap = new HashMap<>();

        for (final String scope : scopeSet) {
            final Scope oAuthScope = scopeManager.getScope(scope);
            if (oAuthScope == null) {
                LOGGER.error("Could not find requested scope: {}", scope);
                throw new InvalidScopeException(scope);
            }
            scopeMap.put(oAuthScope.getName(), oAuthScope);
        }

        for (final Scope defaultScope : scopeManager.getDefaults()) {
            scopeMap.put(defaultScope.getName(), defaultScope);
        }

        return scopeMap;
    }
}


